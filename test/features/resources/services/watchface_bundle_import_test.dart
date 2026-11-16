import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:cross_file/cross_file.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:oronbox/src/device/core/watchface_install_policy.dart';
import 'package:oronbox/src/features/resources/services/watchface_bundle_import.dart';
import 'package:oronbox/src/features/resources/services/watchface_document.dart';
import 'package:oronbox/src/features/resources/services/watchface_workbench_service.dart';

void main() {
  const importer = WatchfaceBundleImport();
  test(
    'computer bundle retains BIN authority, XML name, previews and editing',
    () async {
      final bytes = _bundle(
        {
          'resource.bin': _bin(),
          'description.xml': utf8.encode('<watch/>'),
          'preview/preview.png': _png(),
        },
        metadata: {
          'id': '123456789012',
          'name': 'XML face',
          'nameSource': 'xml',
        },
      );
      final service = WatchfaceWorkbenchService();
      addTearDown(service.dispose);
      final document = await service.importFile(
        XFile.fromData(bytes, name: 'export.ZIP'),
      );
      expect(document.name, 'XML face');
      expect(document.id, '123456789012');
      expect(document.previews.single.bytes, _png());
      final edited = document.edit(name: 'renamed', id: '987654321012');
      expect(edited.binFileName, 'renamed.bin');
      expect(edited.originalId, '123456789012');
      expect(document.bytes, _bin());
    },
  );
  test('mismatched metadata cannot override BIN name or ID', () {
    final document = importer.importZip(
      _bundle(
        {'resource.bin': _bin(), 'description.xml': utf8.encode('<watch/>')},
        metadata: {
          'id': '999999999999',
          'name': 'Wrong XML',
          'nameSource': 'xml',
        },
      ),
    );
    expect(document.name, 'BIN face');
    expect(document.id, '123456789012');
    expect(document.warnings, contains('XML_METADATA_NOT_APPLIED'));
  });
  test('workbench export without manifest round trips with editing intact', () {
    final source = WatchfaceDocument.importBin(
      bytes: _bin(),
      fileName: 'original.bin',
      previews: [WatchfacePreview(name: 'p.png', bytes: _png())],
    ).edit(name: 'renamed export', id: '987654321012');
    final document = importer.importZip(source.exportArchive());
    expect(document.name, 'renamed export');
    expect(document.id, '987654321012');
    expect(document.bytes, source.bytes);
    expect(document.previews.single.bytes, _png());
    expect(document.warnings, contains('BUNDLE_MANIFEST_MISSING'));
    expect(
      document.edit(name: 'new name', id: '987654321012').id,
      '987654321012',
    );
  });
  test('ADB metadata cannot remove restricted original BIN ID', () {
    final bytes = _bin()..setRange(0x28, 0x34, ascii.encode('120912345678'));
    final document = importer.importZip(
      _bundle(
        {'resource.bin': bytes},
        metadata: {
          'id': '123456789012',
          'name': 'Allowed claim',
          'nameSource': 'xml',
        },
      ),
    );
    final edited = document.edit(name: 'renamed', id: '987654321012');
    expect(document.id, '120912345678');
    expect(edited.originalId, '120912345678');
    expect(
      edited.ensureInstallAllowed,
      throwsA(isA<WatchfaceInstallBlockedException>()),
    );
  });
  test('pickLocal accepts a ZIP selection', () async {
    final selected = XFile.fromData(
      _bundle({'resource.bin': _bin()}),
      name: 'picked.zip',
    );
    final service = WatchfaceWorkbenchService(pickFile: () async => selected);
    addTearDown(service.dispose);
    expect((await service.pickLocal())!.id, '123456789012');
  });
  test('plain BIN remains supported through workbench', () async {
    final service = WatchfaceWorkbenchService();
    addTearDown(service.dispose);
    final document = await service.importFile(
      XFile.fromData(_bin(), name: 'face.bin'),
    );
    expect(document.id, '123456789012');
  });
  test(
    'rejects manifest corruption, omissions, unknown schema and duplicates',
    () {
      for (final mutation in [
        'digest',
        'size',
        'omit',
        'duplicate',
        'version',
      ]) {
        final files = {'resource.bin': _bin(), 'preview/p.png': _png()};
        expect(
          () => importer.importZip(
            _bundle(
              files,
              mutate: (manifest) {
                final records = manifest['files'] as List;
                switch (mutation) {
                  case 'digest':
                    (records.first as Map)['sha256'] = '0' * 64;
                  case 'size':
                    (records.first as Map)['size'] = 1;
                  case 'omit':
                    records.removeLast();
                  case 'duplicate':
                    records[1] = records.first;
                  case 'version':
                    manifest['version'] = 7;
                }
              },
            ),
          ),
          throwsFormatException,
          reason: mutation,
        );
      }
    },
  );
  test('rejects absolute, traversal, backslash, drive and control paths', () {
    for (final name in [
      '/resource.bin',
      '../resource.bin',
      'a/../resource.bin',
      'a\\resource.bin',
      'C:/resource.bin',
      'a//resource.bin',
      './resource.bin',
      'a/\u0000resource.bin',
    ]) {
      expect(
        () => importer.importZip(_zip({'resource.bin': _bin(), name: _bin()})),
        throwsFormatException,
        reason: name,
      );
    }
  });
  test('rejects duplicate central names before archive can merge them', () {
    final bytes = _zip({'face.bin': _bin(), 'fake.bin': _bin()});
    final target = utf8.encode('fake.bin');
    final replacement = utf8.encode('face.bin');
    for (var i = 0; i <= bytes.length - target.length; i++) {
      if (List.generate(target.length, (j) => bytes[i + j]).join(',') ==
          target.join(',')) {
        bytes.setRange(i, i + target.length, replacement);
      }
    }
    expect(() => importer.importZip(bytes), throwsFormatException);
  });
  test('rejects symlinks before reading their payload', () {
    final bytes = _zip({'resource.bin': _bin(), 'preview.png': _png()});
    final data = ByteData.sublistView(bytes);
    for (var i = 0; i < bytes.length - 46; i++) {
      if (data.getUint32(i, Endian.little) == 0x02014b50) {
        data.setUint16(i + 4, 3 << 8, Endian.little);
        data.setUint32(i + 38, 0xa1ff << 16, Endian.little);
        break;
      }
    }
    expect(() => importer.importZip(bytes), throwsFormatException);
  });
  test('rejects multiple BINs, unsupported entries and invalid previews', () {
    for (final files in [
      {'a.bin': _bin(), 'b.bin': _bin()},
      {
        'resource.bin': _bin(),
        'payload.exe': [1],
      },
      {
        'resource.bin': _bin(),
        'fake.png': [1, 2, 3],
      },
    ]) {
      expect(() => importer.importZip(_zip(files)), throwsFormatException);
    }
  });
  test('rejects truncated ZIP and CRC-corrupted stored entries', () {
    final bytes = _zip({'resource.bin': _bin()});
    expect(
      () =>
          importer.importZip(Uint8List.sublistView(bytes, 0, bytes.length - 1)),
      throwsFormatException,
    );
    final data = ByteData.sublistView(bytes);
    final start =
        30 +
        data.getUint16(26, Endian.little) +
        data.getUint16(28, Endian.little);
    bytes[start + 5] ^= 1;
    expect(() => importer.importZip(bytes), throwsFormatException);
  });
  test('bounds entry count, compressed input, BIN and preview output', () {
    expect(
      () => importer.importZip(
        _zip({
          'resource.bin': _bin(),
          for (var i = 0; i < 64; i++) '$i.xml': [1],
        }),
      ),
      throwsFormatException,
    );
    expect(
      () => importer.importZip(Uint8List(maxWatchfaceBundleBytes + 1)),
      throwsFormatException,
    );
    for (final entry in ['resource.bin', 'preview.png']) {
      final bytes = _zip({
        'resource.bin': _bin(),
        if (entry != 'resource.bin') entry: _png(),
      });
      final data = ByteData.sublistView(bytes);
      for (var i = 0; i < bytes.length - 46; i++) {
        if (data.getUint32(i, Endian.little) == 0x02014b50) {
          final length = data.getUint16(i + 28, Endian.little);
          if (utf8.decode(bytes.sublist(i + 46, i + 46 + length)) == entry) {
            data.setUint32(
              i + 24,
              entry == 'resource.bin'
                  ? maxWatchfaceImportBytes + 1
                  : 8 * 1024 * 1024 + 1,
              Endian.little,
            );
          }
        }
      }
      expect(() => importer.importZip(bytes), throwsFormatException);
    }
  });
  test('rejects declared-size lie even with a small central header', () {
    final archive = Archive()
      ..addFile(
        ArchiveFile(
          'resource.bin',
          1024 * 1024,
          Uint8List(1024 * 1024)..setRange(0, _bin().length, _bin()),
        ),
      );
    final bytes = Uint8List.fromList(ZipEncoder().encode(archive));
    final data = ByteData.sublistView(bytes);
    for (var i = 0; i < bytes.length - 46; i++) {
      if (data.getUint32(i, Endian.little) == 0x02014b50) {
        data.setUint32(i + 24, 0xa8, Endian.little);
        break;
      }
    }
    expect(() => importer.importZip(bytes), throwsFormatException);
  });
  test('enforces aggregate image and total uncompressed budgets', () {
    final image = Uint8List(6 * 1024 * 1024)
      ..setRange(0, _png().length, _png());
    expect(
      () => importer.importZip(
        _zip({
          'resource.bin': _bin(),
          for (var i = 0; i < 3; i++) '$i.png': image,
        }),
      ),
      throwsFormatException,
    );
    final bin = Uint8List(maxWatchfaceImportBytes)
      ..setRange(0, _bin().length, _bin());
    expect(
      () => importer.importZip(
        _zip({
          'resource.bin': bin,
          '1.png': image,
          '2.png': image,
        }, compress: true),
      ),
      throwsFormatException,
    );
  });
}

