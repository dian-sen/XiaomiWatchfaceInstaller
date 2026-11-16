import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:oronbox/src/app/generated/app_localizations.dart';
import 'package:oronbox/src/app/widgets/smooth_linear_progress_indicator.dart';
import 'package:oronbox/src/features/resources/controllers/watchface_workbench_controller.dart';
import 'package:oronbox/src/features/resources/services/watchface_cache_import_service.dart';
import 'package:oronbox/src/features/resources/services/watchface_document.dart';
import 'package:oronbox/src/features/resources/services/watchface_workbench_service.dart';
import 'package:oronbox/src/features/resources/widgets/watchface_workbench_status.dart';

class _Service extends WatchfaceWorkbenchService {
  Future<WatchfaceCacheScan>? pending;
  Object? scanError;

  @override
  Future<WatchfaceCacheScan> scanCache() async {
    if (scanError != null) throw scanError!;
    return pending == null
        ? const WatchfaceCacheScan(files: [], truncated: false)
        : await pending!;
  }

  @override
  Future<void> dispose() async {}
}

Widget _wrap(WatchfaceWorkbenchController controller) => MaterialApp(
  locale: const Locale('en'),
  localizationsDelegates: AppLocalizations.localizationsDelegates,
  supportedLocales: AppLocalizations.supportedLocales,
  home: Scaffold(
    body: ListenableBuilder(
      listenable: controller,
      builder: (context, _) => WatchfaceWorkbenchStatus(controller: controller),
    ),
  ),
);

void main() {
  for (final error in [
    PlatformException(code: 'ROOT_UNAVAILABLE'),
    PlatformException(
      code: 'WATCHFACE_CACHE_SCAN_FAILED',
      message: 'ROOT_DENIED',
    ),
    PlatformException(
      code: 'WATCHFACE_CACHE_SCAN_FAILED',
      details: const {'code': 'ROOT_UNAVAILABLE'},
    ),
  ]) {
    testWidgets('Root failure is actionable without exposing $error', (
      tester,
    ) async {
      final service = _Service()..scanError = error;
      final controller = WatchfaceWorkbenchController(service);
      addTearDown(controller.dispose);
      await controller.scanCache();
      await tester.pumpWidget(_wrap(controller));
      await tester.pumpAndSettle();

      expect(
        find.text(
          'Root access is unavailable\nGrant OronBox Root access, or import a local BIN',
        ),
        findsOneWidget,
      );
      expect(find.textContaining('ROOT_'), findsNothing);
      expect(find.textContaining('PlatformException'), findsNothing);
    });
  }

  testWidgets(
    'scan has a bounded session progress state and finishes cleanly',
    (tester) async {
      final scan = Completer<WatchfaceCacheScan>();
      final service = _Service()..pending = scan.future;
      final controller = WatchfaceWorkbenchController(service);
      addTearDown(controller.dispose);
      final scanning = controller.scanCache();
      await tester.pumpWidget(_wrap(controller));
      await tester.pump();

      expect(find.text('Scanning cache…'), findsOneWidget);
      expect(find.byType(SmoothLinearProgressIndicator), findsOneWidget);

      scan.complete(const WatchfaceCacheScan(files: [], truncated: false));
      await scanning;
      await tester.pumpAndSettle();

      expect(find.text('Scanning cache…'), findsNothing);
      expect(find.byType(SmoothLinearProgressIndicator), findsNothing);
    },
  );

  testWidgets('replacement BIN keeps a restricted new ID blocked', (
    tester,
  ) async {
    final bytes = Uint8List(0xa8)
      ..setAll(0, [0x5a, 0xa5, 0x34, 0x12])
      ..setAll(0x28, '120912345678'.codeUnits);
    final edited = WatchfaceDocument.importBin(
      bytes: bytes,
      fileName: 'restricted.bin',
    ).edit(name: 'Edited', id: '120998765432');
    final controller = WatchfaceWorkbenchController(_Service());
    addTearDown(controller.dispose);
    var installs = 0;
    await controller.install(edited, (_) async {
      installs++;
      return true;
    });
    await tester.pumpWidget(_wrap(controller));
    await tester.pumpAndSettle();

    final l10n = AppLocalizations.of(
      tester.element(find.byType(WatchfaceWorkbenchStatus)),
    )!;
    expect(find.text(l10n.watchfaceRestrictedMessage), findsOneWidget);
    expect(find.text(l10n.watchfaceWorkbenchEnqueued), findsNothing);
    expect(find.textContaining('120912345678'), findsNothing);
    expect(find.textContaining('987654321012'), findsNothing);
    expect(installs, 0);
  });

  testWidgets('unrecognized failure shows a short translated message', (
    tester,
  ) async {
    final service = _Service()
      ..scanError = StateError('internal path /data/private/example');
    final controller = WatchfaceWorkbenchController(service);
    addTearDown(controller.dispose);
    await controller.scanCache();
    await tester.pumpWidget(_wrap(controller));
    await tester.pumpAndSettle();

    expect(find.textContaining('Could not scan the cache'), findsOneWidget);
    expect(find.textContaining('/data/'), findsNothing);
  });
}
