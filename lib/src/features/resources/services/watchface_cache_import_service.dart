import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class WatchfaceCacheFile {
  const WatchfaceCacheFile({
    required this.token,
    required this.name,
    required this.location,
    required this.size,
    required this.modifiedAt,
  });

  final String token;
  final String name;
  final String location;
  final int size;
  final DateTime modifiedAt;

  factory WatchfaceCacheFile.fromPlatform(Object? value) {
    if (value is! Map) throw const FormatException('Invalid cache entry');
    final token = value['token'];
    final name = value['name'];
    final location = value['location'];
    final size = value['size'];
    final modifiedAt = value['modifiedAt'];
    if (token is! String ||
        token.isEmpty ||
        name is! String ||
        name.isEmpty ||
        location is! String ||
        size is! int ||
        size < 0 ||
        modifiedAt is! int ||
        modifiedAt.abs() > 8640000000000000) {
      throw const FormatException('Invalid cache entry');
    }
    return WatchfaceCacheFile(
      token: token,
      name: name,
      location: location,
      size: size,
      modifiedAt: DateTime.fromMillisecondsSinceEpoch(modifiedAt),
    );
  }
}

class WatchfaceCacheDiagnostic {
  const WatchfaceCacheDiagnostic({
    required this.code,
    required this.path,
    required this.reason,
  });

  final String code;
  final String path;
  final String reason;

  factory WatchfaceCacheDiagnostic.fromPlatform(Object? value) {
    if (value is! Map ||
        value['code'] is! String ||
        value['path'] is! String ||
        value['reason'] is! String) {
      throw const FormatException('Invalid cache diagnostic');
    }
    return WatchfaceCacheDiagnostic(
      code: value['code'] as String,
      path: value['path'] as String,
      reason: value['reason'] as String,
    );
  }
}

class WatchfaceCacheScan {
  const WatchfaceCacheScan({
    required this.files,
    required this.truncated,
    this.diagnostics = const [],
  });

  final List<WatchfaceCacheFile> files;
  final bool truncated;
  final List<WatchfaceCacheDiagnostic> diagnostics;

  factory WatchfaceCacheScan.fromPlatform(Map<Object?, Object?> value) {
    final rawFiles = value['files'];
    final rawDiagnostics = value['diagnostics'] ?? const [];
    if (rawFiles is! List ||
        rawDiagnostics is! List ||
        (value['truncated'] != null && value['truncated'] is! bool)) {
      throw const FormatException('Invalid cache scan');
    }
    return WatchfaceCacheScan(
      files: List.unmodifiable(
        rawFiles
            .map(WatchfaceCacheFile.fromPlatform)
            .where((file) => file.name == 'resource.bin'),
      ),
      truncated: value['truncated'] == true,
      diagnostics: List.unmodifiable(
        rawDiagnostics.map(WatchfaceCacheDiagnostic.fromPlatform),
      ),
    );
  }
}

class WatchfaceCachePreview {
  const WatchfaceCachePreview({required this.path, required this.name});

  final String path;
  final String name;

  factory WatchfaceCachePreview.fromPlatform(Object? value) {
    if (value is! Map ||
        !_isPrivatePath(value['path']) ||
        value['name'] is! String ||
        (value['name'] as String).isEmpty) {
      throw const FormatException('Invalid cache preview');
    }
    return WatchfaceCachePreview(
      path: value['path'] as String,
      name: value['name'] as String,
    );
  }
}

class WatchfaceCacheBundle {
  const WatchfaceCacheBundle({
    required this.path,
    required this.name,
    required this.embeddedId,
    required this.previews,
    required this.warnings,
    required this.nameSource,
  });

  final String path;
  final String name;
  final String? embeddedId;
  final List<WatchfaceCachePreview> previews;
  final List<String> warnings;
  final String nameSource;

