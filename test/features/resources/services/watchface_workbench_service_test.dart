import 'dart:async';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:cross_file/cross_file.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:oronbox/src/device/core/watchface_install_policy.dart';
import 'package:oronbox/src/features/resources/services/watchface_cache_import_service.dart';
import 'package:oronbox/src/features/resources/services/watchface_document.dart';
import 'package:oronbox/src/features/resources/services/watchface_workbench_service.dart';

void main() {
  group('bounded local import', () {
    test(
      'selection cancellation returns null without opening a file',
      () async {
        final service = WatchfaceWorkbenchService(pickFile: () async => null);
        expect(await service.pickLocal(), isNull);
        await service.dispose();
      },
    );

    test(
      'streams an owned snapshot and never reads or changes the source',
      () async {
        final bytes = _bin();
        final original = Uint8List.fromList(bytes);
        final file = _StreamFile('local.bin', [
          bytes,
        ], declaredSize: bytes.length);
        final service = WatchfaceWorkbenchService(pickFile: () async => file);
        final document = (await service.pickLocal())!;
        expect(document.bytes, original);
        expect(bytes, original);
        expect(file.readAsBytesCalled, isFalse);
        bytes[0] = 0;
        expect(document.bytes.first, 0x5a);
        await service.dispose();
      },
    );

    test(
      'imports a real in-memory XFile without reading past its end',
      () async {
        final service = WatchfaceWorkbenchService();
        final document = await service.importFile(
          XFile.fromData(_bin(), name: 'memory.bin'),
        );
        expect(document.bytes, _bin());
        expect(document.id, '123456789012');
        await service.dispose();
      },
    );

    test('rejects oversized metadata without opening the stream', () async {
      final file = _StreamFile('large.bin', [
        _bin(),
      ], declaredSize: maxWatchfaceImportBytes + 1);
      final service = WatchfaceWorkbenchService(pickFile: () async => file);
      await expectLater(service.pickLocal(), throwsFormatException);
      expect(file.opened, isFalse);
      await service.dispose();
    });

    test(
      'enforces the stream bound even when declared size is wrong',
      () async {
        final chunk = Uint8List(1024 * 1024);
        final file = _StreamFile('lying.bin', [
          for (var i = 0; i < 34; i++) chunk,
        ], declaredSize: 0);
        final service = WatchfaceWorkbenchService(pickFile: () async => file);
        await expectLater(service.pickLocal(), throwsFormatException);
        expect(file.chunksRead, 33);
        expect(file.streamClosed, isTrue);
        expect(file.readAsBytesCalled, isFalse);
        await service.dispose();
      },
    );

    test('accepts the exact maximum without truncating bytes', () async {
      final first = Uint8List(1024 * 1024)..setRange(0, 0xa8, _bin());
      final chunk = Uint8List(1024 * 1024);
      final file = _StreamFile('maximum.bin', [
        first,
        for (var i = 1; i < 32; i++) chunk,
      ], declaredSize: maxWatchfaceImportBytes);
      final service = WatchfaceWorkbenchService(pickFile: () async => file);
      final document = (await service.pickLocal())!;
      expect(document.bytes.length, maxWatchfaceImportBytes);
      expect(document.id, '123456789012');
      expect(file.chunksRead, 32);
      await service.dispose();
    });

    test(
      'rejects unknown BIN bytes rather than trusting the extension',
      () async {
        final file = _StreamFile('fake.bin', [Uint8List(0xa8)]);
        final service = WatchfaceWorkbenchService(pickFile: () async => file);
        await expectLater(service.pickLocal(), throwsFormatException);
        await service.dispose();
      },
    );
  });

  group('cache import and cleanup', () {
    test(
      'preserves scan diagnostics and uses opaque selection tokens',
      () async {
        final cache = _Cache();
        final service = WatchfaceWorkbenchService(cacheService: cache);
        final scan = await service.scanCache();
        expect(identical(scan, cache.scanResult), isTrue);
        expect(scan.diagnostics.single.code, 'DENIED');
        await service.dispose();
      },
    );

    test(
      'owns BIN and previews before deleting the exact bundle path',
      () async {
        final cache = _Cache();
        final files = {
          '/private/session/face.bin': _StreamFile('face.bin', [_bin()]),
          '/private/session/preview.png': _StreamFile('preview.png', [_png()]),
        };
        cache.onCleanup = () {
          expect(files.values.every((file) => file.streamClosed), isTrue);
          files.clear();
        };
        final service = WatchfaceWorkbenchService(
          cacheService: cache,
          openFile: (path) => files[path]!,
        );
        final document = await service.importCache(_entry());
        expect(cache.token, 'opaque-token');
        expect(cache.cleaned, ['/private/session/face.bin']);
        expect(document.name, 'XML face');
        expect(document.id, '123456789012');
        expect(document.warnings, ['XML_WARNING']);
        expect(document.previews.single.mimeType, 'image/png');
        expect(document.bytes, _bin());
        expect(document.previews.single.bytes, _png());
        await service.dispose();
        expect(cache.cleaned, hasLength(1));
      },
    );

    test(
      'does not trust a conflicting native ID over copied BIN bytes',
      () async {
        final cache = _Cache(
          bundle: _bundle(embeddedId: '987654321012', nameSource: 'filename'),
        );
        final service = WatchfaceWorkbenchService(
          cacheService: cache,
          openFile: (path) => _StreamFile('face.bin', [_bin()]),
        );
        final document = await service.importCache(_entry());
        expect(document.id, '123456789012');
        expect(document.name, 'selected');
        await service.dispose();
      },
    );

    test(
      'retains a restricted BIN ID despite allowed native metadata',
      () async {
        final bytes = _bin()..setRange(0x28, 0x34, '120912345678'.codeUnits);
        final cache = _Cache(bundle: _bundle(previews: const []));
        final service = WatchfaceWorkbenchService(
          cacheService: cache,
          openFile: (path) => _StreamFile('face.bin', [bytes]),
        );
        addTearDown(service.dispose);

        final document = await service.importCache(_entry());
        final edited = document.edit(name: 'Renamed', id: '987654321012');

        expect(document.id, '120912345678');
        expect(document.originalId, '120912345678');
        expect(edited.originalId, '120912345678');
        expect(extractVelaWatchfaceId(edited.bytes), '987654321012');
        expect(
          edited.ensureInstallAllowed,
          throwsA(isA<WatchfaceInstallBlockedException>()),
        );
        expect(cache.cleaned, ['/private/session/face.bin']);
      },
    );

    test('cleans up when BIN streaming or recognition fails', () async {
      for (final readFails in [true, false]) {
        final cache = _Cache(bundle: _bundle(previews: const []));
        final service = WatchfaceWorkbenchService(
          cacheService: cache,
          openFile: (path) => _StreamFile('face.bin', [
            Uint8List(0xa8),
          ], streamError: readFails ? StateError('read failed') : null),
        );
        await expectLater(
          service.importCache(_entry()),
          readFails ? throwsStateError : throwsFormatException,
        );
        expect(cache.cleaned, ['/private/session/face.bin']);
        await service.dispose();
      }
    });

    test('does not clean arbitrary paths when native copy fails', () async {
      final cache = _Cache()..copyError = StateError('native copy failed');
      final service = WatchfaceWorkbenchService(cacheService: cache);
      await expectLater(service.importCache(_entry()), throwsStateError);
      expect(cache.cleaned, isEmpty);
      await service.dispose();
    });

    test('bounds previews and skips missing or unsupported images', () async {
      final cache = _Cache(
        bundle: _bundle(
          previews: const [
            WatchfaceCachePreview(path: '/private/large', name: 'large.png'),
            WatchfaceCachePreview(
              path: '/private/missing',
              name: 'missing.png',
            ),
            WatchfaceCachePreview(path: '/private/fake', name: 'fake.png'),
            WatchfaceCachePreview(path: '/private/good', name: 'good.png'),
          ],
        ),
      );
      final tooLarge = _StreamFile('large.png', [
        _png(),
      ], declaredSize: 8 * 1024 * 1024 + 1);
      final service = WatchfaceWorkbenchService(
        cacheService: cache,
        openFile: (path) => switch (path) {
          '/private/session/face.bin' => _StreamFile('face.bin', [_bin()]),
          '/private/large' => tooLarge,
          '/private/missing' => throw StateError('missing'),
          '/private/fake' => _StreamFile('fake.png', [Uint8List(12)]),
          _ => _StreamFile('good.png', [_png()]),
        },
      );
      final document = await service.importCache(_entry());
      expect(document.previews, hasLength(1));
      expect(document.previews.single.name, 'good.png');
      expect(document.warnings, contains('PREVIEW_INVALID_IMAGE'));
      expect(document.warnings, contains('PREVIEW_READ_FAILED'));
      expect(tooLarge.opened, isFalse);
      expect(cache.cleaned, ['/private/session/face.bin']);
      await service.dispose();
    });

    test('enforces the aggregate preview byte budget', () async {
      final cache = _Cache(
        bundle: _bundle(
          previews: [
            for (var i = 0; i < 3; i++)
              WatchfaceCachePreview(path: '/private/$i', name: '$i.png'),
          ],
        ),
      );
      final image = Uint8List(8 * 1024 * 1024)
        ..setRange(0, _png().length, _png());
      final opened = <String>[];
      final service = WatchfaceWorkbenchService(
        cacheService: cache,
        openFile: (path) {
          opened.add(path);
          return _StreamFile('file', [path.endsWith('.bin') ? _bin() : image]);
        },
      );
      final document = await service.importCache(_entry());
      expect(document.previews, hasLength(2));
      expect(document.warnings, contains('PREVIEW_BUNDLE_LIMIT'));
      expect(opened, isNot(contains('/private/2')));
      await service.dispose();
    });

    test(
      'retains the document and retries failed cleanup on dispose',
      () async {
        final cache = _Cache(bundle: _bundle(previews: const []))
          ..cleanupFailures = 1;
        final service = WatchfaceWorkbenchService(
          cacheService: cache,
          openFile: (path) => _StreamFile('face.bin', [_bin()]),
        );
        final document = await service.importCache(_entry());
        expect(document.bytes, _bin());
        expect(document.warnings, contains('PRIVATE_CACHE_CLEANUP_FAILED'));
        await service.dispose();
        expect(cache.cleaned, [
          '/private/session/face.bin',
          '/private/session/face.bin',
        ]);
        expect(cache.disposed, isTrue);
      },
    );

    test(
      'cleanup failure does not replace the original import failure',
      () async {
        final original = StateError('original read error');
        final cache = _Cache()..cleanupFailures = 1;
        final service = WatchfaceWorkbenchService(
          cacheService: cache,
          openFile: (path) => throw original,
        );
        await expectLater(
          service.importCache(_entry()),
          throwsA(same(original)),
        );
        await service.dispose();
        expect(cache.cleaned, hasLength(2));
      },
    );

    test(
      'dispose waits for in-flight native copy and then cleans it',
      () async {
        final copied = Completer<WatchfaceCacheBundle>();
        final cache = _Cache()..pendingCopy = copied.future;
        final service = WatchfaceWorkbenchService(cacheService: cache);
        final importing = service.importCache(_entry());
        final importFailure = expectLater(importing, throwsStateError);
        final disposing = service.dispose();
        copied.complete(_bundle());
        await importFailure;
        await disposing;
        expect(cache.cleaned, ['/private/session/face.bin']);
        await expectLater(service.pickLocal(), throwsStateError);
        expect(() => service.scanCache(), throwsStateError);
      },
    );
  });

  group('export', () {
    test(
      'saves current-name BIN, ZIP and real preview with Uri results',
      () async {
        final names = <String>[];
        final data = <Uint8List>[];
        final mimeTypes = <String>[];
        final service = WatchfaceWorkbenchService(
          saveFile:
              ({required fileName, required mimeType, required bytes}) async {
                names.add(fileName);
                mimeTypes.add(mimeType);
                data.add(bytes);
                return Uri.parse('content://documents/export/${names.length}');
              },
        );
        final document = WatchfaceDocument.importBin(
          bytes: _bin(),
          fileName: 'face.bin',
          previews: [WatchfacePreview(name: 'original.jpg', bytes: _png())],
        ).edit(name: 'edited', id: '987654321012');
        expect(await service.exportBin(document), WatchfaceExportResult.saved);
        expect(
          await service.exportBundle(document),
          WatchfaceExportResult.saved,
        );
        expect(
          await service.exportPreview(document, 0),
          WatchfaceExportResult.saved,
        );
        expect(names, ['edited.bin', 'edited.zip', 'edited-preview-1.png']);
        expect(mimeTypes, [
          'application/octet-stream',
          'application/zip',
          'image/png',
        ]);
        expect(data[0], document.bytes);
        expect(ZipDecoder().decodeBytes(data[1]).files, hasLength(2));
        expect(data[2], document.previews.single.bytes);
        await service.dispose();
      },
    );

    test('native cancellation retains a reusable unchanged document', () async {
      final document = WatchfaceDocument.importBin(
        bytes: _bin(),
        fileName: 'face.bin',
      );
      final snapshot = Uint8List.fromList(document.bytes);
      var saves = 0;
      final service = WatchfaceWorkbenchService(
        isWeb: false,
        saveFile:
            ({required fileName, required mimeType, required bytes}) async {
              saves++;
              return saves == 1 ? null : Uri.file('/output/face.bin');
            },
      );
      expect(
        await service.exportBin(document),
        WatchfaceExportResult.cancelled,
      );
      expect(document.bytes, snapshot);
      expect(await service.exportBin(document), WatchfaceExportResult.saved);
      await service.dispose();
    });

    test('Web null means a download was initiated', () async {
      final service = WatchfaceWorkbenchService(
        isWeb: true,
        saveFile: ({
          required fileName,
          required mimeType,
          required bytes,
        }) async => null,
      );
      final document = WatchfaceDocument.importBin(
        bytes: _bin(),
        fileName: 'face.bin',
      );
      expect(
        await service.exportBin(document),
        WatchfaceExportResult.downloadStarted,
      );
      await service.dispose();
    });

    test('save failure keeps document and propagates the error', () async {
      final service = WatchfaceWorkbenchService(
        saveFile: ({
          required fileName,
          required mimeType,
          required bytes,
        }) async => throw StateError('save failed'),
      );
      final document = WatchfaceDocument.importBin(
        bytes: _bin(),
        fileName: 'face.bin',
      );
      await expectLater(service.exportBin(document), throwsStateError);
      expect(document.bytes, _bin());
      await service.dispose();
    });
  });
}

