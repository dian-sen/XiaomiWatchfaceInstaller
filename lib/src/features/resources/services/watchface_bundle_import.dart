import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:crypto/crypto.dart';

import 'watchface_document.dart';

const maxWatchfaceBundleBytes = 40 * 1024 * 1024;
const _maxEntries = 64;
const _maxImageBytes = 8 * 1024 * 1024;
const _maxImagesBytes = 16 * 1024 * 1024;
const _maxMetadataBytes = 512 * 1024;

/// Imports portable bundles entirely in memory. No ZIP path reaches the filesystem.
class WatchfaceBundleImport {
  const WatchfaceBundleImport();

  WatchfaceDocument importZip(Uint8List bytes) {
    try {
      return _import(bytes);
    } on FormatException {
      rethrow;
    } catch (_) {
      throw const FormatException('Invalid watchface bundle');
    }
  }

  WatchfaceDocument _import(Uint8List bytes) {
    if (bytes.length > maxWatchfaceBundleBytes) _fail('Bundle exceeds limit');
    // ZipDecoder merges duplicate names and reads symlink payloads eagerly.
    // Inspect every central/local header before handing anything to archive v4.
    _preflight(bytes);
    final directory = ZipDirectory()..read(InputMemoryStream(bytes));
    final files = <String, Uint8List>{};
    var total = 0;
    var imageTotal = 0;
    var imageCount = 0;
    for (final header in directory.fileHeaders) {
      final name = header.filename;
      if (name.endsWith('/')) continue;
      final extension = name.split('.').last.toLowerCase();
      final image = const ['png', 'jpg', 'jpeg'].contains(extension);
      final maximum = extension == 'bin'
          ? maxWatchfaceImportBytes
          : image
          ? _maxImageBytes
          : _maxMetadataBytes;
      if (extension != 'bin' &&
          !image &&
          extension != 'xml' &&
          name != 'manifest.json') {
        _fail('Unsupported bundle entry');
      }
      if (header.uncompressedSize > maximum) _fail('Entry exceeds limit');
      total += header.uncompressedSize;
      if (total > maxWatchfaceBundleBytes) _fail('Bundle expands beyond limit');
      if (image) {
        imageTotal += header.uncompressedSize;
        if (++imageCount > 8 || imageTotal > _maxImagesBytes) {
          _fail('Preview limit');
        }
      }
      final output = _BoundedOutput(header.uncompressedSize);
      header.file!.decompress(output);
      final content = output.getBytes();
      if (content.length != header.uncompressedSize ||
          getCrc32(content) != header.crc32) {
        _fail('Entry checksum or size mismatch');
      }
      files[name] = content;
    }
    final bins = files.keys
        .where((name) => name.toLowerCase().endsWith('.bin'))
        .toList();
    if (bins.length != 1) _fail('Bundle must contain exactly one BIN');
    final bin = bins.single;
    final warnings = <String>[];
    Map<String, dynamic>? manifest;
    if (files.containsKey('manifest.json')) {
      final decoded = jsonDecode(utf8.decode(files['manifest.json']!));
      if (decoded is! Map<String, dynamic> ||
          decoded['format'] != 'oronbox.watchface.bundle' ||
          decoded['version'] != 1) {
        _fail('Unsupported bundle manifest');
      }
      manifest = decoded;
      final records = manifest['files'];
      if (records is! List || records.length != files.length - 1) {
        _fail('Incomplete manifest');
      }
      final seen = <String>{};
      for (final record in records) {
        if (record is! Map || record['path'] is! String) {
          _fail('Invalid manifest entry');
        }
        final path = record['path'] as String;
        final content = files[path];
        final hash = record['sha256'];
        if (path == 'manifest.json' ||
            !seen.add(path) ||
            content == null ||
            record['size'] is! int ||
            record['size'] != content.length ||
            hash is! String ||
            !RegExp(r'^[a-fA-F0-9]{64}$').hasMatch(hash) ||
            sha256.convert(content).toString() != hash.toLowerCase()) {
          _fail('Manifest SHA-256 or size mismatch');
        }
      }
      final rawWarnings = manifest['warnings'];
      if (rawWarnings != null) {
        if (rawWarnings is! List ||
            rawWarnings.length > 64 ||
            rawWarnings.any(
              (value) =>
                  value is! String ||
                  !RegExp(r'^[A-Z][A-Z0-9_]{0,79}$').hasMatch(value),
            )) {
          _fail('Invalid bundle warnings');
        }
        warnings.addAll(rawWarnings.cast<String>());
      }
    } else {
      // Also accepts the workbench's existing BIN + preview export format.
      warnings.add('BUNDLE_MANIFEST_MISSING');
    }
    final previews = <WatchfacePreview>[];
    for (final entry in files.entries) {
      if (RegExp(r'\.(png|jpe?g)$', caseSensitive: false).hasMatch(entry.key)) {
        previews.add(
          WatchfacePreview(name: entry.key.split('/').last, bytes: entry.value),
        );
      }
    }
    final snapshot = WatchfaceDocument.importBin(
      bytes: files[bin]!,
      fileName: bin,
    );
    // A workbench rename lives in its exported BIN filename, not opaque bytes.
    String? suggestedName = manifest == null
        ? bin
              .split('/')
              .last
              .replaceFirst(RegExp(r'\.bin$', caseSensitive: false), '')
        : null;
    final metadata = manifest?['metadata'];
    if (metadata is Map &&
        const ['xml', 'bin'].contains(metadata['nameSource']) &&
        snapshot.id != null &&
        metadata['id'] == snapshot.id &&
        metadata['name'] is String) {
      suggestedName = metadata['name'] as String;
    } else if (files.keys.any((name) => name.endsWith('.xml'))) {
      // XML is retained for provenance; BIN parsing remains the authority for ID.
      warnings.add('XML_METADATA_NOT_APPLIED');
    }
    return WatchfaceDocument.importBin(
      bytes: files[bin]!,
      fileName: bin,
      suggestedName: suggestedName,
      previews: previews,
      warnings: warnings,
    );
  }
}