  factory WatchfaceCacheBundle.fromPlatform(Map<Object?, Object?> value) {
    final path = value['path'];
    final name = value['name'];
    final embeddedId = value['embeddedId'];
    final previews = value['previews'];
    final warnings = value['warnings'];
    final nameSource = value['nameSource'];
    if (!_isPrivatePath(path) ||
        name is! String ||
        name.isEmpty ||
        (embeddedId != null &&
            (embeddedId is! String ||
                !RegExp(r'^(?:[0-9]{9}|[0-9]{12})$').hasMatch(embeddedId))) ||
        previews is! List ||
        previews.length > 8 ||
        warnings is! List ||
        warnings.any((warning) => warning is! String) ||
        !const ['xml', 'bin', 'filename'].contains(nameSource)) {
      throw const FormatException('Invalid cache bundle');
    }
    return WatchfaceCacheBundle(
      path: path as String,
      name: name,
      embeddedId: embeddedId as String?,
      previews: List.unmodifiable(
        previews.map(WatchfaceCachePreview.fromPlatform),
      ),
      warnings: List<String>.unmodifiable(warnings),
      nameSource: nameSource as String,
    );
  }
}

class WatchfaceCacheImportService {
  WatchfaceCacheImportService();

  static const _channel = MethodChannel('oronbox/watchface_cache');

  static bool get isSupported =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  final Set<String> _pendingCleanup = {};

  Future<String> authorize(String mode) async {
    if (!isSupported || !const ['root', 'shizuku'].contains(mode)) {
      throw UnsupportedError('Unsupported cache access mode');
    }
    final result = await _channel.invokeMethod<Object?>('authorize', {
      'mode': mode,
    });
    if (result != true) {
      throw const FormatException('Access was not authorized');
    }
    return mode;
  }

  Future<WatchfaceCacheScan> scan({String mode = 'root'}) async {
    if (!isSupported) throw UnsupportedError('Android cache import only');
    if (!const ['root', 'shizuku'].contains(mode)) {
      throw ArgumentError.value(mode);
    }
    final result = await _channel.invokeMapMethod<Object?, Object?>('scan', {
      'mode': mode,
    });
    if (result == null) {
      throw const FormatException('Empty cache scan response');
    }
    return WatchfaceCacheScan.fromPlatform(result);
  }

  Future<WatchfaceCacheBundle> copyBundle(String token) async {
    if (!isSupported) throw UnsupportedError('Android cache import only');
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'copyBundle',
      {'token': token},
    );
    if (result == null) {
      throw const FormatException('Empty cache bundle response');
    }
    final path = result['path'];
    if (_isPrivatePath(path)) _pendingCleanup.add(path as String);
    try {
      return WatchfaceCacheBundle.fromPlatform(result);
    } catch (_) {
      if (_isPrivatePath(path)) {
        try {
          await cleanupBundle(path as String);
        } catch (_) {
          // Keep the exact native-owned BIN path for a later cleanup retry.
        }
      }
      rethrow;
    }
  }

  Future<void> cleanupBundle(String path) async {
    if (!isSupported) return;
    await _channel.invokeMethod<void>('deleteCopy', {'path': path});
    _pendingCleanup.remove(path);
  }

  /// Android converts an external open-intent URI into this private cache copy.
  /// SAF-picked files and desktop paths are never passed here.
  Future<void> releaseOpenedFile(String path) async {
    if (!isSupported) return;
    await const MethodChannel('oronbox/file_open')
        .invokeMethod<void>('deleteOpenedFile', {'path': path});
  }

  /// Retry failed cleanups without ever broadening the native ownership scope.
  Future<void> dispose() async {
    Object? firstError;
    StackTrace? firstStack;
    for (final path in _pendingCleanup.toList()) {
      try {
        await cleanupBundle(path);
      } catch (error, stack) {
        firstError ??= error;
        firstStack ??= stack;
      }
    }
    if (firstError != null) Error.throwWithStackTrace(firstError, firstStack!);
  }

  /// Kept for callers on the older installation flow.
  Future<String> copyToPrivateCache(String token) async {
    if (!isSupported) throw UnsupportedError('Android cache import only');
    final path = await _channel.invokeMethod<String>('copy', {'token': token});
    if (path == null || path.isEmpty) {
      throw const FormatException('Cache import did not return a file');
    }
    return path;
  }

  Future<void> deletePrivateCopy(String path) => cleanupBundle(path);
}

bool _isPrivatePath(Object? value) =>
    value is String &&
    value.startsWith('/') &&
    !value.contains('\u0000') &&
    !value.split('/').any((part) => part == '.' || part == '..');
