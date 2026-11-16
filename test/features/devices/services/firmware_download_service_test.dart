import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:oronbox/src/features/devices/services/firmware_catalog.dart';
import 'package:oronbox/src/features/devices/services/firmware_download_service.dart';

const name = 'miwear.watch.n67cn_v3.1.175_full_test.bin';
FirmwarePackage package({
  int size = 4,
  String? digest,
  String? fromVersion,
  String? url,
}) => FirmwarePackage(
  downloadUrl: Uri.parse(
    url ??
        'https://github.com/AstralSightStudios/MiWearFirmwareArchives/releases/download/test/$name',
  ),
  fileName: name,
  size: size,
  sha256: digest,
  fromVersion: fromVersion,
);

class Adapter implements HttpClientAdapter {
  Adapter(this.bytes);
  final List<int> bytes;
  int calls = 0;
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    calls++;
    return ResponseBody(
      Stream.value(Uint8List.fromList(bytes)),
      200,
      headers: {
        Headers.contentLengthHeader: ['${bytes.length}'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  late Directory root;
  setUp(() async {
    root = await Directory.systemTemp.createTemp('firmware-download-test-');
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      const MethodChannel('plugins.flutter.io/path_provider'),
      (_) async => root.path,
    );
  });
  tearDown(() async {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(
      const MethodChannel('plugins.flutter.io/path_provider'),
      null,
    );
    await root.delete(recursive: true);
  });
  test('only full packages from the existing GitHub archive are accepted', () async {
    final dio = Dio();
    final adapter = Adapter([1, 2, 3, 4]);
    dio.httpClientAdapter = adapter;
    final service = FirmwareDownloadService(dio);
    for (final bad in [
      package(fromVersion: '3.1.162'),
      package(url: 'https://example.com/$name'),
      package(
        url:
            'http://github.com/AstralSightStudios/MiWearFirmwareArchives/releases/download/a/$name',
      ),
      package(size: maxFirmwareDownloadBytes + 1),
    ]) {
      await expectLater(
        service.downloadFull(bad, onProgress: (_) {}),
        throwsFormatException,
      );
    }
    expect(adapter.calls, 0);
    dio.close();
  });
  test(
    'length and SHA-256 must pass before a leased path is returned',
    () async {
      final dio = Dio()..httpClientAdapter = Adapter([1, 2, 3, 4]);
      final service = FirmwareDownloadService(dio);
      final progress = <double>[];
      final path = await service.downloadFull(
        package(digest: sha256.convert([1, 2, 3, 4]).toString()),
        onProgress: progress.add,
      );
      expect(await File(path).readAsBytes(), [1, 2, 3, 4]);
      expect(progress.last, 1);
      await service.release(path);
      expect(await File(path).exists(), isFalse);
      dio.close();
    },
  );
  test(
    'truncated or corrupt downloads fail and remove partial payloads',
    () async {
      final dio = Dio()..httpClientAdapter = Adapter([1, 2, 3]);
      final service = FirmwareDownloadService(dio);
      await expectLater(
        service.downloadFull(package(), onProgress: (_) {}),
        throwsFormatException,
      );
      await expectLater(
        service.downloadFull(
          package(size: 3, digest: '0' * 64),
          onProgress: (_) {},
        ),
        throwsFormatException,
      );
      final payloads = await root
          .list(recursive: true)
          .where((item) => item is File && item.path.endsWith('.bin'))
          .toList();
      expect(payloads, isEmpty);
      dio.close();
    },
  );
}
