import 'package:intl/intl.dart';
import 'package:material_ui/material_ui.dart';
import 'package:oronbox/src/app/generated/app_localizations.dart';
import 'package:oronbox/src/app/widgets/page_container.dart';
import 'package:oronbox/src/core/constants/style_constants.dart';
import 'package:oronbox/src/features/resources/services/watchface_cache_import_service.dart';
import 'package:segmented_list/segmented_list.dart';

class WatchfaceWorkbenchSources extends StatelessWidget {
  const WatchfaceWorkbenchSources({
    super.key,
    required this.busy,
    required this.cacheSupported,
    required this.onImport,
    required this.onScan,
    required this.onSelect,
    this.scan,
    this.selectedToken,
    this.cacheMode = 'root',
  });

  final bool busy;
  final bool cacheSupported;
  final VoidCallback onImport;
  final VoidCallback onScan;
  final ValueChanged<WatchfaceCacheFile> onSelect;
  final WatchfaceCacheScan? scan;
  final String? selectedToken;
  final String cacheMode;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final colors = Theme.of(context).colorScheme;
    final result = scan;
    final files = [...?result?.files]
      ..sort((a, b) => b.modifiedAt.compareTo(a.modifiedAt));
    final diagnostics = result?.diagnostics ?? const [];
    final codes = diagnostics.map((item) => item.code).toSet();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SectionCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SectionHeader(title: l10n.watchfaceWorkbenchSource),
              Text(
                l10n.watchfaceWorkbenchDescription,
                style: Theme.of(context).textTheme.bodyMedium
                    ?.copyWith(color: colors.onSurfaceVariant),
              ),
            ],
          ),
        ),
        SegmentedSection(
          margin: const EdgeInsetsDirectional.only(
            bottom: StyleConstants.sectionSpacing,
          ),
          tiles: [
            SegmentedTile.navigation(
              key: const ValueKey('watchface-import'),
              leading: const Icon(Icons.upload_file_outlined),
              title: Text(l10n.watchfaceWorkbenchImport),
              enabled: !busy,
              onPressed: (_) => onImport(),
            ),
            if (cacheSupported)
              SegmentedTile.navigation(
                key: const ValueKey('watchface-scan'),
                leading: const Icon(Icons.folder_open_outlined),
                title: Text(l10n.watchfaceWorkbenchScan),
                description: Text(
                  cacheMode == 'shizuku'
                      ? l10n.cacheShizukuHint
                      : l10n.watchfaceWorkbenchRootHint,
                ),
                enabled: !busy,
                onPressed: (_) => onScan(),
              ),
          ],
          bottomInfo: cacheSupported
              ? null
              : Text(
                  cacheMode == 'adb'
                      ? l10n.cacheAdbHint
                      : l10n.watchfaceWorkbenchAndroidOnly,
                ),
        ),
        if (result != null) ...[
          SectionHeader(title: l10n.watchfaceWorkbenchCandidates(files.length)),
          if (codes.contains('MISSING'))
            _ScanNotice(text: l10n.watchfaceWorkbenchMissingDirectory),
          if (codes.contains('DENIED'))
            _ScanNotice(text: l10n.watchfaceWorkbenchCacheDenied),
          if (result.truncated || codes.contains('LIMIT'))
            _ScanNotice(text: l10n.watchfaceWorkbenchScanTruncated),
          if (codes.any(
            (code) => !const {'MISSING', 'DENIED', 'LIMIT'}.contains(code),
          ))
            _ScanNotice(text: l10n.watchfaceWorkbenchScanPartial),
          if (files.isEmpty)
            SectionCard(child: Text(l10n.watchfaceWorkbenchCacheEmpty))
          else
            SegmentedSection(
              margin: const EdgeInsetsDirectional.only(
                bottom: StyleConstants.sectionSpacing,
              ),
              tiles: files.map((file) {
                final selected = file.token == selectedToken;
                final modified = DateFormat.yMd(l10n.localeName)
                    .add_Hm()
                    .format(file.modifiedAt.toLocal());
                return SegmentedTile(
                  key: ValueKey('watchface-cache-${file.token}'),
                  leading: Icon(
                    selected
                        ? Icons.check_circle_outline
                        : Icons.watch_outlined,
                    color: selected ? colors.primary : null,
                  ),
                  title: Text(file.name),
                  description: Text(
                    [
                      l10n.watchfaceWorkbenchFileDetails(
                        _formatSize(file.size),
                        modified,
                      ),
                      if (file.location.isNotEmpty) file.location,
                    ].join('\n'),
                  ),
                  enabled: !busy,
                  onPressed: (_) => onSelect(file),
                );
              }).toList(),
            ),
        ],
      ],
    );
  }
}

class _ScanNotice extends StatelessWidget {
  const _ScanNotice({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: StyleConstants.safeSpace),
    child: Text(
      text,
      style: Theme.of(context).textTheme.bodyMedium
          ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
    ),
  );
}

String _formatSize(int bytes) {
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
  return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
}
