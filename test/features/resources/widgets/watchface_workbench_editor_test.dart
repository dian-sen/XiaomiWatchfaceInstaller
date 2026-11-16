import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:oronbox/src/app/generated/app_localizations.dart';
import 'package:oronbox/src/features/resources/controllers/watchface_workbench_controller.dart';
import 'package:oronbox/src/features/resources/services/watchface_document.dart';
import 'package:oronbox/src/features/resources/services/watchface_workbench_service.dart';
import 'package:oronbox/src/features/resources/widgets/watchface_workbench_editor.dart';

final _nameField = find.byKey(const ValueKey('watchface-name'));
final _idField = find.byKey(const ValueKey('watchface-id'));
final _binButton = find.byKey(const ValueKey('watchface-export-bin'));
final _bundleButton = find.byKey(const ValueKey('watchface-export-bundle'));
final _installButton = find.byKey(const ValueKey('watchface-install'));
final _randomButton = find.byKey(const ValueKey('watchface-random-id'));

WatchfaceDocument _document({
  String name = 'Original',
  String id = '998877665544',
  bool preview = false,
}) {
  final bytes = Uint8List(0xc0);
  bytes.setAll(0, [0x5a, 0xa5, 0x34, 0x12]);
  bytes.setAll(0x28, ascii.encode(id));
  return WatchfaceDocument.importBin(
    bytes: bytes,
    fileName: '$name.bin',
    previews: [
      if (preview)
        WatchfacePreview(
          name: 'preview.png',
          bytes: base64Decode(
            'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==',
          ),
        ),
    ],
  );
}

class _Service extends WatchfaceWorkbenchService {
  Future<WatchfaceExportResult>? pending;
  WatchfaceDocument? exported;
  int exports = 0;

  @override
  Future<WatchfaceExportResult> exportBin(WatchfaceDocument doc) async {
    exports++;
    exported = doc;
    return pending == null ? WatchfaceExportResult.saved : await pending!;
  }

  @override
  Future<void> dispose() async {}
}

Widget _wrap(Widget child) => MaterialApp(
  locale: const Locale('en'),
  localizationsDelegates: AppLocalizations.localizationsDelegates,
  supportedLocales: AppLocalizations.supportedLocales,
  home: Scaffold(body: SingleChildScrollView(child: child)),
);

WatchfaceWorkbenchEditor _editor({
  required WatchfaceDocument document,
  bool busy = false,
  bool canInstall = false,
  ValueChanged<WatchfaceDocument>? onBin,
  ValueChanged<WatchfaceDocument>? onBundle,
  void Function(WatchfaceDocument, int)? onPreview,
  ValueChanged<WatchfaceDocument>? onInstall,
}) => WatchfaceWorkbenchEditor(
  document: document,
  busy: busy,
  canInstall: canInstall,
  onExportBin: onBin ?? (_) {},
  onExportBundle: onBundle ?? (_) {},
  onExportPreview: onPreview ?? (_, _) {},
  onInstall: onInstall ?? (_) {},
);

