import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:oronbox/src/device/core/watchface_install_policy.dart';
import 'package:oronbox/src/features/resources/services/watchface_document.dart';

void main() {
  group('BIN recognition', () {
    test('requires all magic bytes and a complete header', () {
      for (var i = 0; i < 4; i++) {
        final bytes = _bin()..[i] ^= 1;
        expect(() => _document(bytes), throwsFormatException);
      }
      expect(() => _document(Uint8List(0xa7)), throwsFormatException);
      expect(() => _document(Uint8List(0xa8)), throwsFormatException);
      expect(_document(_bin()).id, '123456789012');
      expect(
        () => _document(Uint8List(maxWatchfaceImportBytes + 1)),
        throwsFormatException,
      );
    });

    test('recognizes only NUL-terminated 9 or 12 ASCII digits', () {
      for (final id in ['123456789', '123456789012']) {
        final document = _document(_bin(id: id));
        expect(document.id, id);
        expect(document.canEditId, isTrue);
      }
      for (final id in [
        '',
        '12345678',
        '1234567890',
        '1234567890123',
        '12345678x',
        ' 12345678',
        '12345678 ',
      ]) {
        final document = _document(_bin(id: id));
        expect(document.id, isNull, reason: id);
        expect(document.canEditId, isFalse, reason: id);
      }
      final unterminated = _bin()..fillRange(0x28, 0x68, 0x31);
      expect(_document(unterminated).id, isNull);
      final nonAscii = _bin(id: '123456789')..[0x28] = 0xb1;
      expect(_document(nonAscii).id, isNull);
    });

    test('owns immutable input and metadata snapshots', () {
      final input = _bin();
      final before = Uint8List.fromList(input);
      final warnings = ['NO_PREVIEW'];
      final previews = [_png()];
      final document = WatchfaceDocument.importBin(
        bytes: input,
        fileName: 'face.bin',
        previews: previews,
        warnings: warnings,
      );
      input.fillRange(0, input.length, 0xff);
      warnings.clear();
      previews.clear();
      expect(document.bytes, before);
      expect(document.warnings, ['NO_PREVIEW']);
      expect(document.previews, hasLength(1));
      expect(() => document.bytes[0] = 0, throwsUnsupportedError);
      expect(() => document.previews.clear(), throwsUnsupportedError);
      expect(() => document.warnings.clear(), throwsUnsupportedError);
    });
  });

  group('editing', () {
    test('changes only ID bytes and leaves both inputs unchanged', () {
      final input = _bin();
      final original = Uint8List.fromList(input);
      final document = _document(input);
      final edited = document.edit(name: 'renamed', id: '120912345678');
      final expected = Uint8List.fromList(original)
        ..setRange(0x28, 0x34, ascii.encode('120912345678'));
      expect(edited.bytes, expected);
      expect(edited.id, '120912345678');
      expect(edited.name, 'renamed');
      expect(edited.binFileName, 'renamed.bin');
      expect(input, original);
      expect(document.bytes, original);
      expect(identical(edited.bytes, document.bytes), isFalse);
    });

    test('extends 9 to 12 digits only across zero padding', () {
      final original = _bin(id: '123456789');
      final edited = _document(original).edit(name: 'face', id: '987654321012');
      final expected = Uint8List.fromList(original)
        ..setRange(0x28, 0x34, ascii.encode('987654321012'));
      expect(edited.bytes, expected);
      for (var offset = 0x31; offset <= 0x34; offset++) {
        final unsafe = _bin(id: '123456789')..[offset] = 0x7e;
        if (offset == 0x31) {
          expect(_document(unsafe).canEditId, isFalse);
        }
        expect(
          () => _document(unsafe).edit(name: 'face', id: '987654321012'),
          throwsFormatException,
          reason: 'padding byte $offset',
        );
      }
      final afterTerminator = _bin(id: '123456789')..[0x35] = 0x7e;
      expect(
        _document(afterTerminator)
            .edit(name: 'face', id: '987654321012')
            .bytes[0x35],
        0x7e,
      );
    });

    test('shortening clears the old ID tail without touching later data', () {
      final original = _bin()..[0x35] = 0x91;
      final expected = Uint8List.fromList(original)
        ..setRange(0x28, 0x31, ascii.encode('987654321'))
        ..fillRange(0x31, 0x35, 0);
      final edited = _document(original).edit(name: 'face', id: '987654321');
      expect(edited.bytes, expected);
      expect(edited.id, '987654321');
      expect(edited.bytes[0x35], 0x91);
    });

    test('same-width edit does not consume unrelated padding', () {
      final original = _bin(id: '123456789')..[0x32] = 0x88;
      final expected = Uint8List.fromList(original)
        ..setRange(0x28, 0x31, ascii.encode('987654321'));
      expect(
        _document(original).edit(name: 'face', id: '987654321').bytes,
        expected,
      );
    });

    test('rejects zero, non-digit and wrong-width replacement IDs', () {
      final document = _document(_bin());
      for (final id in [
        '',
        '0',
        '000000000',
        '000000000000',
        '12345678',
        '1234567890',
        '1234567890123',
        '12345678a',
        '１２３４５６７８９',
        ' 123456789',
      ]) {
        expect(
          () => document.edit(name: 'face', id: id),
          throwsFormatException,
          reason: id,
        );
      }
    });

    test('unknown ID allows byte-preserving renaming and export', () {
      final input = _bin(id: 'unknown');
      final document = _document(input);
      final renamed = document.edit(name: 'my face', id: '');
      expect(renamed.canEditId, isFalse);
      expect(renamed.bytes, input);
      expect(renamed.binFileName, 'my face.bin');
      expect(
        () => document.edit(name: 'face', id: '123456789012'),
        throwsFormatException,
      );
    });
  });

  group('installation policy', () {
    for (final originalId in ['120912345', '120912345678']) {
      test('preserves restricted original ID $originalId across edits', () {
        final original = _document(_bin(id: originalId));
        final edited = original
            .edit(name: 'renamed', id: '998877665544')
            .edit(name: 'renamed again', id: '987654321');

        expect(original.originalId, originalId);
        expect(edited.originalId, originalId);
        expect(edited.id, '987654321');
        expect(extractVelaWatchfaceId(edited.bytes), '987654321');
        expect(
          edited.ensureInstallAllowed,
          throwsA(
            isA<WatchfaceInstallBlockedException>().having(
              (error) => error.watchfaceId,
              'watchfaceId',
              originalId,
            ),
          ),
        );
      });
    }

    test(
      'checks a newly restricted payload despite an allowed original ID',
      () {
        final edited = _document(_bin())
            .edit(name: 'restricted', id: '120912345678');

        expect(edited.originalId, '123456789012');
        expect(extractVelaWatchfaceId(edited.bytes), '120912345678');
        expect(
          edited.ensureInstallAllowed,
          throwsA(isA<WatchfaceInstallBlockedException>()),
        );
      },
    );

    test('checks restricted payloads whose IDs cannot be safely edited', () {
      final document = _document(_bin(id: '1209unknown'));

      expect(document.id, isNull);
      expect(document.originalId, isNull);
      expect(document.canEditId, isFalse);
      expect(
        document.ensureInstallAllowed,
        throwsA(isA<WatchfaceInstallBlockedException>()),
      );
    });

    test('permits allowed original and edited IDs', () {
      final edited = _document(_bin())
          .edit(name: 'allowed', id: '998877665544');

      expect(edited.ensureInstallAllowed, returnsNormally);
    });
  });

  group('names', () {
    test('prefers verified suggestion, then strict UTF-8, then filename', () {
      expect(
        WatchfaceDocument.importBin(
          bytes: _bin(name: 'BIN 名称'),
          fileName: 'source.bin',
          suggestedName: 'XML 名称',
        ).name,
        'XML 名称',
      );
      expect(_document(_bin(name: 'BIN 名称')).name, 'BIN 名称');
      final malformed = _bin(name: '')
        ..[0x68] = 0xff
        ..[0x69] = 0xfe;
      expect(
        WatchfaceDocument.importBin(
          bytes: malformed,
          fileName: r'C:\untrusted\fallback.BIN',
        ).name,
        'fallback',
      );
      expect(
        WatchfaceDocument.importBin(
          bytes: _bin(name: ''),
          fileName: '/untrusted/source.bin',
          suggestedName: 'bad\u0000name',
        ).name,
        'source',
      );
    });

    test('sanitizes portable filename stems', () {
      for (final name in [
        '../../bad\\path:*?<>|"\u0000\u001f\u007f',
        '\u202ereverse',
      ]) {
        final safe = safeWatchfaceName(name);
        expect(RegExp(r'[/\\<>:"|?*\x00-\x1f\x7f]').hasMatch(safe), isFalse);
        expect(safe, isNot(startsWith('.')));
        expect(safe, isNot(contains('\u202e')));
      }
      expect(safeWatchfaceName(' . '), 'watchface');
      expect(safeWatchfaceName('  face...  '), 'face');
      for (final name in [
        'CON',
        'nul.txt',
        'COM1',
        'Lpt9.bin',
        'aux',
        r'CONIN$',
        'CON .txt',
      ]) {
        expect(safeWatchfaceName(name), startsWith('_'));
      }
    });

    test('truncates by UTF-8 bytes without splitting Unicode', () {
      final safe = safeWatchfaceName(List.filled(100, '界😀').join());
      expect(utf8.encode(safe).length, lessThanOrEqualTo(200));
      expect(utf8.decode(utf8.encode(safe)), safe);
      expect(safe, isNot(contains('\ufffd')));
      expect(safe.codeUnits.last, isNot(inInclusiveRange(0xd800, 0xdbff)));
      final document = _document(_bin())
          .edit(name: List.filled(200, '😀').join(), id: '123456789012');
      expect(utf8.encode(document.binFileName).length, lessThan(255));
    });
  });

  group('archive and previews', () {
    test(
      'sniffs images instead of trusting extension and owns their bytes',
      () {
        final bytes = Uint8List.fromList([0xff, 0xd8, 0xff, 0x00]);
        final preview = WatchfacePreview(name: 'image.png', bytes: bytes);
        bytes[0] = 0;
        expect(preview.mimeType, 'image/jpeg');
        expect(preview.bytes.first, 0xff);
        expect(() => preview.bytes[0] = 0, throwsUnsupportedError);
        expect(_png().mimeType, 'image/png');
        expect(
          () => WatchfacePreview(name: 'fake.png', bytes: Uint8List(20)),
          throwsFormatException,
        );
      },
    );

    test('ZIP contains current-name BIN and only actual preview bytes', () {
      final png = _png();
      final jpg = WatchfacePreview(
        name: 'wrong.png',
        bytes: Uint8List.fromList([0xff, 0xd8, 0xff, 0x01]),
      );
      final document = WatchfaceDocument.importBin(
        bytes: _bin(),
        fileName: 'original.bin',
        previews: [png, jpg],
      ).edit(name: '新的名字', id: '987654321012');
      final archive = ZipDecoder().decodeBytes(document.exportArchive());
      expect(archive.files.map((file) => file.name), [
        '新的名字.bin',
        '新的名字-preview-1.png',
        '新的名字-preview-2.jpg',
      ]);
      expect(archive.files[0].content, document.bytes);
      expect(archive.files[1].content, png.bytes);
      expect(archive.files[2].content, jpg.bytes);
    });

    test('BIN-only archives do not invent XML or a preview', () {
      final document = _document(_bin());
      final archive = ZipDecoder().decodeBytes(document.exportArchive());
      expect(archive.files, hasLength(1));
      expect(archive.files.single.name, document.binFileName);
      expect(archive.files.single.content, document.bytes);
    });
  });
}

WatchfaceDocument _document(Uint8List bytes) =>
    WatchfaceDocument.importBin(bytes: bytes, fileName: 'fallback.bin');

Uint8List _bin({String id = '123456789012', String name = '表盘'}) {
  final bytes = Uint8List(0xa8 + 32);
  bytes.setRange(0, 4, [0x5a, 0xa5, 0x34, 0x12]);
  bytes.setRange(0x28, 0x28 + id.length, ascii.encode(id));
  final nameBytes = utf8.encode(name);
  bytes.setRange(0x68, 0x68 + nameBytes.length, nameBytes);
  bytes.fillRange(0xa8, bytes.length, 0x9b);
  return bytes;
}

WatchfacePreview _png() => WatchfacePreview(
  name: 'preview.png',
  bytes: Uint8List.fromList([0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10, 0x01]),
);
