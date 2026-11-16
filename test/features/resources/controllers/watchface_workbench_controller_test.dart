import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:cross_file/cross_file.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:oronbox/src/device/core/watchface_install_policy.dart';
import 'package:oronbox/src/features/resources/controllers/watchface_workbench_controller.dart';
import 'package:oronbox/src/features/resources/services/watchface_cache_import_service.dart';
import 'package:oronbox/src/features/resources/services/watchface_document.dart';
import 'package:oronbox/src/features/resources/services/watchface_workbench_service.dart';

class _Service extends WatchfaceWorkbenchService {
  WatchfaceDocument? picked;
  XFile? importedFile;
  WatchfaceDocument? exported;
  WatchfaceDocument? bundled;
  int? previewIndex;
  int pickCount = 0;
  int exportCount = 0;
  int disposeCount = 0;
  Future<WatchfaceDocument?>? pendingPick;
  Future<WatchfaceExportResult>? pendingExport;
  WatchfaceExportResult result = WatchfaceExportResult.saved;
  Object? exportError;

  @override
  Future<WatchfaceDocument?> pickLocal() async {
    pickCount++;
    return pendingPick == null ? picked : await pendingPick!;
  }

  @override
  Future<WatchfaceDocument> importFile(XFile file) async {
    importedFile = file;
    return picked!;
  }

  @override
  Future<WatchfaceCacheScan> scanCache() async =>
      const WatchfaceCacheScan(files: [], truncated: false);

  @override
  Future<WatchfaceExportResult> exportBin(WatchfaceDocument doc) async {
    exportCount++;
    exported = doc;
    if (exportError != null) throw exportError!;
    return pendingExport == null ? result : await pendingExport!;
  }

  @override
  Future<WatchfaceExportResult> exportBundle(WatchfaceDocument doc) async {
    bundled = doc;
    return result;
  }

  @override
  Future<WatchfaceExportResult> exportPreview(
    WatchfaceDocument doc,
    int index,
  ) async {
    exported = doc;
    previewIndex = index;
    return result;
  }

  @override
  Future<void> dispose() async => disposeCount++;
}

WatchfaceDocument _document({
  String name = 'Original',
  String id = '998877665544',
}) {
  final bytes = Uint8List(0xc0);
  bytes.setAll(0, [0x5a, 0xa5, 0x34, 0x12]);
  bytes.setAll(0x28, ascii.encode(id));
  return WatchfaceDocument.importBin(bytes: bytes, fileName: '$name.bin');
}

