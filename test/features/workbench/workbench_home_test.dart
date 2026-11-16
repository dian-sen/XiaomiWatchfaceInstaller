import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:oronbox/src/app/generated/app_localizations.dart';
import 'package:oronbox/src/app/theme/app_theme.dart';
import 'package:oronbox/src/features/devices/controllers/device_manager.dart';
import 'package:oronbox/src/features/workbench/pages/workbench_home_page.dart';

class _OfflineManager extends LocalDeviceManager {
  @override
  DeviceManagerState build() => const DeviceManagerState();
}

void main() {
  for (final dark in [false, true]) {
    testWidgets(
      'offline home exposes tools with ${dark ? 'dark' : 'light'} theme',
      (tester) async {
        tester.view.physicalSize = const Size(432, 1150);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        await tester.pumpWidget(
          ProviderScope(
            overrides: [
              deviceManagerProvider.overrideWith(_OfflineManager.new),
            ],
            child: MaterialApp(
              locale: const Locale('en'),
              theme: dark ? AppTheme.dark : AppTheme.light,
              localizationsDelegates: AppLocalizations.localizationsDelegates,
              supportedLocales: AppLocalizations.supportedLocales,
              home: const WorkbenchHomePage(),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.text('Watchface Studio'), findsOneWidget);
        expect(find.text('Watchface workbench'), findsOneWidget);
        expect(find.text('Device keys'), findsOneWidget);
        expect(find.text('Local installation'), findsOneWidget);
        expect(find.text('Xiaomi / Huami accounts'), findsOneWidget);
        expect(tester.takeException(), isNull);
      },
    );
  }
}
