import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:oronbox/src/app/router/personal_build_routes.dart';
import 'package:oronbox/src/core/constants/app_constants.dart';
import 'package:oronbox/src/core/network/app_http_transport.dart';
import 'package:oronbox/src/core/network/personal_build_interceptor.dart';
import 'package:oronbox/src/core/providers/app_settings_providers.dart';

void main() {
  test('routes use the selected profile and preserve local device tools', () {
    expect(initialAppLocation, '/studio');
    expect(personalBuildRedirect(Uri.parse('/studio')), isNull);
    expect(personalBuildRedirect(Uri.parse('/device-keys')), isNull);
    expect(personalBuildRedirect(Uri.parse('/settings/accounts')), isNull);
    expect(personalBuildRedirect(Uri.parse('/watchface-tools')), isNull);
    expect(personalBuildRedirect(Uri.parse('/devices/firmware')), isNull);
    expect(personalBuildRedirect(Uri.parse('/settings/about')), isNull);
    if (AppConstants.personalBuild) {
      expect(
        personalBuildRedirect(Uri.parse('/resources/detail/1')),
        '/devices',
      );
      expect(
        personalBuildRedirect(Uri.parse('/settings/feedback')),
        '/devices',
      );
    } else {
      expect(personalBuildRedirect(Uri.parse('/resources/detail/1')), isNull);
      expect(personalBuildRedirect(Uri.parse('/settings/feedback')), isNull);
    }
  });

  test('legacy preferences cannot enable personal community surfaces', () {
    const clean = CleanSettings();
    expect(clean.exploreEnabled, !AppConstants.personalBuild);
    expect(clean.homeRecommendedEnabled, !AppConstants.personalBuild);
    expect(clean.creatorEnabled, !AppConstants.personalBuild);
    expect(clean.inboxEnabled, !AppConstants.personalBuild);
    expect(clean.announcementsEnabled, !AppConstants.personalBuild);
    expect(clean.oronBoxSourceEnabled, !AppConstants.personalBuild);
    expect(clean.bandBbsSourceEnabled, !AppConstants.personalBuild);
    expect(clean.astroBoxSourceEnabled, !AppConstants.personalBuild);
    expect(clean.bandBbsLoginEnabled, !AppConstants.personalBuild);
    expect(clean.githubLoginEnabled, !AppConstants.personalBuild);
    expect(clean.pluginsEnabled, !AppConstants.personalBuild);
    expect(clean.huamiAppStoreSourceEnabled, !AppConstants.personalBuild);
  });

  test('network fence blocks only upstream community services', () {
    expect(
      PersonalBuildInterceptor.blocks(
        Uri.parse('https://ob-api.zxor.org/api/v1/me'),
      ),
      AppConstants.personalBuild,
    );
    expect(
      PersonalBuildInterceptor.blocks(Uri.parse('https://www.bandbbs.cn/api')),
      AppConstants.personalBuild,
    );
    expect(
      PersonalBuildInterceptor.blocks(
        Uri.parse(
          'https://api.github.com/repos/OronCanopy/OronBox/releases/latest',
        ),
      ),
      AppConstants.personalBuild,
    );
    expect(
      PersonalBuildInterceptor.blocks(
        Uri.parse(
          'https://api.github.com/repos/AstralSightStudios/MiWearFirmwareArchives/releases',
        ),
      ),
      isFalse,
    );
    expect(
      PersonalBuildInterceptor.blocks(
        Uri.parse(
          'https://raw.githubusercontent.com/AstralSightStudios/AstroBox-Repo/main/index.csv',
        ),
      ),
      AppConstants.personalBuild,
    );
    expect(
      PersonalBuildInterceptor.blocks(Uri.parse('https://ghfast.top')),
      isFalse,
    );
    expect(
      PersonalBuildInterceptor.blocks(
        Uri.parse(
          'https://ghfast.top/https://raw.githubusercontent.com/OronCanopy/OronBox/main/README.md',
        ),
      ),
      AppConstants.personalBuild,
    );
    expect(
      PersonalBuildInterceptor.blocks(
        Uri.parse('https://api.amazfit.com/account'),
      ),
      isFalse,
    );
    expect(
      PersonalBuildInterceptor.blocks(
        Uri.parse('https://api.github.com/repos/someone/plugin'),
      ),
      isFalse,
    );
  });

  test('shared transport never sends blocked community requests', () async {
    final dio = createAppHttpTransport();
    final adapter = _Adapter();
    dio.httpClientAdapter = adapter;
    try {
      if (AppConstants.personalBuild) {
        await expectLater(
          dio.get<Object?>('https://ob-api.zxor.org/api/v1/me'),
          throwsA(isA<DioException>()),
        );
        expect(adapter.requests, 0);
      } else {
        await dio.get<Object?>('https://ob-api.zxor.org/api/v1/me');
        expect(adapter.requests, 1);
      }
    } finally {
      dio.close(force: true);
    }
  });

  test(
    'shared HTTP transport preserves vendor requests in both profiles',
    () async {
      final dio = createAppHttpTransport(
        options: BaseOptions(baseUrl: 'https://api.amazfit.com'),
      );
      dio.httpClientAdapter = _Adapter();
      try {
        final response = await dio.get<Object?>('/account');
        expect(response.statusCode, 200);
      } finally {
        dio.close(force: true);
      }
    },
  );
}

class _Adapter implements HttpClientAdapter {
  int requests = 0;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests++;
    return ResponseBody.fromString('{}', 200);
  }

  @override
  void close({bool force = false}) {}
}
