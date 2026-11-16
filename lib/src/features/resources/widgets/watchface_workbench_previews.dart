import 'package:material_ui/material_ui.dart';
import 'package:oronbox/src/app/generated/app_localizations.dart';
import 'package:oronbox/src/app/widgets/page_container.dart';
import 'package:oronbox/src/core/constants/style_constants.dart';
import 'package:oronbox/src/features/resources/services/watchface_document.dart';

class WatchfaceWorkbenchPreviews extends StatelessWidget {
  const WatchfaceWorkbenchPreviews({
    super.key,
    required this.previews,
    required this.busy,
    required this.onExport,
  });

  final List<WatchfacePreview> previews;
  final bool busy;
  final ValueChanged<int> onExport;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final theme = Theme.of(context);
    return SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SectionHeader(title: l10n.preview),
          if (previews.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(
                vertical: StyleConstants.pagePadding,
              ),
              child: Text(
                l10n.watchfaceWorkbenchPreviewMissing,
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            )
          else
            LayoutBuilder(
              builder: (context, constraints) {
                final width = constraints.maxWidth < 300
                    ? constraints.maxWidth
                    : (constraints.maxWidth - StyleConstants.safeSpace) / 2;
                return Wrap(
                  spacing: StyleConstants.safeSpace,
                  runSpacing: StyleConstants.safeSpace,
                  children: [
                    for (var index = 0; index < previews.length; index++)
                      SizedBox(
                        width: width,
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            ClipRRect(
                              borderRadius: BorderRadius.circular(
                                StyleConstants.tileRadius,
                              ),
                              child: ColoredBox(
                                color: theme.colorScheme.surfaceContainer,
                                child: Image.memory(
                                  previews[index].bytes,
                                  key: ValueKey('watchface-preview-$index'),
                                  height: 220,
                                  fit: BoxFit.contain,
                                  semanticLabel: previews[index].name,
                                  errorBuilder: (context, error, stackTrace) =>
                                      SizedBox(
                                        height: 220,
                                        child: Center(
                                          child: Text(
                                            l10n.watchfaceWorkbenchPreviewUnreadable,
                                            textAlign: TextAlign.center,
                                          ),
                                        ),
                                      ),
                                ),
                              ),
                            ),
                            const SizedBox(height: StyleConstants.cardSpace),
                            Text(
                              previews[index].name,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: theme.textTheme.bodySmall,
                            ),
                            Align(
                              alignment: AlignmentDirectional.centerStart,
                              child: TextButton.icon(
                                key: ValueKey(
                                  'watchface-export-preview-$index',
                                ),
                                onPressed: busy ? null : () => onExport(index),
                                icon: const Icon(Icons.save_alt),
                                label: Text(
                                  l10n.watchfaceWorkbenchExportPreview,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                  ],
                );
              },
            ),
        ],
      ),
    );
  }
}