Uint8List _bin() {
  final bytes = Uint8List(0xa8)..setRange(0, 4, [0x5a, 0xa5, 0x34, 0x12]);
  bytes.setRange(0x28, 0x34, '123456789012'.codeUnits);
  return bytes;
}

Uint8List _png() =>
    Uint8List.fromList([0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10, 1]);

WatchfaceCacheFile _entry() => WatchfaceCacheFile(
  token: 'opaque-token',
  name: 'selected.bin',
  location: 'download/WatchFace',
  size: 0xa8,
  modifiedAt: DateTime.fromMillisecondsSinceEpoch(1700000000000),
);

WatchfaceCacheBundle _bundle({
  String embeddedId = '123456789012',
  String nameSource = 'xml',
  List<WatchfaceCachePreview> previews = const [
    WatchfaceCachePreview(
      path: '/private/session/preview.png',
      name: 'preview.png',
    ),
  ],
}) => WatchfaceCacheBundle(
  path: '/private/session/face.bin',
  name: 'XML face',
  embeddedId: embeddedId,
  previews: previews,
  warnings: const ['XML_WARNING'],
  nameSource: nameSource,
);

class _Cache extends WatchfaceCacheImportService {
  _Cache({WatchfaceCacheBundle? bundle}) : bundle = bundle ?? _bundle();

