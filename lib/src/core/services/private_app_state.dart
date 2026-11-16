import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:oronbox/src/core/errors/coded_error.dart';
import 'package:oronbox/src/core/logging/logging_service.dart';
import 'package:oronbox/src/core/storage/app_data_directory.dart';

import 'shared_prefs_service.dart';

class PrivateStateException implements CodedError {
  const PrivateStateException(this.code);
  @override
  final String code;
  @override
  String get message => '';
  @override
  Object? get details => null;
  @override
  String toString() => code;
}

/// Small account and pairing records; bulk data belongs in application files.
class PrivateAppState {
  PrivateAppState({FlutterSecureStorage? secureStorage, AppDataDirectory? lock})
    : _secure =
          secureStorage ??
          const FlutterSecureStorage(
            aOptions: AndroidOptions(resetOnError: false),
            mOptions: MacOsOptions(usesDataProtectionKeychain: false),
          ),
      _lock = lock == null ? null : Future.value(lock);

  static final _log = getLogger('PrivateAppState');

  static PrivateAppState _instance = PrivateAppState();
  static PrivateAppState get instance => _instance;
  @visibleForTesting
  static set instanceForTesting(PrivateAppState? value) =>
      _instance = value ?? PrivateAppState();

  static const miToken = 'mi.account.token';
  static const huamiToken = 'huami.account.token';
  static const huamiUsername = 'huami.account.username';
  static const pairedDevices = 'paired_devices';
  static const _groups = {
    'xiaomi': [miToken, 'mi_account.username', 'mi_account.password'],
    'huami': [
      huamiToken,
      huamiUsername,
      'huami_account.username',
      'huami_account.password',
    ],
    'devices': [pairedDevices],
  };
  static const _miLegacy = 'mi_account.session';

  // Single root vault key to minimize OS keychain prompts and aggregate storage.
  static const _vaultKey = 'oronbox.private.vault.v1';
  static const _vaultPresent = 'vault.present';
  static const _backupRecoveryKey = 'oronbox.private.backup-recovery.v1';

  final FlutterSecureStorage _secure;
  Future<AppDataDirectory>? _lock;
  Future<AppDataDirectory> get _directory =>
      _lock ??= openAppDataDirectory('private');
  final _prefs = SharedPrefsService.instance;

  Map<String, Map<String, Object?>> _vaultGroups = {};
  String? _backupRecoveryValue;
  Map<String, Object?> _values = {};

  Future<void>? _initializing;
  bool _ready = false;
  bool get isReady => _ready;

  Future<void> initialize() {
    if (_ready) return Future.value();
    return _initializing ??= _reload().whenComplete(() => _initializing = null);
  }

  Future<void> reload({String? key}) async {
    await initialize();
    await _reload(key: key);
  }

  Future<void> _reload({String? key}) async {
    try {
      await _prefs.init();
      await (await _directory).exclusive(() async {
        await _prefs.reload();
        await _loadVaultInsideLock();
        _syncValues();
        _ready = true;
      });
    } on PrivateStateException {
      rethrow;
    } catch (_) {
      throw const PrivateStateException('private_state_read_failed');
    }
  }

  Future<void> _loadVaultInsideLock() async {
    // A failed read is not an absent key: never replace protected data with a fallback.
    final rawVault = await _secure.read(key: _vaultKey);
    if (rawVault != null) {
      _unpackVault(rawVault);
      await _markVaultPresent();
      await _cleanupLegacySources();
      return;
    }
    if (await (await _directory).read(_vaultPresent) != null) {
      throw const PrivateStateException('private_state_read_failed');
    }

    final recovered = await _recoverOrMigrateData();
    if (recovered != null) {
      _vaultGroups = recovered.$1;
      _backupRecoveryValue = recovered.$2;
      await _commitVault();
      await _cleanupLegacySources();
      return;
    }

    if (await _isAnyPresent()) {
      throw const PrivateStateException('private_state_read_failed');
    }

    _vaultGroups = {for (final group in _groups.keys) group: {}};
    _backupRecoveryValue = null;
    await _commitVault();
  }