Uint8List _bin() => Uint8List(0xa8)
  ..setRange(0, 4, [0x5a, 0xa5, 0x34, 0x12])
  ..setRange(0x28, 0x34, ascii.encode('123456789012'))
  ..setRange(0x68, 0x70, utf8.encode('BIN face'));
Uint8List _png() => Uint8List.fromList([137, 80, 78, 71, 13, 10, 26, 10]);
Uint8List _zip(Map<String, List<int>> files, {bool compress = false}) {
  final archive = Archive();
  for (final entry in files.entries) {
    archive.addFile(
      compress
          ? ArchiveFile(entry.key, entry.value.length, entry.value)
          : ArchiveFile.noCompress(entry.key, entry.value.length, entry.value),
    );
  }
  return Uint8List.fromList(ZipEncoder().encode(archive));
}

Uint8List _bundle(
  Map<String, List<int>> files, {
  Map<String, Object?>? metadata,
  void Function(Map<String, Object?>)? mutate,
}) {
  final manifest = <String, Object?>{
    'format': 'oronbox.watchface.bundle',
    'version': 1,
    'metadata': metadata,
    'files': files.entries
        .map(
          (entry) => <String, Object?>{
            'path': entry.key,
            'size': entry.value.length,
            'sha256': sha256.convert(entry.value).toString(),
          },
        )
        .toList(),
  };
  mutate?.call(manifest);
  return _zip({...files, 'manifest.json': utf8.encode(jsonEncode(manifest))});
}
