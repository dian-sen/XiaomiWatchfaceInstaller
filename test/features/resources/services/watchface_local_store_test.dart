import 'dart:convert';
import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:oronbox/src/core/storage/app_data_directory_io.dart';
import 'package:oronbox/src/features/resources/services/watchface_local_store.dart';
import 'package:oronbox/src/features/resources/services/watchface_document.dart';

void main() {
  late Directory root;
  late IoAppDataDirectory bins;
  late IoAppDataDirectory previews;
  late WatchfaceLocalStore store;
  setUp(() async {
    root = await Directory.systemTemp.createTemp('watchface-store-');
    bins = IoAppDataDirectory(Directory('${root.path}/watchfaces'));
    previews = IoAppDataDirectory(Directory('${root.path}/previews'));
    store = WatchfaceLocalStore(bins: bins, previews: previews);
  });
  tearDown(() => root.delete(recursive: true));

  test(
    'replaces imported file on disk before returning the new bytes',
    () async {
      final source = Uint8List(256)
        ..setAll(0, [0x5a, 0xa5, 0x34, 0x12])
        ..setAll(0x28, ascii.encode('123456789012'));
      final edited = WatchfaceDocument.importBin(
        bytes: source,
        fileName: 'resource.bin',
      ).edit(name: 'new', id: '987654321012').bytes;
      final key = sha256.convert(source).toString();
      await store.replaceImportedBin(source, source);
      expect(await bins.read('imported/$key.bin'), source);
      final saved = await store.replaceImportedBin(source, edited);
      expect(saved, edited);
      expect(await bins.read('imported/$key.bin'), edited);
      expect((await bins.files()).length, 2);
    },
  );
  test(
    'cleanup is scoped and leaves firmware, account and queued files intact',
    () async {
      final protected = [
        'tasks/install.bin',
        'private/vault',
        'firmware/full.bin',
      ];
      for (final name in protected) {
        final file = File('${root.path}/$name');
        await file.parent.create(recursive: true);
        await file.writeAsString('keep');
      }
      await store.saveBin(Uint8List.fromList([1, 2]), edited: false);
      await store.saveBin(Uint8List.fromList([3, 4]), edited: true);
      await previews.write('preview.png', Uint8List.fromList([5, 6, 7]));
      expect(await store.bytes(), 7);
      await store.clear(previews: false);
      expect(await store.bytes(previews: false), 0);
      expect(await store.bytes(bins: false), 3);
      await store.clear(bins: false);
      expect(await store.bytes(), 0);
      for (final name in protected) {
        expect(await File('${root.path}/$name').readAsString(), 'keep');
      }
    },
  );
  test('clear waits for a writer holding the same cross-engine lock', () async {
    final entered = Completer<void>();
    final release = Completer<void>();
    final writer = bins.exclusive(() async {
      entered.complete();
      await release.future;
      await bins.write('imported/test.bin', Uint8List.fromList([1]));
    });
    await entered.future;
    var finished = false;
    final cleaning = store.clear().then((_) => finished = true);
    expect(await bins.tryExclusive(() async {}), isFalse);
    expect(finished, isFalse);
    release.complete();
    await writer;
    await cleaning;
    expect(await bins.files(), isEmpty);
  });
}