  Future<(Map<String, Map<String, Object?>>, String?)?>
  _recoverOrMigrateData() async {
    final groups = <String, Map<String, Object?>>{};
    var hasAnyData = false;

    for (final group in _groups.keys) {
      Map<String, Object?> groupData = {};
      final raw = await _secure.read(key: 'oronbox.private.$group.v1');

      if (raw != null) {
        groupData = _decodeLegacyGroup(group, raw);
        hasAnyData = true;
      } else {
        groupData = _loadLegacyGroup(group);
        if (groupData.isNotEmpty) {
          hasAnyData = true;
        }
      }
      groups[group] = groupData;
    }

    final recovery = await _secure.read(key: _backupRecoveryKey);
    if (recovery != null) hasAnyData = true;

    if (!hasAnyData) return null;
    return (groups, recovery);
  }

  void _unpackVault(String raw) {
    final decoded = jsonDecode(raw);
    if (decoded is! Map ||
        decoded['version'] != 1 ||
        decoded['groups'] is! Map) {
      throw const PrivateStateException('private_state_read_failed');
    }
    final groupsMap = (decoded['groups'] as Map).cast<String, Object?>();
    final groups = <String, Map<String, Object?>>{};
    for (final groupKey in _groups.keys) {
      final rawGroup = groupsMap[groupKey];
      if (rawGroup != null) {
        if (rawGroup is! Map) {
          throw const PrivateStateException('private_state_read_failed');
        }
        final groupValues = rawGroup.cast<String, Object?>();
        _validate(groupKey, groupValues);
        groups[groupKey] = {
          for (final entry in groupValues.entries)
            entry.key: entry.key == pairedDevices
                ? List<String>.from(entry.value! as List)
                : entry.value,
        };
      } else {
        groups[groupKey] = {};
      }
    }
    _vaultGroups = groups;
    final recovery = decoded['backup_recovery'];
    _backupRecoveryValue = recovery is String ? recovery : null;
  }

  void _syncValues() {
    final flat = <String, Object?>{};
    for (final group in _vaultGroups.values) {
      flat.addAll(group);
    }
    _values = flat;
  }

  String? readString(String key) {
    _checkReady();
    _group(key);
    return _values[key] as String?;
  }

  List<String>? readStringList(String key) {
    _checkReady();
    _group(key);
    final value = _values[key] as List<String>?;
    return value == null ? null : List.of(value);
  }

  void _checkReady() {
    if (!_ready) throw const PrivateStateException('private_state_read_failed');
  }

  String _group(String key) => _groups.entries
      .firstWhere(
        (group) => group.value.contains(key),
        orElse: () => throw ArgumentError('Unknown private state key'),
      )
      .key;

  Map<String, Object?> _loadLegacyGroup(String group) => {
    for (final key in _groups[group]!)
      if (_legacy(key) case final value?) key: value,
  };

  Object? _legacy(String key) => key == pairedDevices
      ? _prefs.getStringList(key)
      : _prefs.getString(key) ??
            (key == miToken ? _prefs.getString(_miLegacy) : null);

  Map<String, Object?> _decodeLegacyGroup(String group, String raw) {
    final decoded = jsonDecode(raw);
    if (decoded is! Map ||
        decoded['version'] != 1 ||
        decoded['values'] is! Map) {
      throw const PrivateStateException('private_state_read_failed');
    }
    final values = (decoded['values'] as Map).cast<String, Object?>();
    _validate(group, values);
    return {
      for (final entry in values.entries)
        entry.key: entry.key == pairedDevices
            ? List<String>.from(entry.value! as List)
            : entry.value,
    };
  }

  void _validate(String group, Map<String, Object?> values) {
    for (final entry in values.entries) {
      final value = entry.value;
      if (!_groups[group]!.contains(entry.key) ||
          (entry.key == pairedDevices
              ? value is! List || value.any((row) => row is! String)
              : value is! String)) {
        throw const PrivateStateException('private_state_read_failed');
      }
    }
  }

