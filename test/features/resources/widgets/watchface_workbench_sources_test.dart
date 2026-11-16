import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:oronbox/src/app/generated/app_localizations.dart';
import 'package:oronbox/src/features/resources/services/watchface_cache_import_service.dart';
import 'package:oronbox/src/features/resources/widgets/watchface_workbench_sources.dart';
import 'package:segmented_list/segmented_list.dart';

Widget _wrap(Widget child) => MaterialApp(
  locale: const Locale('en'),
  localizationsDelegates: AppLocalizations.localizationsDelegates,
  supportedLocales: AppLocalizations.supportedLocales,
  home: Scaffold(body: SingleChildScrollView(child: child)),
);

WatchfaceCacheFile _file(String token, DateTime modified) => WatchfaceCacheFile(
  token: token,
  name: 'resource.bin',
  location: 'watchfaces/$token',
  size: 2048,
  modifiedAt: modified,
);

void main() {
  testWidgets('local import is available without scan or device requirements', (
    tester,
  ) async {
    var imports = 0;
    var scans = 0;
    await tester.pumpWidget(
      _wrap(
        WatchfaceWorkbenchSources(
          busy: false,
          cacheSupported: false,
          onImport: () => imports++,
          onScan: () => scans++,
          onSelect: (_) {},
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('watchface-scan')), findsNothing);
    expect(find.text('Cache scanning is available on Android'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('watchface-import')));
    expect(imports, 1);
    expect(scans, 0);
  });

  testWidgets('cache button explains Root access must be granted to OronBox', (
    tester,
  ) async {
    var scans = 0;
    await tester.pumpWidget(
      _wrap(
        WatchfaceWorkbenchSources(
          busy: false,
          cacheSupported: true,
          onImport: () {},
          onScan: () => scans++,
          onSelect: (_) {},
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.text('Grant OronBox Root access to read the cache'),
      findsOneWidget,
    );
    await tester.tap(find.byKey(const ValueKey('watchface-scan')));
    expect(scans, 1);
  });

  testWidgets('candidates show relative location, size and localized date', (
    tester,
  ) async {
    final old = _file('old', DateTime(2026, 1, 1, 8, 30));
    final recent = _file('recent', DateTime(2026, 2, 2, 9, 15));
    WatchfaceCacheFile? selected;
    await tester.pumpWidget(
      _wrap(
        WatchfaceWorkbenchSources(
          busy: false,
          cacheSupported: true,
          onImport: () {},
          onScan: () {},
          onSelect: (file) => selected = file,
          selectedToken: recent.token,
          scan: WatchfaceCacheScan(files: [old, recent], truncated: false),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Cached watchfaces (2)'), findsOneWidget);
    expect(find.textContaining('2.0 KB · Modified'), findsNWidgets(2));
    expect(find.textContaining('watchfaces/recent'), findsOneWidget);
    expect(find.textContaining('2/2/2026'), findsOneWidget);
    expect(
      tester
          .getTopLeft(find.byKey(const ValueKey('watchface-cache-recent')))
          .dy,
      lessThan(
        tester.getTopLeft(find.byKey(const ValueKey('watchface-cache-old'))).dy,
      ),
    );
    expect(find.byIcon(Icons.check_circle_outline), findsOneWidget);
    final candidate = find.byKey(const ValueKey('watchface-cache-recent'));
    await tester.ensureVisible(candidate);
    await tester.tap(candidate);
    expect(selected, same(recent));
  });

  testWidgets(
    'missing directories and truncation have distinct visible states',
    (tester) async {
      await tester.pumpWidget(
        _wrap(
          WatchfaceWorkbenchSources(
            busy: false,
            cacheSupported: true,
            onImport: () {},
            onScan: () {},
            onSelect: (_) {},
            scan: const WatchfaceCacheScan(
              files: [],
              truncated: true,
              diagnostics: [
                WatchfaceCacheDiagnostic(
                  code: 'MISSING',
                  path: 'watchfaces',
                  reason: 'root',
                ),
                WatchfaceCacheDiagnostic(
                  code: 'DENIED',
                  path: 'cache',
                  reason: 'root',
                ),
              ],
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.textContaining('Some cache folders are missing'),
        findsOneWidget,
      );
      expect(
        find.textContaining('Some cache folders cannot be read'),
        findsOneWidget,
      );
      expect(find.textContaining('Scan limit reached'), findsOneWidget);
      expect(find.text('No cached BIN files found'), findsOneWidget);
    },
  );

  testWidgets('busy disables both sources and cache candidates', (
    tester,
  ) async {
    final file = _file('one', DateTime(2026));
    await tester.pumpWidget(
      _wrap(
        WatchfaceWorkbenchSources(
          busy: true,
          cacheSupported: true,
          onImport: () => fail('busy import'),
          onScan: () => fail('busy scan'),
          onSelect: (_) => fail('busy selection'),
          scan: WatchfaceCacheScan(files: [file], truncated: false),
        ),
      ),
    );
    await tester.pumpAndSettle();

    for (final tile in tester.widgetList<SegmentedTile>(
      find.byWidgetPredicate((widget) => widget is SegmentedTile),
    )) {
      expect(tile.enabled, isFalse);
    }
    await tester.tap(find.byKey(const ValueKey('watchface-import')));
    await tester.tap(find.byKey(const ValueKey('watchface-scan')));
  });
}
