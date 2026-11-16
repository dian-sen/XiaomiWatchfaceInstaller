import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:oronbox/src/device/core/device_kind.dart';
import 'package:oronbox/src/device/core/watchface_install_policy.dart';
import 'package:oronbox/src/features/devices/controllers/device_manager.dart';
import 'package:oronbox/src/features/resources/services/resource_install_service.dart';
import 'package:oronbox/src/features/resources/services/watchface_bin_import.dart';

class _Manager extends LocalDeviceManager {
  Uint8List? sent;
  String? id;
  @override
  DeviceKind? get currentDeviceKind => DeviceKind.xiaomi;
  @override
  Future<void> installWatchface(
    Uint8List bytes, {
    required String watchfaceId,
    void Function(double)? onProgress,
  }) async {
    sent = bytes;
    id = watchfaceId;
  }
}

void main() {
  test(
    'install uses replacement BIN and ID even with the old numeric filename',
    () async {
      final source = Uint8List(256)
        ..setAll(0, [0x5a, 0xa5, 0x34, 0x12])
        ..setAll(0x28, '120912345678'.codeUnits);
      final prepared = (await prepareWatchfaceBinBytes(
        source,
        '120912345678.bin',
        requestRandomId: (_) async => true,
        generateId: () => '987654321012',
      ))!;
      final manager = _Manager();
      await ResourceInstallService().installLocalPayload(
        type: LocalDeviceInstallType.watchface,
        fileName: '120912345678.bin',
        bytes: prepared,
        deviceManager: manager,
        onProgress: (_) {},
      );
      expect(manager.id, '987654321012');
      expect(manager.sent, prepared);
      expect(extractVelaWatchfaceId(manager.sent!), '987654321012');
    },
  );
}