  Future<void> _commitVault() async {
    for (final entry in _vaultGroups.entries) {
      _validate(entry.key, entry.value);
    }
    final payload = jsonEncode({
      'version': 1,
      'groups': _vaultGroups,
      'backup_recovery': _backupRecoveryValue,
    });
    await _secure.write(key: _vaultKey, value: payload);
    if (await _secure.read(key: _vaultKey) != payload) {
      throw const PrivateStateException('private_state_write_failed');
    }
    await _markVaultPresent();
  }

  Future<void> _markVaultPresent() async {
    final directory = await _directory;
    if (await directory.read(_vaultPresent) == null) {
      await directory.write(_vaultPresent, Uint8List.fromList([1]));
    }
  }

  Future<bool> _isAnyPresent() async {
    final directory = await _directory;
    if (await directory.read(_vaultPresent) != null) return true;
    for (final group in _groups.keys) {
      if (await directory.read('$group.present') != null) return true;
    }
    return false;
  }

  Future<void> _removeLegacy(String group) async {
    for (final key in [..._groups[group]!, if (group == 'xiaomi') _miLegacy]) {
      if (_prefs.value(key) != null && !await _prefs.remove(key)) {
        throw const PrivateStateException('private_state_write_failed');
      }
    }
  }

  Future<void> _cleanupLegacySources() async {
    for (final group in _groups.keys) {
      await _removeLegacy(group);
      await _secure.delete(key: 'oronbox.private.$group.v1');
    }
    await _secure.delete(key: _backupRecoveryKey);
  }

  Future<String?> readBackupRecovery() async {
    await initialize();
    return _backupRecoveryValue;
  }

  Future<void> writeBackupRecovery(String? value) async {
    await initialize();
    try {
      await (await _directory).exclusive(() async {
        await _loadVaultInsideLock();
        _backupRecoveryValue = value;
        await _commitVault();
        try {
          await _secure.delete(key: _backupRecoveryKey);
        } catch (_) {}
      });
    } on PrivateStateException {
      rethrow;
    } catch (_) {
      throw const PrivateStateException('private_state_write_failed');
    }
  }

  Future<bool> update(
    Map<String, Object?> changes, {
    bool Function(Map<String, Object?> current)? when,
  }) async {
    if (changes.isEmpty) return true;
    final group = _group(changes.keys.first);
    if (changes.keys.any((key) => _group(key) != group)) {
      throw ArgumentError('Private state updates must belong to one account');
    }
    await initialize();
    try {
      return await (await _directory).exclusive(() async {
        await _prefs.reload();
        await _loadVaultInsideLock();
        final currentGroup = Map<String, Object?>.from(
          _vaultGroups[group] ?? {},
        );
        if (when != null && !when(Map.unmodifiable(currentGroup))) {
          _syncValues();
          return false;
        }
        for (final entry in changes.entries) {
          if (entry.value == null) {
            currentGroup.remove(entry.key);
          } else {
            currentGroup[entry.key] = entry.value is List<String>
                ? List<String>.from(entry.value! as List<String>)
                : entry.value;
          }
        }
        _validate(group, currentGroup);
        _vaultGroups[group] = currentGroup;
        await _commitVault();
        await _removeLegacy(group);
        _syncValues();
        return true;
      });
    } on PrivateStateException {
      rethrow;
    } catch (_) {
      throw const PrivateStateException('private_state_write_failed');
    }
  }

  Future<void> resetCorruptedState() async {
    try {
      await (await _directory).exclusive(() async {
        _ready = false;
        await _prefs.reload();
        // Keep a verified empty authority so stale data can never be reimported.
        _vaultGroups = {for (final group in _groups.keys) group: {}};
        _backupRecoveryValue = null;
        await _commitVault();
        await _cleanupLegacySources();
        _syncValues();
        _ready = true;
      });
    } catch (e, st) {
      _log.severe('Failed to reset corrupted private state', e, st);
      throw const PrivateStateException('private_state_write_failed');
    }
  }
}
