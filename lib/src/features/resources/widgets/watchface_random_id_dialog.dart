import 'package:material_ui/material_ui.dart';
import 'package:oronbox/src/app/generated/app_localizations.dart';

/// A cancelled dialog aborts the import; keeping the ID is an explicit choice.
Future<bool?> requestWatchfaceRandomId(
  BuildContext context,
  String? currentId,
) {
  final l10n = AppLocalizations.of(context)!;
  return showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(l10n.watchfaceImportIdTitle),
      content: Text(
        l10n.watchfaceImportIdDescription(
          currentId ?? l10n.watchfaceWorkbenchUnknownId,
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: Text(l10n.cancel),
        ),
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: Text(l10n.watchfaceImportKeepId),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, true),
          child: Text(l10n.watchfaceImportRandomId),
        ),
      ],
    ),
  );
}
