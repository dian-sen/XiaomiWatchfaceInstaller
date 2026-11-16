import 'package:dio/dio.dart';
import 'package:oronbox/src/core/constants/app_constants.dart';
import 'package:oronbox/src/core/constants/oronbox_server.dart';

/// A narrow backstop for community callers, not a general offline mode.
class PersonalBuildInterceptor extends Interceptor {
  static bool blocks(Uri uri) {
    if (!AppConstants.personalBuild) return false;
    final host = uri.host.toLowerCase().replaceFirst(RegExp(r'\.$'), '');
    if (host == Uri.parse(oronBoxServerBaseUrl).host.toLowerCase() ||
        const {
          'ob-api.zxor.org',
          'oronbox.zxor.org',
          'bandbbs.cn',
          'www.bandbbs.cn',
          'api.bandbbs.cn',
        }.contains(host)) {
      return true;
    }
    // Match repositories, never all of GitHub: vendor assets and arbitrary
    // user networking must remain available. CDN wrapping is checked as well.
    if (host == 'ghfast.top' || host == 'gh-proxy.com') {
      if (uri.path.length < 2) return false;
      final origin = Uri.tryParse(uri.path.substring(1));
      return origin != null && origin.hasAuthority && blocks(origin);
    }
    final segments = uri.pathSegments
        .map((part) => part.toLowerCase())
        .toList();
    final offset =
        host == 'api.github.com' &&
            segments.isNotEmpty &&
            segments.first == 'repos'
        ? 1
        : 0;
    if (!const {
          'api.github.com',
          'github.com',
          'raw.githubusercontent.com',
        }.contains(host) ||
        segments.length < offset + 2) {
      return false;
    }
    final repository = '${segments[offset]}/${segments[offset + 1]}';
    return repository == 'oroncanopy/oronbox' ||
        repository == 'astralsightstudios/astrobox-repo' ||
        repository == 'astralsightstudios/astrobox-plugin-repo' ||
        repository == 'astralsightstudios/miwearfirmwarearchives';
  }

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    if (blocks(options.uri)) {
      handler.reject(
        DioException(
          requestOptions: options,
          type: DioExceptionType.cancel,
          error: const CommunityServiceDisabled(),
        ),
      );
      return;
    }
    handler.next(options);
  }
}

class CommunityServiceDisabled implements Exception {
  const CommunityServiceDisabled();

  @override
  String toString() => 'community_service_disabled';
}
