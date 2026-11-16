import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:oronbox/src/features/resources/services/watchface_cache_import_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('decodes bounded cache scan metadata and diagnostics', () {
    final scan = WatchfaceCacheScan.fromPlatform({
      'files': [_entry()],
      'truncated': true,
      'diagnostics': [
        {'code': 'DENIED', 'path': '/root/watchfaces', 'reason': 'read'},
      ],
    });
    expect(scan.files, hasLength(1));
    expect(scan.files.single.token, 'scan-token');
    expect(scan.files.single.name, 'resource.bin');
    expect(scan.files.single.location, 'download/WatchFace');
    expect(scan.files.single.size, 1024);
    expect(scan.files.single.modifiedAt.millisecondsSinceEpoch, 1700000000000);
    expect(scan.truncated, isTrue);
    expect(scan.diagnostics.single.code, 'DENIED');
    expect(scan.diagnostics.single.path, '/root/watchfaces');
    expect(scan.diagnostics.single.reason, 'read');
    expect(() => scan.files.clear(), throwsUnsupportedError);
    expect(() => scan.diagnostics.clear(), throwsUnsupportedError);
  });

  test('only exact resource.bin entries survive cache scan decoding', () {
    final scan = WatchfaceCacheScan.fromPlatform({
      'files': [
        _entry(),
        {..._entry(), 'name': '1.bin', 'token': 'other'},
        {..._entry(), 'name': 'RESOURCE.BIN', 'token': 'upper'},
        {..._entry(), 'name': 'myresource.bin', 'token': 'suffix'},
      ],
    });
    expect(scan.files.map((file) => file.name), ['resource.bin']);
  });

  test('keeps legacy scans without diagnostics compatible', () {
    final scan = WatchfaceCacheScan.fromPlatform({
      'files': [_entry()],
      'truncated': false,
    });
    expect(scan.diagnostics, isEmpty);
  });

  test('rejects malformed cache entries and scan metadata', () {
    for (final patch in [
      {'token': ''},
      {'name': null},
      {'size': -1},
      {'size': 1.5},
      {'modifiedAt': double.nan},
      {'modifiedAt': 8640000000000001},
    ]) {
      expect(
        () => WatchfaceCacheScan.fromPlatform({
          'files': [
            {..._entry(), ...patch},
          ],
        }),
        throwsFormatException,
      );
    }
    for (final scan in <Map<Object?, Object?>>[
      {'files': 'wrong'},
      {'files': [], 'truncated': 'yes'},
      {'files': [], 'diagnostics': 'wrong'},
      {
        'files': [],
        'diagnostics': [
          {'code': 'DENIED', 'path': '/root'},
        ],
      },
    ]) {
      expect(
        () => WatchfaceCacheScan.fromPlatform(scan),
        throwsFormatException,
      );
    }
  });

  test('decodes bundle name source, previews, warnings and embedded ID', () {
    final raw = _bundle();
    final bundle = WatchfaceCacheBundle.fromPlatform(raw);
    expect(bundle.path, '/private/session/face.bin');
    expect(bundle.name, 'XML 名称');
    expect(bundle.embeddedId, '123456789012');
    expect(bundle.nameSource, 'xml');
    expect(bundle.previews.single.path, '/private/session/preview.png');
    expect(bundle.previews.single.name, 'preview.png');
    expect(bundle.warnings, ['PATH_ID_MISMATCH']);
    (raw['warnings'] as List).clear();
    (raw['previews'] as List).clear();
    expect(bundle.warnings, ['PATH_ID_MISMATCH']);
    expect(bundle.previews, hasLength(1));
    expect(() => bundle.warnings.clear(), throwsUnsupportedError);
    expect(() => bundle.previews.clear(), throwsUnsupportedError);
    expect(
      WatchfaceCacheBundle.fromPlatform({..._bundle(), 'embeddedId': null})
          .embeddedId,
      isNull,
    );
  });

  test('rejects malformed bundle metadata and unsafe path shapes', () {
    for (final patch in <Map<String, Object?>>[
      {'path': ''},
      {'path': 'relative.bin'},
      {'path': '/private/../outside.bin'},
      {'path': '/private/face\u0000.bin'},
      {'name': ''},
      {'embeddedId': 123},
      {'embeddedId': '12345678x'},
      {'nameSource': 'unverified'},
      {
        'warnings': <Object>[1],
      },
      {'previews': 'wrong'},
      {
        'previews': [
          {'path': 'relative.png', 'name': 'preview.png'},
        ],
      },
      {
        'previews': [
          for (var i = 0; i < 9; i++)
            {'path': '/private/$i.png', 'name': '$i.png'},
        ],
      },
    ]) {
      expect(
        () => WatchfaceCacheBundle.fromPlatform({..._bundle(), ...patch}),
        throwsFormatException,
        reason: '$patch',
      );
    }
  });

  group('native method channel contract', () {
    const channel = MethodChannel('oronbox/watchface_cache');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    late List<MethodCall> calls;
    late Object? Function(MethodCall) respond;

    setUp(() {
      debugDefaultTargetPlatformOverride = TargetPlatform.android;
      calls = [];
      respond = (call) => switch (call.method) {
        'scan' => {
          'files': [_entry()],
          'truncated': false,
          'diagnostics': [],
        },
        'copyBundle' => _bundle(),
        'copy' => '/private/legacy.bin',
        _ => null,
      };
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return respond(call);
      });
    });

    tearDown(() {
      messenger.setMockMethodCallHandler(channel, null);
      debugDefaultTargetPlatformOverride = null;
    });

    test(
      'scan, copyBundle and deleteCopy preserve platform arguments',
      () async {
        final service = WatchfaceCacheImportService();
        final scan = await service.scan();
        expect(scan.files.single.token, 'scan-token');
        final bundle = await service.copyBundle('opaque-token');
        await service.cleanupBundle(bundle.path);
        await service.dispose();
        expect(calls.map((call) => call.method), [
          'scan',
          'copyBundle',
          'deleteCopy',
        ]);
        expect(calls[1].arguments, {'token': 'opaque-token'});
        expect(calls[2].arguments, {'path': '/private/session/face.bin'});
      },
    );

    test(
      'Shizuku scan and explicit authorization preserve the selected source',
      () async {
        final service = WatchfaceCacheImportService();
        await service.scan(mode: 'shizuku');
        expect(calls.single.arguments, {'mode': 'shizuku'});
        respond = (_) => true;
        expect(await service.authorize('root'), 'root');
        expect(calls.last.arguments, {'mode': 'root'});
        respond = (_) => null;
        await expectLater(service.authorize('shizuku'), throwsFormatException);
        await expectLater(service.scan(mode: 'adb'), throwsArgumentError);
      },
    );

    test('retains the old installation copy and cleanup methods', () async {
      final service = WatchfaceCacheImportService();
      expect(
        await service.copyToPrivateCache('old-token'),
        '/private/legacy.bin',
      );
      await service.deletePrivateCopy('/private/legacy.bin');
      expect(calls.map((call) => call.method), ['copy', 'deleteCopy']);
      expect(calls[0].arguments, {'token': 'old-token'});
      expect(calls[1].arguments, {'path': '/private/legacy.bin'});
    });

    test(
      'cleans a native-created bundle even when metadata is invalid',
      () async {
        respond = (call) => call.method == 'copyBundle'
            ? {..._bundle(), 'warnings': 'malformed'}
            : null;
        final service = WatchfaceCacheImportService();
        await expectLater(service.copyBundle('token'), throwsFormatException);
        expect(calls.map((call) => call.method), ['copyBundle', 'deleteCopy']);
        expect(calls.last.arguments, {'path': '/private/session/face.bin'});
        await service.dispose();
        expect(calls, hasLength(2));
      },
    );

    test(
      'dispose retries cleanup after malformed metadata and channel failure',
      () async {
        var deletes = 0;
        respond = (call) {
          if (call.method == 'copyBundle') {
            return {..._bundle(), 'nameSource': 'bad'};
          }
          if (call.method == 'deleteCopy' && deletes++ == 0) {
            throw PlatformException(code: 'PRIVATE_CACHE_CHANGED');
          }
          return null;
        };
        final service = WatchfaceCacheImportService();
        await expectLater(service.copyBundle('token'), throwsFormatException);
        await service.dispose();
        expect(deletes, 2);
        expect(calls.last.arguments, {'path': '/private/session/face.bin'});
      },
    );

    test(
      'does not attempt cleanup without an exact absolute response path',
      () async {
        respond = (call) => {'path': '../arbitrary.bin'};
        final service = WatchfaceCacheImportService();
        await expectLater(service.copyBundle('token'), throwsFormatException);
        await service.dispose();
        expect(calls.map((call) => call.method), ['copyBundle']);
      },
    );

    test('propagates copy errors and rejects empty platform results', () async {
      final service = WatchfaceCacheImportService();
      respond = (call) => throw PlatformException(code: 'SOURCE_CHANGED');
      await expectLater(
        service.copyBundle('token'),
        throwsA(
          isA<PlatformException>().having(
            (e) => e.code,
            'code',
            'SOURCE_CHANGED',
          ),
        ),
      );
      respond = (call) => null;
      await expectLater(service.copyBundle('token'), throwsFormatException);
      await expectLater(service.scan(), throwsFormatException);
      await service.dispose();
    });

    test('does not invoke Android cache methods on other platforms', () async {
      debugDefaultTargetPlatformOverride = TargetPlatform.linux;
      final service = WatchfaceCacheImportService();
      expect(WatchfaceCacheImportService.isSupported, isFalse);
      await expectLater(service.scan(), throwsUnsupportedError);
      await expectLater(service.copyBundle('token'), throwsUnsupportedError);
      await service.cleanupBundle('/private/session/face.bin');
      await service.dispose();
      expect(calls, isEmpty);
    });
  });
}

Map<String, Object?> _entry() => {
  'token': 'scan-token',
  'name': 'resource.bin',
  'location': 'download/WatchFace',
  'size': 1024,
  'modifiedAt': 1700000000000,
};

Map<Object?, Object?> _bundle() => {
  'path': '/private/session/face.bin',
  'name': 'XML 名称',
  'embeddedId': '123456789012',
  'previews': [
    {'path': '/private/session/preview.png', 'name': 'preview.png'},
  ],
  'warnings': ['PATH_ID_MISMATCH'],
  'nameSource': 'xml',
};
