import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:oronbox/src/app/generated/app_localizations.dart';
import 'package:oronbox/src/device/core/watchface_install_policy.dart';
import 'package:oronbox/src/app/widgets/page_container.dart';
import 'package:oronbox/src/app/widgets/smooth_linear_progress_indicator.dart';
import 'package:oronbox/src/core/constants/style_constants.dart';
import 'package:oronbox/src/features/resources/controllers/watchface_workbench_controller.dart';
import 'package:oronbox/src/features/resources/services/watchface_workbench_service.dart';

class WatchfaceWorkbenchStatus extends StatelessWidget {
  const WatchfaceWorkbenchStatus({super.key, required this.controller});

  final WatchfaceWorkbenchController controller;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final colors = Theme.of(context).colorScheme;
    final action = controller.action;
    final error = controller.error;
    final result = controller.exportResult;
    final message = error != null
        ? _errorMessage(l10n, error, controller.failedAction)
        : controller.enqueued
        ? l10n.watchfaceWorkbenchEnqueued
        : switch (result) {
            WatchfaceExportResult.saved => l10n.watchfaceWorkbenchSaved,
            WatchfaceExportResult.cancelled => l10n.watchfaceWorkbenchCancelled,
            WatchfaceExportResult.downloadStarted =>
              l10n.watchfaceWorkbenchDownloadStarted,
            null => null,
          };
    if (action == null && message == null) return const SizedBox.shrink();
    return SectionCard(
      color: error == null ? null : colors.errorContainer,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (action != null) ...[
            Text(switch (action) {
              WatchfaceWorkbenchAction.importing =>
                l10n.watchfaceWorkbenchImporting,
              WatchfaceWorkbenchAction.scanning =>
                l10n.watchfaceWorkbenchScanning,
              WatchfaceWorkbenchAction.exporting =>
                l10n.watchfaceWorkbenchExporting,
              WatchfaceWorkbenchAction.installing =>
                l10n.watchfaceWorkbenchInstalling,
            }),
            const SizedBox(height: StyleConstants.safeSpace),
            const SmoothLinearProgressIndicator(value: null),
          ],
          if (message != null)
            Text(
              message,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: error == null
                    ? colors.onSurface
                    : colors.onErrorContainer,
              ),
            ),
        ],
      ),
    );
  }
}

String _errorMessage(
  AppLocalizations l10n,
  Object error,
  WatchfaceWorkbenchAction? action,
) {
  if (error is WatchfaceInstallBlockedException) {
    return l10n.watchfaceRestrictedMessage;
  }
  if (error is PlatformException) {
    final codes = {
      error.code.toUpperCase(),
      error.message?.toUpperCase(),
      if (error.details case {'code': final String code}) code.toUpperCase(),
    };
    if (codes.contains('ROOT_DENIED') || codes.contains('ROOT_UNAVAILABLE')) {
      return l10n.watchfaceWorkbenchRootUnavailable;
    }
    if (codes.any((code) => code?.startsWith('SHIZUKU_') == true)) {
      return l10n.cacheShizukuHint;
    }
    if (codes.contains('MISSING')) {
      return l10n.watchfaceWorkbenchMissingDirectory;
    }
    if (codes.contains('DENIED')) return l10n.watchfaceWorkbenchCacheDenied;
    if (codes.contains('SOURCE_CHANGED') || codes.contains('INVALID_TOKEN')) {
      return l10n.watchfaceWorkbenchSourceChanged;
    }
  }
  return switch (action) {
    WatchfaceWorkbenchAction.importing => l10n.watchfaceWorkbenchImportFailed,
    WatchfaceWorkbenchAction.scanning => l10n.watchfaceWorkbenchScanFailed,
    WatchfaceWorkbenchAction.exporting => l10n.watchfaceWorkbenchExportFailed,
    WatchfaceWorkbenchAction.installing => l10n.errorOperationFailed,
    null => l10n.watchfaceWorkbenchImportFailed,
  };
}
