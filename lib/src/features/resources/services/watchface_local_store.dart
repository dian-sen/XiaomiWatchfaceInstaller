import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:oronbox/src/core/storage/app_data_directory.dart';

import 'watchface_document.dart';

/// Private copies only. Externally picked files and official caches are never
/// owned by this store. Install tasks receive their own leased copy.
class WatchfaceLocalStore {
  WatchfaceLocalStore({this.bins, this.previews});

  final AppDataDirectory? bins;
  final AppDataDirectory? previews;

  Future<AppDataDirectory> get _binDirectory async =>
      bins ?? await openAppDataDirectory('watchfaces');
  Future<AppDataDirectory> get _previewDirectory async =>
      previews ?? await openAppDataDirectory('previews');

  /// Overwrite the app-owned imported BIN, then read that exact file back.
  Future<Uint8List> replaceImportedBin(
    Uint8List original,
    Uint8List replacement, {
    String? importKey,
  }) async {
    final directory = await _binDirectory;
    final key = importKey ?? sha256.convert(original).toString();
    if (!RegExp(r'^[a-f0-9]{64}$').hasMatch(key)) {
      throw const FormatException('Invalid watchface import key');
    }
    return directory.exclusive(() async {
      final path = 'imported/$key.bin';
      await directory.write(path, replacement);
      if (key != sha256.convert(replacement).toString()) {
        await directory.write(
          'edited/${sha256.convert(replacement)}.bin',
          replacement,
        );
      }
      final saved = await directory.read(path);
      if (saved == null ||
          sha256.convert(saved) != sha256.convert(replacement)) {
        throw StateError('Watchface BIN replacement failed');
      }
      return saved;
    });
  }

  Future<void> saveBin(Uint8List bytes, {required bool edited}) async {
    final directory = await _binDirectory;
    final key = sha256.convert(bytes).toString();
    await directory.exclusive(() async {
      await directory.write(
        '${edited ? 'edited' : 'imported'}/$key.bin',
        bytes,
      );
    });
  }

  Future<void> saveDocument(
    WatchfaceDocument document, {
    bool includeBin = true,
  }) async {
    if (includeBin) {
      await saveBin(document.bytes, edited: document.id != document.originalId);
    }
    final directory = await _previewDirectory;
    await directory.exclusive(() async {
      for (final preview in document.previews) {
        final key = sha256.convert(preview.bytes).toString();
        final suffix = preview.mimeType == 'image/png' ? 'png' : 'jpg';
        await directory.write('$key.$suffix', preview.bytes);
      }
    });
  }

  Future<void> clear({bool bins = true, bool previews = true}) async {
    // Same cross-engine file locks as writers. Queued installs live under the
    // managed temporary namespace and cannot be removed by this operation.
    for (final directory in [
      if (bins) await _binDirectory,
      if (previews) await _previewDirectory,
    ]) {
      await directory.exclusive(() async {
        for (final file in await directory.files(includeTemporary: true)) {
          await directory.remove(file.path);
        }
      });
    }
  }

  Future<int> bytes({bool bins = true, bool previews = true}) async {
    var total = 0;
    for (final directory in [
      if (bins) await _binDirectory,
      if (previews) await _previewDirectory,
    ]) {
      total += await directory.exclusive(
        () async =>
            (await directory.files(includeTemporary: true))
                .fold<int>(0, (sum, file) => sum + file.bytes),
      );
    }
    return total;
  }
}
