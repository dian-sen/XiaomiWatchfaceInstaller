import 'dart:typed_data';

import 'package:cross_file/cross_file.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart' show kIsWeb;

import 'watchface_bundle_import.dart';
import 'watchface_cache_import_service.dart';
import 'watchface_document.dart';

const _maxPreviewBytes = 8 * 1024 * 1024;
const _maxPreviewTotalBytes = 16 * 1024 * 1024;

enum WatchfaceExportResult { saved, cancelled, downloadStarted }

typedef WatchfaceFileSaver = Future<Uri?> Function({
  required String fileName,
  required String mimeType,
  required Uint8List bytes,
});

/// Owns file I/O and temporary native bundles; documents own only memory.
class WatchfaceWorkbenchService {
  WatchfaceWorkbenchService({
    WatchfaceCacheImportService? cacheService,
    Future<XFile?> Function()? pickFile,
    XFile Function(String path)? openFile,
    WatchfaceFileSaver? saveFile,
    bool? isWeb,
  }) : _cacheService = cacheService ?? WatchfaceCacheImportService(),
       _pickFile = pickFile ?? _pickBin,
       _openFile = openFile ?? XFile.new,
       _saveFile = saveFile ?? _saveBytes,
       _isWeb = isWeb ?? kIsWeb;

  final WatchfaceCacheImportService _cacheService;
  final Future<XFile?> Function() _pickFile;
  final XFile Function(String path) _openFile;
  final WatchfaceFileSaver _saveFile;
  final bool _isWeb;
  final Set<Future<WatchfaceDocument>> _imports = {};
  bool _disposed = false;
  String cacheMode = 'root';

  Future<WatchfaceDocument?> pickLocal() async {
    _ensureActive();
    final file = await _pickFile();
    _ensureActive();
    if (file == null) return null;
    return importFile(file);
  }

  /// Uses the same bounds for files supplied by drag/drop or external callers.
  Future<WatchfaceDocument> importFile(XFile file) async {
    _ensureActive();
    final isBundle = file.name.toLowerCase().endsWith('.zip');
    final bytes = await _readBounded(
      file,
      isBundle ? maxWatchfaceBundleBytes : maxWatchfaceImportBytes,
    );
    _ensureActive();
    final hasZipSignature =
        bytes.length >= 4 &&
        bytes[0] == 0x50 &&
        bytes[1] == 0x4b &&
        bytes[2] == 0x03 &&
        bytes[3] == 0x04;
    return isBundle || hasZipSignature
        ? const WatchfaceBundleImport().importZip(bytes)
        : WatchfaceDocument.importBin(bytes: bytes, fileName: file.name);
  }

  /// Releases only the private copy created by Android's open-with intent path.
  Future<WatchfaceDocument> importOpenedFile(XFile file) async {
    try {
      return await importFile(file);
    } finally {
      final path = file.path;
      if (path.isNotEmpty) {
        await _cacheService.releaseOpenedFile(path);
      }
    }
  }

  Future<WatchfaceCacheScan> scanCache() {
    _ensureActive();
    return _cacheService.scan(mode: cacheMode);
  }

  Future<WatchfaceDocument> importCache(WatchfaceCacheFile file) {
    _ensureActive();
    final future = _importCache(file);
    _imports.add(future);
    return future.whenComplete(() => _imports.remove(future));
  }

  Future<WatchfaceDocument> _importCache(WatchfaceCacheFile file) async {
    final bundle = await _cacheService.copyBundle(file.token);
    final warnings = [...bundle.warnings];
    final previews = <WatchfacePreview>[];
    late Uint8List bytes;
    try {
      _ensureActive();
      bytes = await _readBounded(
        _openFile(bundle.path),
        maxWatchfaceImportBytes,
      );
      var previewBytes = 0;
      for (final preview in bundle.previews.take(8)) {
        final remaining = _maxPreviewTotalBytes - previewBytes;
        if (remaining <= 0) {
          warnings.add('PREVIEW_BUNDLE_LIMIT');
          break;
        }
        try {
          final data = await _readBounded(
            _openFile(preview.path),
            remaining < _maxPreviewBytes ? remaining : _maxPreviewBytes,
          );
          previewBytes += data.length;
          previews.add(WatchfacePreview(name: preview.name, bytes: data));
        } on FormatException {
          warnings.add('PREVIEW_INVALID_IMAGE');
        } catch (_) {
          warnings.add('PREVIEW_READ_FAILED');
        }
      }
      _ensureActive();
    } finally {
      try {
        await _cacheService.cleanupBundle(bundle.path);
      } catch (_) {
        warnings.add('PRIVATE_CACHE_CLEANUP_FAILED');
      }
    }
    _ensureActive();
    return WatchfaceDocument.importBin(
      bytes: bytes,
      fileName: file.name,
      suggestedName: bundle.nameSource == 'filename' ? null : bundle.name,
      previews: previews,
      warnings: warnings,
    );
  }

  Future<WatchfaceExportResult> exportBin(WatchfaceDocument document) =>
      _export(document.binFileName, 'application/octet-stream', document.bytes);

  Future<WatchfaceExportResult> exportBundle(WatchfaceDocument document) =>
      _export(
        '${document.name}.zip',
        'application/zip',
        document.exportArchive(),
      );

  Future<WatchfaceExportResult> exportPreview(
    WatchfaceDocument document,
    int index,
  ) => _export(
    document.previewFileName(index),
    document.previews[index].mimeType,
    document.previews[index].bytes,
  );

  Future<WatchfaceExportResult> _export(
    String name,
    String mimeType,
    Uint8List bytes,
  ) async {
    _ensureActive();
    final uri = await _saveFile(
      fileName: name,
      mimeType: mimeType,
      bytes: bytes,
    );
    if (uri != null) return WatchfaceExportResult.saved;
    return _isWeb
        ? WatchfaceExportResult.downloadStarted
        : WatchfaceExportResult.cancelled;
  }

  Future<void> dispose() async {
    _disposed = true;
    // An import already awaiting native copy still has to clean up its bundle.
    for (final future in _imports.toList()) {
      try {
        await future;
      } catch (_) {
        // The caller retains the original import error.
      }
    }
    await _cacheService.dispose();
  }

  void _ensureActive() {
    if (_disposed) throw StateError('Watchface workbench is disposed');
  }
}

Future<Uint8List> _readBounded(XFile file, int maximum) async {
  final knownSize = await file.length();
  if (knownSize > maximum) {
    throw const FormatException('Watchface file exceeds the import limit');
  }
  final buffer = BytesBuilder(copy: false);
  // Web materializes one Blob slice. Native streams are chunked, and XFile's
  // in-memory native implementation rejects an end beyond its byte length.
  await for (final chunk in file.openRead(0, kIsWeb ? maximum + 1 : null)) {
    if (buffer.length + chunk.length > maximum) {
      throw const FormatException('Watchface file exceeds the import limit');
    }
    buffer.add(chunk);
  }
  return buffer.takeBytes();
}

Future<XFile?> _pickBin() async {
  final file = await FilePicker.pickFile(
    type: FileType.custom,
    allowedExtensions: const ['bin', 'zip'],
  );
  return file?.xFile;
}

Future<Uri?> _saveBytes({
  required String fileName,
  required String mimeType,
  required Uint8List bytes,
}) => FilePicker.saveFile(fileName: fileName, mimeType: mimeType, bytes: bytes);
