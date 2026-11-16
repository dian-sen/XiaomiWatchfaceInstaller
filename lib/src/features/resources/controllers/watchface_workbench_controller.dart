import 'dart:async';

import 'package:cross_file/cross_file.dart';
import 'package:oronbox/src/features/resources/services/watchface_bin_import.dart';
import 'package:flutter/foundation.dart';
import 'package:oronbox/src/features/resources/services/watchface_cache_import_service.dart';
import 'package:oronbox/src/features/resources/services/watchface_document.dart';
import 'package:oronbox/src/features/resources/services/watchface_workbench_service.dart';

enum WatchfaceWorkbenchAction { importing, scanning, exporting, installing }

enum WatchfaceExportKind { bin, bundle, preview }

/// Owns one workbench session, including pending work and its temporary files.
class WatchfaceWorkbenchController extends ChangeNotifier {
  WatchfaceWorkbenchController(this.service);

  final WatchfaceWorkbenchService service;
  WatchfaceDocument? document;
  WatchfaceCacheScan? scan;
  String? selectedCacheToken;
  WatchfaceWorkbenchAction? action;
  WatchfaceWorkbenchAction? failedAction;
  WatchfaceExportResult? exportResult;
  Object? error;
  bool enqueued = false;
  int documentRevision = 0;
  bool _disposed = false;

  bool get busy => action != null;
  String cacheMode = 'root';
  bool cacheAuthorized = false;

  void setCacheMode(String mode) {
    if (_disposed || busy || !const ['root', 'shizuku', 'adb'].contains(mode)) {
      return;
    }
    cacheMode = mode;
    cacheAuthorized = false;
    error = null;
    scan = null;
    selectedCacheToken = null;
    service.cacheMode = mode;
    notifyListeners();
  }

  Future<void> authorizeCache() =>
      _perform(WatchfaceWorkbenchAction.scanning, () async {
        cacheAuthorized = false;
        await WatchfaceCacheImportService().authorize(cacheMode);
        if (!_disposed) cacheAuthorized = true;
      });

  Future<void> pickLocal() => _import(service.pickLocal);

  Future<void> importFile(XFile file) =>
      _import(() => service.importFile(file));

  Future<void> importOpenedFile(XFile file) =>
      _import(() => service.importOpenedFile(file));

  Future<void> selectCache(WatchfaceCacheFile file) =>
      _import(() => service.importCache(file), cacheToken: file.token);

  Future<void> _import(
    Future<WatchfaceDocument?> Function() load, {
    String? cacheToken,
  }) => _perform(WatchfaceWorkbenchAction.importing, () async {
    final imported = await load();
    if (_disposed || imported == null) return;
    document = imported;
    selectedCacheToken = cacheToken;
    documentRevision++;
  });

  Future<void> scanCache() =>
      _perform(WatchfaceWorkbenchAction.scanning, () async {
        // A new scan invalidates the previous candidate tokens.
        scan = null;
        selectedCacheToken = null;
        final result = await service.scanCache();
        if (!_disposed) scan = result;
      });

  Future<void> exportDocument(
    WatchfaceDocument edited, {
    required WatchfaceExportKind kind,
    int previewIndex = 0,
  }) => _perform(WatchfaceWorkbenchAction.exporting, () async {
    final result = await switch (kind) {
      WatchfaceExportKind.bin => service.exportBin(edited),
      WatchfaceExportKind.bundle => service.exportBundle(edited),
      WatchfaceExportKind.preview => service.exportPreview(
        edited,
        previewIndex,
      ),
    };
    if (_disposed) return;
    exportResult = result;
    // A browser download is not confirmation that the user saved a file.
    if (kind != WatchfaceExportKind.preview &&
        result == WatchfaceExportResult.saved) {
      document = edited;
    }
  });

  Future<void> install(
    WatchfaceDocument edited,
    Future<bool> Function(WatchfaceDocument document) enqueue,
  ) => _perform(WatchfaceWorkbenchAction.installing, () async {
    final prepared = await service.replaceDocument(edited);
    if (_disposed) return;
    document = prepared;
    documentRevision++;
    prepared.ensureInstallAllowed();
    final result = await enqueue(prepared);
    if (!_disposed) enqueued = result;
  });

  Future<void> _perform(
    WatchfaceWorkbenchAction next,
    Future<void> Function() run,
  ) async {
    if (_disposed || busy) return;
    action = next;
    error = null;
    failedAction = null;
    exportResult = null;
    enqueued = false;
    notifyListeners();
    try {
      await run();
    } on WatchfaceImportCancelled {
      // Keep the previous document after the user cancels the ID prompt.
    } catch (failure) {
      if (!_disposed) {
        error = failure;
        failedAction = next;
      }
    } finally {
      if (!_disposed) {
        action = null;
        notifyListeners();
      }
    }
  }

  @override
  void dispose() {
    if (_disposed) return;
    _disposed = true;
    // The service also cleans imports which finish after this session closes.
    unawaited(service.dispose().catchError((Object _) {}));
    super.dispose();
  }
}