Future<void> _tap(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

String _fieldText(WidgetTester tester, Finder finder) =>
    tester.widget<TextFormField>(finder).controller!.text;

void main() {
  testWidgets('random ID button fills exactly 12 digits', (tester) async {
    await tester.pumpWidget(_wrap(_editor(document: _document())));
    await tester.pumpAndSettle();

    await _tap(tester, _randomButton);

    expect(_fieldText(tester, _idField), matches(RegExp(r'^[1-9][0-9]{11}$')));
    expect(_fieldText(tester, _idField), isNot(startsWith('1209')));
  });

  testWidgets('rename and manual 9-digit ID export an immutable snapshot', (
    tester,
  ) async {
    final original = _document();
    WatchfaceDocument? exported;
    await tester.pumpWidget(
      _wrap(_editor(document: original, onBin: (doc) => exported = doc)),
    );
    await tester.pumpAndSettle();

    await tester.enterText(_nameField, 'Renamed');
    await tester.enterText(_idField, '987654321');
    await _tap(tester, _binButton);

    expect(exported!.name, 'Renamed');
    expect(exported!.binFileName, 'Renamed.bin');
    expect(exported!.id, '987654321');
    expect(original.name, 'Original');
    expect(original.id, '998877665544');
  });

  testWidgets('incomplete ID shows validation and blocks BIN export', (
    tester,
  ) async {
    var exports = 0;
    await tester.pumpWidget(
      _wrap(_editor(document: _document(), onBin: (_) => exports++)),
    );
    await tester.pumpAndSettle();

    await tester.enterText(_idField, '12345');
    await _tap(tester, _binButton);

    expect(exports, 0);
    expect(find.text('Enter 9 or 12 digits, not all zeros'), findsOneWidget);
  });

  testWidgets('cancelled save keeps document, name and ID for retry', (
    tester,
  ) async {
    final original = _document();
    final save = Completer<WatchfaceExportResult>();
    final service = _Service()..pending = save.future;
    final controller = WatchfaceWorkbenchController(service)
      ..document = original;
    addTearDown(controller.dispose);
    await tester.pumpWidget(
      _wrap(
        ListenableBuilder(
          listenable: controller,
          builder: (context, _) => _editor(
            document: controller.document!,
            busy: controller.busy,
            onBin: (edited) => controller.exportDocument(
              edited,
              kind: WatchfaceExportKind.bin,
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(_nameField, 'Keep my name');
    await tester.enterText(_idField, '987654321012');
    await _tap(tester, _binButton);

    expect(tester.widget<FilledButton>(_binButton).onPressed, isNull);
    expect(service.exports, 1);
    expect(service.exported!.binFileName, 'Keep my name.bin');
    expect(controller.document, same(original));

    save.complete(WatchfaceExportResult.cancelled);
    await tester.pumpAndSettle();

    expect(controller.document, same(original));
    expect(_fieldText(tester, _nameField), 'Keep my name');
    expect(_fieldText(tester, _idField), '987654321012');
    expect(tester.widget<FilledButton>(_binButton).onPressed, isNotNull);
  });

  testWidgets('only successful save replaces the displayed original ID', (
    tester,
  ) async {
    final original = _document();
    final service = _Service();
    final controller = WatchfaceWorkbenchController(service)
      ..document = original;
    addTearDown(controller.dispose);
    await tester.pumpWidget(
      _wrap(
        ListenableBuilder(
          listenable: controller,
          builder: (context, _) => _editor(
            document: controller.document!,
            busy: controller.busy,
            onBin: (edited) => controller.exportDocument(
              edited,
              kind: WatchfaceExportKind.bin,
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(_nameField, 'Saved');
    await tester.enterText(_idField, '987654321');
    await _tap(tester, _binButton);

    expect(controller.document, same(service.exported));
    expect(_fieldText(tester, _nameField), 'Saved');
    expect(_fieldText(tester, _idField), '987654321');
    expect(find.text('Original ID: 987654321'), findsOneWidget);
  });

  testWidgets('changing the selected file resets values and validation', (
    tester,
  ) async {
    var document = _document();
    late StateSetter update;
    await tester.pumpWidget(
      _wrap(
        StatefulBuilder(
          builder: (context, setState) {
            update = setState;
            return _editor(document: document);
          },
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(_nameField, 'Unsaved');
    await tester.enterText(_idField, '12');
    await _tap(tester, _binButton);
    expect(find.text('Enter 9 or 12 digits, not all zeros'), findsOneWidget);

    update(() => document = _document(name: 'Next', id: '987654321'));
    await tester.pumpAndSettle();

    expect(_fieldText(tester, _nameField), 'Next');
    expect(_fieldText(tester, _idField), '987654321');
    expect(find.text('Enter 9 or 12 digits, not all zeros'), findsNothing);
  });

  testWidgets('read-only ID still allows rename, BIN and ZIP export', (
    tester,
  ) async {
    final document = _document(id: 'unrecognized');
    WatchfaceDocument? exported;
    await tester.pumpWidget(
      _wrap(_editor(document: document, onBundle: (doc) => exported = doc)),
    );
    await tester.pumpAndSettle();

    expect(tester.widget<TextFormField>(_idField).enabled, isFalse);
    expect(tester.widget<TextButton>(_randomButton).onPressed, isNull);
    expect(tester.widget<FilledButton>(_binButton).onPressed, isNotNull);
    await tester.enterText(_nameField, 'Readable');
    await _tap(tester, _bundleButton);

    expect(exported!.name, 'Readable');
    expect(exported!.bytes, orderedEquals(document.bytes));
    expect(exported!.id, isNull);
  });

  testWidgets('renders real image bytes and exports the selected image', (
    tester,
  ) async {
    final document = _document(preview: true);
    int? exportedIndex;
    await tester.pumpWidget(
      _wrap(
        _editor(
          document: document,
          onPreview: (doc, index) {
            expect(doc.name, 'Renamed');
            expect(doc.id, document.id);
            exportedIndex = index;
          },
        ),
      ),
    );
    await tester.pumpAndSettle();
    final image = tester.widget<Image>(
      find.byKey(const ValueKey('watchface-preview-0')),
    );

    expect(image.image, isA<MemoryImage>());
    expect(
      (image.image as MemoryImage).bytes,
      orderedEquals(document.previews.single.bytes),
    );
    await tester.enterText(_idField, '12');
    await tester.enterText(_nameField, 'Renamed');
    await _tap(
      tester,
      find.byKey(const ValueKey('watchface-export-preview-0')),
    );
    expect(exportedIndex, 0);
  });

  testWidgets('missing preview explains BIN export remains available', (
    tester,
  ) async {
    await tester.pumpWidget(_wrap(_editor(document: _document())));
    await tester.pumpAndSettle();

    expect(
      find.text('No preview image found\nYou can still export the BIN'),
      findsOneWidget,
    );
    expect(tester.widget<FilledButton>(_binButton).onPressed, isNotNull);
  });

  testWidgets(
    'offline export stays enabled and install follows device readiness',
    (tester) async {
      final document = _document();
      await tester.pumpWidget(_wrap(_editor(document: document)));
      await tester.pumpAndSettle();

      expect(tester.widget<FilledButton>(_binButton).onPressed, isNotNull);
      expect(tester.widget<OutlinedButton>(_bundleButton).onPressed, isNotNull);
      expect(tester.widget<OutlinedButton>(_installButton).onPressed, isNull);

      await tester.pumpWidget(
        _wrap(_editor(document: document, canInstall: true)),
      );
      await tester.pumpAndSettle();
      expect(
        tester.widget<OutlinedButton>(_installButton).onPressed,
        isNotNull,
      );
    },
  );

  testWidgets('busy disables form, random ID, exports and install', (
    tester,
  ) async {
    await tester.pumpWidget(
      _wrap(_editor(document: _document(), busy: true, canInstall: true)),
    );
    await tester.pumpAndSettle();

    expect(tester.widget<TextFormField>(_nameField).enabled, isFalse);
    expect(tester.widget<TextFormField>(_idField).enabled, isFalse);
    expect(tester.widget<TextButton>(_randomButton).onPressed, isNull);
    expect(tester.widget<FilledButton>(_binButton).onPressed, isNull);
    expect(tester.widget<OutlinedButton>(_bundleButton).onPressed, isNull);
    expect(tester.widget<OutlinedButton>(_installButton).onPressed, isNull);
  });
}
