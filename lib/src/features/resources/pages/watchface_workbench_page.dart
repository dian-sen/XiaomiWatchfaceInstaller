import 'dart:async';

import 'package:cross_file/cross_file.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';
import 'package:oronbox/src/app/generated/app_localizations.dart';
import 'package:oronbox/src/app/widgets/page_container.dart';
import 'package:oronbox/src/app/widgets/sys_app_bar.dart';
import 'package:oronbox/src/core/constants/style_constants.dart';
import 'package:oronbox/src/device/core/device_state.dart' as proto;
import 'package:oronbox/src/features/devices/controllers/device_manager.dart';
import 'package:oronbox/src/features/resources/controllers/watchface_workbench_controller.dart';
import 'package:oronbox/src/features/resources/services/resource_install_service.dart';
import 'package:oronbox/src/features/resources/services/watchface_cache_import_service.dart';
import 'package:oronbox/src/features/resources/services/watchface_document.dart';
import 'package:oronbox/src/features/resources/services/watchface_bin_import.dart';
import 'package:oronbox/src/features/resources/services/watchface_local_store.dart';
import 'package:oronbox/src/features/resources/widgets/watchface_random_id_dialog.dart';
import 'package:oronbox/src/features/resources/services/watchface_workbench_service.dart';
import 'package:oronbox/src/features/resources/widgets/resource_install_confirmation.dart';
import 'package:oronbox/src/features/resources/widgets/watchface_workbench_editor.dart';
import 'package:oronbox/src/features/resources/widgets/watchface_workbench_sources.dart';
import 'package:oronbox/src/features/resources/widgets/watchface_workbench_status.dart';

class WatchfaceWorkbenchPage extends ConsumerStatefulWidget {
  const WatchfaceWorkbenchPage({
    super.key,
    this.initialFile,
    this.releaseInitialFile = false,
    this.service,
  });

  final XFile? initialFile;
  final bool releaseInitialFile;

  /// The workbench owns this service and disposes it when the page closes.
  final WatchfaceWorkbenchService? service;

  @override
  ConsumerState<WatchfaceWorkbenchPage> createState() =>
      _WatchfaceWorkbenchPageState();
}

class _WatchfaceWorkbenchPageState
    extends ConsumerState<WatchfaceWorkbenchPage> {
  late final WatchfaceWorkbenchController _controller;

  @override
  void initState() {
    super.initState();
    _controller = WatchfaceWorkbenchController(
      widget.service ??
          WatchfaceWorkbenchService(
            localStore: WatchfaceLocalStore(),
            prepareBin: (bytes, name) => prepareWatchfaceBinBytes(
              bytes,
              name,
              requestRandomId: (id) async =>
                  mounted ? requestWatchfaceRandomId(context, id) : null,
            ),
          ),
    );
    final initialFile = widget.initialFile;
    if (initialFile != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        unawaited(
          widget.releaseInitialFile
              ? _controller.importOpenedFile(initialFile)
              : _controller.importFile(initialFile),
        );
      });
    }
  }

  bool get _canInstall {
    final state = ref.read(deviceManagerProvider);
    return state.currentDevice != null &&
        !state.currentDevice!.disconnected &&
        !state.connecting &&
        state.protocolState == proto.ProtocolState.ready;
  }

  void _install(WatchfaceDocument edited) {
    if (!_canInstall || _controller.busy) return;
    unawaited(
      _controller.install(
        edited,
        (document) => confirmAndEnqueueResourceFile(
          context: context,
          ref: ref,
          file: XFile.fromData(document.bytes, name: document.binFileName),
          selectedType: LocalDeviceInstallType.watchface,
          watchfaceIdPrepared: true,
        ),
      ),
    );
  }

  Widget _cacheModes(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final mode = _controller.cacheMode;
    return Padding(
      padding: const EdgeInsets.only(bottom: 20),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                l10n.cacheModeTitle,
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final item in [
                    ('root', l10n.cacheModeRoot),
                    ('shizuku', l10n.cacheModeShizuku),
                    ('adb', l10n.cacheModeAdb),
                  ])
                    ChoiceChip(
                      label: Text(item.$2),
                      selected: mode == item.$1,
                      onSelected: _controller.busy
                          ? null
                          : (_) => _controller.setCacheMode(item.$1),
                    ),
                ],
              ),
              const SizedBox(height: 12),
              Text(
                mode == 'adb'
                    ? l10n.cacheAdbHint
                    : mode == 'shizuku'
                    ? l10n.cacheShizukuHint
                    : l10n.cacheRootHint,
              ),
              const SizedBox(height: 12),
              if (_controller.cacheAuthorized) Text(l10n.cacheReady),
              if (mode == 'adb')
                FilledButton(
                  onPressed: _controller.busy ? null : _controller.pickLocal,
                  child: Text(l10n.cacheAdbImport),
                )
              else if (WatchfaceCacheImportService.isSupported)
                OutlinedButton(
                  onPressed: _controller.busy
                      ? null
                      : _controller.authorizeCache,
                  child: Text(l10n.cacheAuthorize),
                ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final ready = ref.watch(
      deviceManagerProvider.select(
        (state) =>
            state.currentDevice != null &&
            !state.currentDevice!.disconnected &&
            !state.connecting &&
            state.protocolState == proto.ProtocolState.ready,
      ),
    );
    return Scaffold(
      appBar: SysAppBar(
        secondary: true,
        title: Text(l10n.watchfaceWorkbenchTitle),
      ),
      body: ListenableBuilder(
        listenable: _controller,
        builder: (context, _) {
          final document = _controller.document;
          return ListView(
            padding: EdgeInsets.zero,
            children: [
              PageContainer(
                padding: const EdgeInsets.fromLTRB(
                  StyleConstants.pagePadding,
                  StyleConstants.cardSpace,
                  StyleConstants.pagePadding,
                  StyleConstants.pagePadding,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    WatchfaceWorkbenchStatus(controller: _controller),
                    _cacheModes(context),
                    WatchfaceWorkbenchSources(
                      busy: _controller.busy,
                      cacheMode: _controller.cacheMode,
                      cacheSupported:
                          WatchfaceCacheImportService.isSupported &&
                          _controller.cacheMode != 'adb',
                      scan: _controller.scan,
                      selectedToken: _controller.selectedCacheToken,
                      onImport: _controller.pickLocal,
                      onScan: _controller.scanCache,
                      onSelect: _controller.selectCache,
                    ),
                    if (document != null)
                      WatchfaceWorkbenchEditor(
                        key: ValueKey(_controller.documentRevision),
                        document: document,
                        busy: _controller.busy,
                        canInstall: ready,
                        onExportBin: (edited) => _controller.exportDocument(
                          edited,
                          kind: WatchfaceExportKind.bin,
                        ),
                        onExportBundle: (edited) => _controller.exportDocument(
                          edited,
                          kind: WatchfaceExportKind.bundle,
                        ),
                        onExportPreview: (edited, index) =>
                            _controller.exportDocument(
                              edited,
                              kind: WatchfaceExportKind.preview,
                              previewIndex: index,
                            ),
                        onInstall: _install,
                      ),
                  ],
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}
