import 'dart:convert';
import 'dart:typed_data';

import 'package:cross_file/cross_file.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:oronbox/src/device/core/watchface_install_policy.dart';
import 'package:oronbox/src/features/resources/services/watchface_bin_import.dart';
import 'package:oronbox/src/features/resources/services/watchface_document.dart';
import 'package:oronbox/src/features/resources/services/resource_install_service.dart';

Uint8List bin(String id) => Uint8List(256)
  ..setAll(0, [0x5a, 0xa5, 0x34, 0x12])
  ..setAll(0x28, ascii.encode(id));

void main() {
  test(
    'ID prompt precedes document recognition and cancellation aborts',
    () async {
      var prompted = false;
      final input = bin('123456789012');
      final result = await prepareWatchfaceBinBytes(
        input,
        'resource.bin',
        requestRandomId: (id) async {
          prompted = true;
          expect(id, '123456789012');
          return null;
        },
      );
      expect(prompted, isTrue);
      expect(result, isNull);
      expect(extractVelaWatchfaceId(input), '123456789012');
    },
  );
  test('keeping ID preserves every byte', () async {
    final input = bin('123456789012');
    final result = await prepareWatchfaceBinBytes(
      input,
      'resource.bin',
      requestRandomId: (_) async => false,
    );
    expect(result, same(input));
  });
  test('modified raw BIN is the input to all subsequent detection', () async {
    final input = bin('120912345678');
    final result = (await prepareWatchfaceBinFile(
      XFile.fromData(input, name: 'resource.bin'),
      requestRandomId: (_) async => true,
      generateId: () => '987654321012',
    ))!;
    final bytes = await result.readAsBytes();
    expect(extractVelaWatchfaceId(bytes), '987654321012');
    final document = WatchfaceDocument.importBin(
      bytes: bytes,
      fileName: result.name,
    );
    expect(document.originalId, '987654321012');
    expect(() => document.ensureInstallAllowed(), returnsNormally);
    final analysis = ResourceInstallService().analyzePayload(
      fileName: result.name,
      bytes: bytes,
    );
    expect(analysis!.identifier, '987654321012');
    for (var i = 0; i < input.length; i++) {
      if (i < 0x28 || i >= 0x34) expect(bytes[i], input[i]);
    }
    expect(extractVelaWatchfaceId(input), '120912345678');
  });
  test(
    'random generation preserves a nine digit field and its following bytes',
    () async {
      final input = bin('123456789')..[0x32] = 0xaa;
      final result = (await prepareWatchfaceBinBytes(
        input,
        'resource.bin',
        requestRandomId: (_) async => true,
      ))!;
      expect(extractVelaWatchfaceId(result)!.length, 9);
      expect(result[0x32], 0xaa);
      expect(extractVelaWatchfaceId(result), isNot('123456789'));
    },
  );
  test('non-watchface firmware bypasses ID prompt', () async {
    final input = XFile.fromData(
      Uint8List.fromList([0x60, 0x5a, 0x5a, 0x7e]),
      name: 'firmware.bin',
    );
    expect(
      await prepareWatchfaceBinFile(
        input,
        requestRandomId: (_) async => fail('firmware prompted'),
      ),
      same(input),
    );
  });
  test('truncated BIN asks first but cannot generate unsafe edits', () async {
    await expectLater(
      prepareWatchfaceBinBytes(
        Uint8List.fromList([0x5a, 0xa5, 0x34, 0x12]),
        'bad.bin',
        requestRandomId: (_) async => true,
      ),
      throwsFormatException,
    );
  });
}
