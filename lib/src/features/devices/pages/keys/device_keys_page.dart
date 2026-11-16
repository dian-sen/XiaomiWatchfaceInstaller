import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:oronbox/src/app/generated/app_localizations.dart';
import 'package:oronbox/src/app/widgets/page_container.dart';
import 'package:oronbox/src/app/widgets/sys_app_bar.dart';
import 'package:oronbox/src/features/accounts/models/mi_account_models.dart';
import 'package:oronbox/src/features/devices/controllers/device_manager.dart';
import 'package:oronbox/src/features/devices/widgets/device_import_dialog.dart';
import 'package:oronbox/src/features/settings/pages/settings_page.dart';

class DeviceKeysPage extends ConsumerStatefulWidget {
  const DeviceKeysPage({super.key});

  @override
  ConsumerState<DeviceKeysPage> createState() => _DeviceKeysPageState();
}

class _DeviceKeysPageState extends ConsumerState<DeviceKeysPage>
    with WidgetsBindingObserver {
  static const _channel = MethodChannel('watchface/device_keys');
  bool _busy = false;
  List<Map<String, Object?>> _choices = const [];
  String? _message;
  int _generation = 0;
  bool get _android =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    if (_android) _channel.invokeMethod<void>('protect');
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached) {
      _clear();
    }
  }

  void _clear() {
    ++_generation;
    if (_android) _channel.invokeMethod<void>('clear');
    if (mounted) {
      setState(() {
        _choices = const [];
        _busy = false;
      });
    }
  }

  Future<void> _readRoot() async {
    if (_busy || !_android) return;
    final l10n = AppLocalizations.of(context)!;
    final request = ++_generation;
    setState(() {
      _busy = true;
      _choices = const [];
      _message = null;
    });
    try {
      final rows = await _channel.invokeListMethod<Object?>('readRoot');
      if (!mounted || request != _generation) return;
      setState(() {
        _choices =
            rows
                ?.whereType<Map>()
                .map((row) => row.cast<String, Object?>())
                .toList() ??
            [];
        _message = _choices.isEmpty ? l10n.keyCenterNoRecords : null;
      });
    } catch (_) {
      if (mounted && request == _generation) {
        setState(() {
          _message = l10n.keyCenterRootFailed;
        });
      }
    } finally {
      if (mounted && request == _generation) {
        setState(() {
          _busy = false;
        });
      }
    }
  }

  Future<void> _import(Map<String, Object?> row) async {
    if (_busy) return;
    final l10n = AppLocalizations.of(context)!;
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l10n.keyCenterImportTitle),
        content: Text(
          '${row['model']}\n${row['mac']}\n\n${l10n.keyCenterImportHint}',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(l10n.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(l10n.keyCenterImport),
          ),
        ],
      ),
    );
    if (accepted != true || !mounted) return;
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      final value = await _channel.invokeMapMethod<String, Object?>('consume', {
        'token': row['token'],
      });
      if (value == null) throw const FormatException();
      final count = await ref
          .read(deviceManagerProvider.notifier)
          .importMiCloudDevices([
            MiCloudDevice(
              name: value['model']! as String,
              model: value['model']! as String,
              mac: value['mac']! as String,
              authKey: value['authKey']! as String,
              firmwareVersion: '',
              serialNumber: '',
            ),
          ]);
      if (mounted) {
        setState(() {
          _message = count > 0
              ? l10n.keyCenterImported
              : l10n.keyCenterRootFailed;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _message = l10n.keyCenterRootFailed;
        });
      }
    } finally {
      if (mounted) {
        setState(() {
          _choices = const [];
          _busy = false;
        });
      }
    }
  }

  @override
  void dispose() {
    ++_generation;
    WidgetsBinding.instance.removeObserver(this);
    if (_android) _channel.invokeMethod<void>('release');
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    return Scaffold(
      appBar: SysAppBar(secondary: true, title: Text(l10n.keyCenterTitle)),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          PageContainer(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  l10n.keyCenterDescription,
                  style: Theme.of(context).textTheme.bodyLarge,
                ),
                const SizedBox(height: 24),
                _source(
                  Icons.account_circle_outlined,
                  l10n.keyCenterXiaomi,
                  l10n.keyCenterXiaomiHint,
                  () => SettingsPage.showMiAccountLoginDialog(context, ref),
                ),
                _source(
                  Icons.favorite_outline,
                  l10n.keyCenterHuami,
                  l10n.keyCenterHuamiHint,
                  () => context.push('/settings/accounts'),
                ),
                _source(
                  Icons.description_outlined,
                  l10n.keyCenterLogs,
                  l10n.keyCenterLogsHint,
                  () => showWearableLogSyncDialog(context, ref),
                ),
                if (_android)
                  _source(
                    Icons.key_outlined,
                    l10n.keyCenterRoot,
                    l10n.keyCenterRootHint,
                    _readRoot,
                  ),
                if (_busy) const LinearProgressIndicator(),
                if (_message != null)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    child: Text(_message!),
                  ),
                for (final row in _choices)
                  Card(
                    child: ListTile(
                      title: Text(row['model'] as String? ?? ''),
                      subtitle: Text(
                        '${row['mac']}\n••••••••••••••••••••••••••••••••',
                      ),
                      isThreeLine: true,
                      trailing: TextButton(
                        onPressed: _busy ? null : () => _import(row),
                        child: Text(l10n.keyCenterImport),
                      ),
                    ),
                  ),
                const SizedBox(height: 16),
                OutlinedButton.icon(
                  onPressed: _busy
                      ? null
                      : () {
                          _clear();
                          context.push('/devices/switch');
                        },
                  icon: const Icon(Icons.watch_outlined),
                  label: Text(l10n.keyCenterDevices),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _source(
    IconData icon,
    String title,
    String detail,
    VoidCallback onTap,
  ) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Card(
      child: ListTile(
        leading: Icon(icon),
        title: Text(title),
        subtitle: Text(detail),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 20,
          vertical: 12,
        ),
        trailing: const Icon(Icons.chevron_right),
        onTap: _busy
            ? null
            : () {
                _clear();
                onTap();
              },
      ),
    ),
  );
}
