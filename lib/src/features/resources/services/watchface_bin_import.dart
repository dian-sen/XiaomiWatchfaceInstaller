import 'dart:math' as math;
import 'dart:typed_data';

import 'package:cross_file/cross_file.dart';
import 'package:oronbox/src/device/core/watchface_install_policy.dart';

import 'watchface_document.dart';

/// null cancels the import, false keeps the BIN, true generates a new ID.
typedef WatchfaceRandomIdRequest = Future<bool?> Function(String? currentId);
typedef WatchfaceBinPreparation = Future<Uint8List?> Function(
  Uint8List bytes,
  String fileName,
);

class WatchfaceImportCancelled implements Exception {
  const WatchfaceImportCancelled();
}

bool isVelaWatchfaceBin(List<int> bytes) =>
    bytes.length >= 4 &&
    bytes[0] == 0x5a &&
    bytes[1] == 0xa5 &&
    bytes[2] == 0x34 &&
    bytes[3] == 0x12;

/// Produces the replacement BIN before callers run recognition or policy checks.
/// The original file is never written; the imported snapshot is replaced.
Future<Uint8List?> prepareWatchfaceBinBytes(
  Uint8List bytes,
  String fileName, {
  required WatchfaceRandomIdRequest requestRandomId,
  String Function()? generateId,
}) async {
  if (!isVelaWatchfaceBin(bytes)) return bytes;
  final change = await requestRandomId(extractVelaWatchfaceId(bytes));
  if (change == null) return null;
  if (!change) return bytes;
  final document = WatchfaceDocument.importBin(
    bytes: bytes,
    fileName: fileName,
  );
  final id = generateId?.call() ?? _randomReplacementId(document.id);
  if (id == document.id) {
    throw const FormatException('Random watchface ID did not change');
  }
  if (!isValidVelaWatchfaceId(id) || isRestrictedWatchfaceId(id)) {
    throw const FormatException('Invalid random watchface ID');
  }
  return document.edit(name: document.name, id: id).bytes;
}

String _randomReplacementId(String? current) {
  if (current == null) {
    throw const FormatException('Watchface ID cannot be safely edited');
  }
  final random = math.Random.secure();
  while (true) {
    final candidate = generateRandomWatchfaceId(random: random);
    final id = current.length == 9 ? candidate.substring(0, 9) : candidate;
    if (id != current && !isRestrictedWatchfaceId(id)) return id;
  }
}

/// Non-watchface packages retain their existing streaming install path.
Future<XFile?> prepareWatchfaceBinFile(
  XFile file, {
  required WatchfaceRandomIdRequest requestRandomId,
  String Function()? generateId,
  void Function(Uint8List bytes)? onOriginal,
}) async {
  final length = await file.length();
  final header = await file
      .openRead(0, math.min(length, 4))
      .fold<List<int>>(<int>[], (bytes, chunk) => bytes..addAll(chunk));
  if (!isVelaWatchfaceBin(header)) return file;
  if (length > maxWatchfaceImportBytes) {
    throw const FormatException('Watchface file exceeds the import limit');
  }
  final buffer = BytesBuilder(copy: false);
  await for (final chunk in file.openRead()) {
    if (buffer.length + chunk.length > maxWatchfaceImportBytes) {
      throw const FormatException('Watchface file exceeds the import limit');
    }
    buffer.add(chunk);
  }
  final bytes = buffer.takeBytes();
  onOriginal?.call(bytes);
  final prepared = await prepareWatchfaceBinBytes(
    bytes,
    file.name,
    requestRandomId: requestRandomId,
    generateId: generateId,
  );
  if (prepared == null) return null;
  return XFile.fromData(prepared, name: file.name);
}
