import 'dart:io';

import 'package:cross_file/cross_file.dart';
import 'package:crypto/crypto.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:oronbox/src/core/network/dio_provider.dart';
import 'package:oronbox/src/core/storage/managed_temporary_files.dart';

import 'firmware_catalog.dart';

const maxFirmwareDownloadBytes = 256 * 1024 * 1024;

bool isFirmwareArchiveDownload(Uri uri) =>
    uri.scheme == 'https' &&
    uri.host.toLowerCase() == 'github.com' &&
    !uri.hasPort &&
    uri.userInfo.isEmpty &&
    uri.pathSegments.length >= 6 &&
    uri.pathSegments[0].toLowerCase() == 'astralsightstudios' &&
    uri.pathSegments[1].toLowerCase() == 'miwearfirmwarearchives' &&
    uri.pathSegments[2] == 'releases' &&
    uri.pathSegments[3] == 'download';

/// Downloads only a full firmware asset. The leased file is not handed to an
/// installer until the stream, expected length and available digest all pass.
class FirmwareDownloadService {
  FirmwareDownloadService(this.dio);
  final Dio dio;

  Future<String> downloadFull(
    FirmwarePackage package, {
    required void Function(double progress) onProgress,
    CancelToken? cancelToken,
  }) async {
    if (package.isIncremental ||
        !isFirmwareArchiveDownload(package.downloadUrl) ||
        !package.fileName.toLowerCase().contains('_full_') ||
        package.fileName.contains('/') ||
        package.fileName.contains('\\') ||
        package.downloadUrl.pathSegments.last != package.fileName ||
        (package.size != null &&
            (package.size! <= 0 || package.size! > maxFirmwareDownloadBytes))) {
      throw const FormatException('Invalid full firmware package');
    }
    final expectedDigest = package.sha256?.toLowerCase();
    if (expectedDigest != null &&
        !RegExp(r'^[a-f0-9]{64}$').hasMatch(expectedDigest)) {
      throw const FormatException('Invalid firmware digest');
    }
    onProgress(0);
    return ManagedTemporaryFiles.create(
      TemporaryFilePurpose.downloads,
      package.fileName,
      (path) async {
        final response = await dio.get<ResponseBody>(
          package.downloadUrl.toString(),
          options: Options(
            responseType: ResponseType.stream,
            receiveTimeout: const Duration(minutes: 3),
          ),
          cancelToken: cancelToken,
        );
        final body = response.data;
        if (body == null) {
          throw const FormatException('Empty firmware response');
        }
        final declared = int.tryParse(
          response.headers.value(Headers.contentLengthHeader) ?? '',
        );
        var received = 0;
        final output = await File(path).open(mode: FileMode.write);
        try {
          await for (final chunk in body.stream) {
            received += chunk.length;
            if (received > maxFirmwareDownloadBytes ||
                (package.size != null && received > package.size!)) {
              throw const FormatException(
                'Firmware download exceeds expected size',
              );
            }
            await output.writeFrom(chunk);
            final total = package.size ?? declared;
            if (total != null && total > 0) {
              onProgress((received / total).clamp(0, 0.99));
            }
          }
          await output.flush();
        } finally {
          await output.close();
        }
        if (received == 0 ||
            (package.size != null && received != package.size) ||
            (declared != null && received != declared)) {
          throw const FormatException('Incomplete firmware package');
        }
        if (expectedDigest != null &&
            (await sha256.bind(File(path).openRead()).first).toString() !=
                expectedDigest) {
          throw const FormatException('Firmware SHA-256 mismatch');
        }
        if (cancelToken?.isCancelled == true) {
          throw cancelToken!.cancelError!;
        }
        onProgress(1);
      },
    );
  }

  XFile open(String path) => XFile(path);
  Future<void> release(String path) => ManagedTemporaryFiles.release(path);
}

final firmwareDownloadServiceProvider = Provider<FirmwareDownloadService>(
  (ref) => FirmwareDownloadService(ref.watch(appDioProvider)),
);
