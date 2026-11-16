import 'package:oronbox/src/core/constants/app_constants.dart';

String get initialAppLocation =>
    AppConstants.personalBuild ? '/studio' : '/resources';

/// Keep local device tools outside the community route boundary.
String? personalBuildRedirect(Uri uri) {
  if (!AppConstants.personalBuild) return null;
  final path = uri.path;
  for (final root in const [
    '/resources',
    '/inbox',
    '/plugins',
    '/settings/bandbbs',
    '/settings/feedback',
    '/settings/clean-mode',
  ]) {
    if (path == root || path.startsWith('$root/')) return '/devices';
  }
  if (path == '/home' ||
      (uri.host == 'oauth' && path == '/bandbbs') ||
      uri.queryParameters['oauth'] == 'bandbbs') {
    return '/devices';
  }
  return null;
}