void main() {
  test(
    'imports an externally opened file through the bounded service',
    () async {
      final service = _Service()..picked = _document();
      final controller = WatchfaceWorkbenchController(service);
      addTearDown(controller.dispose);
      final file = XFile.fromData(service.picked!.bytes, name: 'opened.bin');

      expect(service.pickCount, 0);
      await controller.importFile(file);

      expect(service.importedFile, same(file));
      expect(controller.document, same(service.picked));
      expect(controller.documentRevision, 1);
      expect(service.pickCount, 0);
    },
  );

  test(
    'commits the exact edited snapshot only after BIN save succeeds',
    () async {
      final original = _document();
      final service = _Service()..picked = original;
      final controller = WatchfaceWorkbenchController(service);
      addTearDown(controller.dispose);
      await controller.pickLocal();
      final edited = original.edit(name: 'Renamed', id: '987654321012');
      final saving = Completer<WatchfaceExportResult>();
      service.pendingExport = saving.future;

      final operation = controller.exportDocument(
        edited,
        kind: WatchfaceExportKind.bin,
      );
      expect(controller.busy, isTrue);
      expect(controller.document, same(original));
      expect(service.exported, same(edited));
      expect(original.name, 'Original');
      expect(original.id, '998877665544');

      saving.complete(WatchfaceExportResult.saved);
      await operation;

      expect(controller.document, same(edited));
      expect(controller.document!.binFileName, 'Renamed.bin');
      expect(controller.exportResult, WatchfaceExportResult.saved);
      expect(controller.busy, isFalse);
    },
  );

  for (final result in [
    WatchfaceExportResult.cancelled,
    WatchfaceExportResult.downloadStarted,
  ]) {
    test('$result keeps the original document and selection', () async {
      final original = _document();
      final service = _Service()
        ..picked = original
        ..result = result;
      final controller = WatchfaceWorkbenchController(service);
      addTearDown(controller.dispose);
      await controller.pickLocal();
      final edited = original.edit(name: 'Uncommitted', id: original.id!);

      await controller.exportDocument(edited, kind: WatchfaceExportKind.bin);

      expect(controller.document, same(original));
      expect(controller.exportResult, result);
      expect(controller.error, isNull);
    });
  }

  test('failed save keeps the original document and permits retry', () async {
    final original = _document();
    final service = _Service()
      ..picked = original
      ..exportError = StateError('save failed');
    final controller = WatchfaceWorkbenchController(service);
    addTearDown(controller.dispose);
    await controller.pickLocal();
    final edited = original.edit(name: 'Retry', id: original.id!);

    await controller.exportDocument(edited, kind: WatchfaceExportKind.bin);

    expect(controller.document, same(original));
    expect(controller.error, isA<StateError>());
    expect(controller.busy, isFalse);
    service.exportError = null;
    await controller.exportDocument(edited, kind: WatchfaceExportKind.bin);
    expect(controller.document, same(edited));
    expect(controller.error, isNull);
  });

  test(
    'bundle save commits its snapshot; image save leaves BIN edits alone',
    () async {
      final original = _document();
      final service = _Service()..picked = original;
      final controller = WatchfaceWorkbenchController(service);
      addTearDown(controller.dispose);
      await controller.pickLocal();
      final edited = original.edit(name: 'Bundle', id: original.id!);

      await controller.exportDocument(
        edited,
        kind: WatchfaceExportKind.preview,
        previewIndex: 2,
      );
      expect(service.previewIndex, 2);
      expect(controller.document, same(original));

      await controller.exportDocument(edited, kind: WatchfaceExportKind.bundle);
      expect(service.bundled, same(edited));
      expect(controller.document, same(edited));
    },
  );

  test('busy blocks duplicate export, import and install operations', () async {
    final original = _document();
    final service = _Service()..picked = original;
    final controller = WatchfaceWorkbenchController(service);
    addTearDown(controller.dispose);
    await controller.pickLocal();
    final saving = Completer<WatchfaceExportResult>();
    service.pendingExport = saving.future;
    final operation = controller.exportDocument(
      original,
      kind: WatchfaceExportKind.bin,
    );
    var installs = 0;

    await controller.exportDocument(original, kind: WatchfaceExportKind.bin);
    await controller.pickLocal();
    await controller.install(original, (_) async {
      installs++;
      return true;
    });

    expect(service.exportCount, 1);
    expect(service.pickCount, 1);
    expect(installs, 0);
    saving.complete(WatchfaceExportResult.cancelled);
    await operation;
  });

  test(
    'cancelled import keeps the current document and form revision',
    () async {
      final service = _Service()..picked = _document();
      final controller = WatchfaceWorkbenchController(service);
      addTearDown(controller.dispose);
      await controller.pickLocal();
      final original = controller.document;
      final revision = controller.documentRevision;
      service.picked = null;

      await controller.pickLocal();

      expect(controller.document, same(original));
      expect(controller.documentRevision, revision);
    },
  );

  test(
    'install queues the edited snapshot without committing a save',
    () async {
      final service = _Service()..picked = _document();
      final controller = WatchfaceWorkbenchController(service);
      addTearDown(controller.dispose);
      await controller.pickLocal();
      final original = controller.document!;
      final edited = original.edit(name: 'Install', id: '987654321');
      WatchfaceDocument? queued;

      await controller.install(edited, (doc) async {
        queued = doc;
        return true;
      });

      expect(queued, same(edited));
      expect(controller.document, same(original));
      expect(controller.enqueued, isTrue);
    },
  );

  for (final kind in [WatchfaceExportKind.bin, WatchfaceExportKind.bundle]) {
    test('saving $kind cannot clear a restricted original ID', () async {
      final original = _document(id: '120912345678');
      final service = _Service()..picked = original;
      final controller = WatchfaceWorkbenchController(service);
      addTearDown(controller.dispose);
      await controller.pickLocal();
      final edited = original.edit(name: 'Saved', id: '987654321012');

      await controller.exportDocument(edited, kind: kind);
      expect(controller.exportResult, WatchfaceExportResult.saved);
      expect(controller.document, same(edited));
      expect(controller.document!.originalId, '120912345678');
      var installs = 0;
      await controller.install(
        controller.document!.edit(name: 'Install', id: '987654321'),
        (_) async {
          installs++;
          return true;
        },
      );

      expect(installs, 0);
      expect(controller.error, isA<WatchfaceInstallBlockedException>());
      expect(controller.failedAction, WatchfaceWorkbenchAction.installing);
      expect(controller.enqueued, isFalse);
      expect(controller.busy, isFalse);
      expect(controller.document, same(edited));
    });
  }

  test('rejects a newly restricted payload before invoking enqueue', () async {
    final original = _document();
    final service = _Service()..picked = original;
    final controller = WatchfaceWorkbenchController(service);
    addTearDown(controller.dispose);
    await controller.pickLocal();
    var installs = 0;
    Future<bool> enqueue(WatchfaceDocument _) async {
      installs++;
      return true;
    }

    await controller.install(
      original.edit(name: 'Restricted', id: '120912345678'),
      enqueue,
    );

    expect(installs, 0);
    expect(controller.error, isA<WatchfaceInstallBlockedException>());
    expect(controller.failedAction, WatchfaceWorkbenchAction.installing);
    expect(controller.enqueued, isFalse);
    expect(controller.busy, isFalse);
    expect(controller.document, same(original));

    await controller.install(original, enqueue);
    expect(installs, 1);
    expect(controller.error, isNull);
    expect(controller.failedAction, isNull);
    expect(controller.enqueued, isTrue);
  });

  test(
    'dispose releases the service and ignores late import completion',
    () async {
      final imported = Completer<WatchfaceDocument?>();
      final service = _Service()..pendingPick = imported.future;
      final controller = WatchfaceWorkbenchController(service);
      var notifications = 0;
      controller.addListener(() => notifications++);
      final importing = controller.pickLocal();
      expect(notifications, 1);

      controller.dispose();
      imported.complete(_document());
      await importing;
      await controller.pickLocal();

      expect(service.disposeCount, 1);
      expect(service.pickCount, 1);
      expect(controller.document, isNull);
      expect(notifications, 1);
    },
  );
}
