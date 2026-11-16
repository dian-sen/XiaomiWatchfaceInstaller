import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:oronbox/src/device/core/watchface_install_policy.dart';

const maxWatchfaceImportBytes = 32 * 1024 * 1024;
const _headerSize = 0xa8;
const _idStart = 0x28;
const _idEnd = 0x68;
const _nameEnd = 0xa4;
const _maxNameBytes = 200;

class WatchfacePreview {
  WatchfacePreview({required String name, required Uint8List bytes})
    : name = safeWatchfaceName(name),
      bytes = Uint8List.fromList(bytes).asUnmodifiableView() {
    if (_imageExtension(this.bytes) == null) {
      throw const FormatException('Unsupported watchface preview');
    }
  }

  final String name;
  final Uint8List bytes;

  String get mimeType =>
      _imageExtension(bytes) == 'png' ? 'image/png' : 'image/jpeg';
}

/// An owned, immutable BIN snapshot. Naming affects exports, not opaque BIN data.
class WatchfaceDocument {
  WatchfaceDocument._({
    required this.bytes,
    required this.name,
    required this.id,
    required this.originalId,
    required this.previews,
    required this.warnings,
  });

  factory WatchfaceDocument.importBin({
    required Uint8List bytes,
    required String fileName,
    String? suggestedName,
    List<WatchfacePreview> previews = const [],
    List<String> warnings = const [],
  }) {
    if (bytes.length > maxWatchfaceImportBytes) {
      throw const FormatException('Watchface exceeds the import limit');
    }
    if (bytes.length < _headerSize ||
        bytes[0] != 0x5a ||
        bytes[1] != 0xa5 ||
        bytes[2] != 0x34 ||
        bytes[3] != 0x12) {
      throw const FormatException('Unsupported watchface BIN');
    }
    final owned = Uint8List.fromList(bytes).asUnmodifiableView();
    final fallback = fileName.split(RegExp(r'[/\\]')).last;
    return WatchfaceDocument._(
      bytes: owned,
      name: safeWatchfaceName(
        _metadataName(suggestedName) ??
            _readName(owned) ??
            fallback.replaceFirst(RegExp(r'\.bin$', caseSensitive: false), ''),
      ),
      id: _readId(owned),
      originalId: _readId(owned),
      previews: List.unmodifiable(previews),
      warnings: List.unmodifiable(warnings),
    );
  }

  final Uint8List bytes;
  final String name;
  final String? id;
  final String? originalId;
  final List<WatchfacePreview> previews;
  final List<String> warnings;

  bool get canEditId => id != null;

  WatchfaceDocument edit({required String name, required String id}) {
    final edited = Uint8List.fromList(bytes);
    if (id != (this.id ?? '')) {
      if (!canEditId) {
        throw const FormatException('Watchface ID cannot be safely edited');
      }
      if (!isValidVelaWatchfaceId(id)) {
        throw const FormatException('Invalid watchface ID');
      }
      final oldLength = this.id!.length;
      if (id.length > oldLength) {
        // Include the new terminator: expansion may only consume zero padding.
        for (var i = _idStart + oldLength; i <= _idStart + id.length; i++) {
          if (bytes[i] != 0) {
            throw const FormatException('Watchface ID has no safe padding');
          }
        }
      }
      edited.setRange(_idStart, _idStart + id.length, ascii.encode(id));
      if (id.length < oldLength) {
        edited.fillRange(_idStart + id.length, _idStart + oldLength, 0);
      }
    }
    return WatchfaceDocument._(
      bytes: edited.asUnmodifiableView(),
      name: safeWatchfaceName(name),
      id: this.id == null ? null : id,
      originalId: originalId,
      previews: previews,
      warnings: warnings,
    );
  }

  void ensureInstallAllowed() {
    final restrictedId = restrictedWatchfaceIdFor(
      payload: bytes,
      identifier: originalId,
      analyzedIdentifier: id,
    );
    if (restrictedId != null) {
      throw WatchfaceInstallBlockedException(restrictedId);
    }
  }

  String get binFileName => '$name.bin';

  String previewFileName(int index) {
    final preview = previews[index];
    return '$name-preview-${index + 1}.${_imageExtension(preview.bytes)!}';
  }

  Uint8List exportArchive() {
    final archive = Archive()
      ..addFile(ArchiveFile(binFileName, bytes.length, bytes));
    for (var i = 0; i < previews.length; i++) {
      final preview = previews[i];
      archive.addFile(
        ArchiveFile(previewFileName(i), preview.bytes.length, preview.bytes),
      );
    }
    return Uint8List.fromList(ZipEncoder().encode(archive));
  }
}

/// A portable filename stem, with room for export suffixes and extensions.
String safeWatchfaceName(String value) {
  var name = utf8
      .decode(utf8.encode(value))
      .replaceAll(
        RegExp(
          r'[<>:"/\\|?*\x00-\x1f\x7f-\x9f\u00ad\u061c\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff]',
        ),
        '_',
      )
      .trim()
      .replaceAll(RegExp(r'^[. ]+|[. ]+$'), '');
  if (name.isEmpty) name = 'watchface';
  if (RegExp(
    r'^(?:con|prn|aux|nul|clock\$|conin\$|conout\$|com[1-9¹²³]|lpt[1-9¹²³])$',
    caseSensitive: false,
  ).hasMatch(name.split('.').first.trimRight())) {
    name = '_$name';
  }
  final result = StringBuffer();
  var length = 0;
  for (final rune in name.runes) {
    final character = String.fromCharCode(rune);
    final size = utf8.encode(character).length;
    if (length + size > _maxNameBytes) break;
    result.write(character);
    length += size;
  }
  return result.toString().replaceAll(RegExp(r'[. ]+$'), '');
}

String? _readId(Uint8List bytes) {
  var end = _idStart;
  while (end < _idEnd && bytes[end] != 0) {
    end++;
  }
  if (end == _idEnd || (end - _idStart != 9 && end - _idStart != 12)) {
    return null;
  }
  final field = bytes.sublist(_idStart, end);
  if (field.any((byte) => byte < 0x30 || byte > 0x39)) {
    return null;
  }
  return ascii.decode(field);
}

String? _readName(Uint8List bytes) {
  var end = _idEnd;
  while (end < _nameEnd && bytes[end] != 0) {
    end++;
  }
  try {
    return _metadataName(utf8.decode(bytes.sublist(_idEnd, end)));
  } on FormatException {
    return null;
  }
}

String? _metadataName(String? value) {
  final name = value?.trim();
  if (name == null ||
      name.isEmpty ||
      RegExp(
        r'[\x00-\x1f\x7f-\x9f\ufffd\u00ad\u061c\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff]',
      ).hasMatch(name)) {
    return null;
  }
  return name;
}

String? _imageExtension(Uint8List bytes) {
  const png = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (bytes.length >= png.length) {
    var matches = true;
    for (var i = 0; i < png.length; i++) {
      if (bytes[i] != png[i]) matches = false;
    }
    if (matches) return 'png';
  }
  if (bytes.length >= 3 &&
      bytes[0] == 0xff &&
      bytes[1] == 0xd8 &&
      bytes[2] == 0xff) {
    return 'jpg';
  }
  return null;
}