  final WatchfaceCacheBundle bundle;
  final cleaned = <String>[];
  final pendingCleanup = <String>{};
  final scanResult = WatchfaceCacheScan(
    files: [_entry()],
    truncated: false,
    diagnostics: const [
      WatchfaceCacheDiagnostic(code: 'DENIED', path: '/root', reason: 'read'),
    ],
  );
  String? token;
  Object? copyError;
  Future<WatchfaceCacheBundle>? pendingCopy;
  int cleanupFailures = 0;
  bool disposed = false;
  void Function()? onCleanup;

  @override
  Future<WatchfaceCacheScan> scan({String mode = 'root'}) async => scanResult;

  @override
  Future<WatchfaceCacheBundle> copyBundle(String token) async {
    this.token = token;
    if (copyError != null) throw copyError!;
    final copied = await (pendingCopy ?? Future.value(bundle));
    pendingCleanup.add(copied.path);
    return copied;
  }

  @override
  Future<void> cleanupBundle(String path) async {
    cleaned.add(path);
    if (cleanupFailures > 0) {
      cleanupFailures--;
      throw StateError('cleanup failed');
    }
    onCleanup?.call();
    pendingCleanup.remove(path);
  }

  @override
  Future<void> dispose() async {
    disposed = true;
    for (final path in pendingCleanup.toList()) {
      await cleanupBundle(path);
    }
  }
}

class _StreamFile extends XFile {
  _StreamFile(String name, this.chunks, {this.declaredSize, this.streamError})
    : super('/virtual/$name', name: name);

  final List<Uint8List> chunks;
  final int? declaredSize;
  final Object? streamError;
  bool opened = false;
  bool streamClosed = false;
  bool readAsBytesCalled = false;
  int chunksRead = 0;

  @override
  Future<int> length() async =>
      declaredSize ?? chunks.fold<int>(0, (sum, chunk) => sum + chunk.length);

  @override
  Future<Uint8List> readAsBytes() async {
    readAsBytesCalled = true;
    throw StateError('unbounded read');
  }

  @override
  Stream<Uint8List> openRead([int? start, int? end]) async* {
    opened = true;
    try {
      if (streamError != null) throw streamError!;
      for (final chunk in chunks) {
        chunksRead++;
        yield chunk;
      }
    } finally {
      streamClosed = true;
    }
  }
}