Never _fail(String reason) => throw FormatException(reason);

void _safePath(String name) {
  if (name.isEmpty ||
      utf8.encode(name).length > 1024 ||
      name.startsWith('/') ||
      name.contains('\\') ||
      name.contains(':') ||
      RegExp(r'[\x00-\x1f\x7f]').hasMatch(name)) {
    _fail('Unsafe ZIP path');
  }
  final parts = name.endsWith('/')
      ? name.substring(0, name.length - 1).split('/')
      : name.split('/');
  if (parts.any((part) => part.isEmpty || part == '.' || part == '..')) {
    _fail('Unsafe ZIP path');
  }
}

void _preflight(Uint8List bytes) {
  final data = ByteData.sublistView(bytes);
  int u16(int offset) => data.getUint16(offset, Endian.little);
  int u32(int offset) => data.getUint32(offset, Endian.little);
  var end = -1;
  for (var i = bytes.length - 22; i >= 0 && i >= bytes.length - 65557; i--) {
    if (u32(i) == 0x06054b50 && i + 22 + u16(i + 20) == bytes.length) {
      end = i;
      break;
    }
  }
  if (end < 0 ||
      u16(end + 4) != 0 ||
      u16(end + 6) != 0 ||
      u16(end + 8) != u16(end + 10)) {
    _fail('Invalid ZIP directory');
  }
  final count = u16(end + 10);
  final centralSize = u32(end + 12);
  var offset = u32(end + 16);
  if (count == 0 || count > _maxEntries || offset + centralSize != end) {
    _fail('ZIP entry limit or layout');
  }
  final names = <String>{};
  final offsets = <int>{};
  var total = 0;
  for (var i = 0; i < count; i++) {
    if (offset + 46 > end || u32(offset) != 0x02014b50) {
      _fail('Invalid ZIP header');
    }
    final flags = u16(offset + 8);
    final method = u16(offset + 10);
    final compressed = u32(offset + 20);
    final size = u32(offset + 24);
    final length = u16(offset + 28);
    final next = offset + 46 + length + u16(offset + 30) + u16(offset + 32);
    final local = u32(offset + 42);
    if (next > end ||
        u16(offset + 34) != 0 ||
        (flags & 0x41) != 0 ||
        (method != 0 && method != 8) ||
        size > maxWatchfaceImportBytes ||
        compressed > bytes.length ||
        (u32(offset + 38) >> 16 & 0xf000) == 0xa000) {
      _fail('Unsupported or oversized ZIP entry');
    }
    final name = utf8.decode(bytes.sublist(offset + 46, offset + 46 + length));
    _safePath(name);
    final key = name.endsWith('/') ? name.substring(0, name.length - 1) : name;
    if (!names.add(key.toLowerCase()) || !offsets.add(local)) {
      _fail('Duplicate ZIP entry');
    }
    final type = u32(offset + 38) >> 16 & 0xf000;
    if (type != 0 && type != 0x8000 && type != 0x4000) {
      _fail('Non-regular ZIP entry');
    }
    if (name.endsWith('/') && (size != 0 || compressed != 0)) {
      _fail('Invalid ZIP directory entry');
    }
    if (local + 30 > u32(end + 16) ||
        u32(local) != 0x04034b50 ||
        u16(local + 6) != flags ||
        u16(local + 8) != method) {
      _fail('Invalid local ZIP header');
    }
    final localNameEnd = local + 30 + u16(local + 26);
    final contentStart = localNameEnd + u16(local + 28);
    if (contentStart + compressed > u32(end + 16) ||
        utf8.decode(bytes.sublist(local + 30, localNameEnd)) != name) {
      _fail('ZIP header mismatch');
    }
    total += size;
    if (total > maxWatchfaceBundleBytes) _fail('Bundle expands beyond limit');
    offset = next;
  }
  if (offset != end) _fail('Invalid ZIP directory size');
}

class _BoundedOutput extends OutputMemoryStream {
  _BoundedOutput(this.maximum) : super(size: 1024);
  final int maximum;
  void _check(int count) {
    if (count < 0 || length + count > maximum) {
      _fail('ZIP expands beyond declared size');
    }
  }

  @override
  void writeByte(int value) {
    _check(1);
    super.writeByte(value);
  }

  @override
  void writeBytes(List<int> bytes, {int? length}) {
    _check(length ?? bytes.length);
    super.writeBytes(bytes, length: length);
  }

  @override
  void writeStream(InputStream stream) {
    _check(stream.length);
    super.writeStream(stream);
  }

  @override
  void writeBackReference(int distance, int count) {
    _check(count);
    super.writeBackReference(distance, count);
  }
}
