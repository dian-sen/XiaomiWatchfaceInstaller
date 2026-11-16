import 'dart:async';

import 'package:file_picker/file_picker.dart';
import 'package:dio/dio.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:oronbox/src/app/generated/app_localizations.dart';
import 'package:oronbox/src/app/utils/error_localization.dart';
import 'package:oronbox/src/app/utils/picked_file.dart';
import 'package:oronbox/src/app/widgets/app_state_view.dart';
import 'package:oronbox/src/app/widgets/page_container.dart';
import 'package:oronbox/src/app/widgets/release_notes_view.dart';
import 'package:oronbox/src/app/widgets/sys_app_bar.dart';
import 'package:oronbox/src/core/constants/style_constants.dart';
import 'package:oronbox/src/device/core/device_kind.dart';
import 'package:oronbox/src/features/devices/controllers/device_manager.dart';
import 'package:oronbox/src/features/devices/services/firmware_catalog.dart';
import 'package:oronbox/src/features/devices/services/firmware_download_service.dart';
import 'package:oronbox/src/features/resources/services/resource_install_service.dart';
import 'package:oronbox/src/features/resources/widgets/resource_install_confirmation.dart';

class DeviceFirmwarePage extends ConsumerStatefulWidget {
  const DeviceFirmwarePage({super.key});

  @override
  ConsumerState<DeviceFirmwarePage> createState() => _DeviceFirmwarePageState();
}

class _DeviceFirmwarePageState extends ConsumerState<DeviceFirmwarePage> {
  bool _checking = false;
  bool _checked = false;
  bool _sourceUnavailable = false;
  bool _installingUpdate = false;
  String? _error;
  double _downloadProgress = 0;
  CancelToken? _downloadCancellation;
  List<FirmwareRelease> _releases = const [];
  String? _releaseDevice;

  String? _deviceKey() {
    final state = ref.read(deviceManagerProvider);
    final device = state.currentDevice;
    return device == null
        ? null
        : '${device.addr}:${device.codename}:${state.systemInfo?.model}';
  }

  @override
  void initState() {
    super.initState();
    Future.microtask(_checkForUpdates);
  }

  Future<void> _checkForUpdates() async {
    if (!mounted) return;
    final manager = ref.read(deviceManagerProvider.notifier);
    final state = ref.read(deviceManagerProvider);
    final device = state.currentDevice;
    if (device == null || _checking) return;
    setState(() {
      _checking = true;
      _sourceUnavailable = false;
      _error = null;
    });
    final systemInfo = state.systemInfo;
    final queryDevice = _deviceKey();
    try {
      final releases = await ref
          .read(firmwareCatalogProvider)
          .findUpdates(
            FirmwareCatalogQuery(
              kind: manager.currentDeviceKind ?? DeviceKind.xiaomi,
              codename: device.codename,
              model: systemInfo?.model ?? '',
              currentVersion: systemInfo?.firmwareVersion ?? '',
            ),
          );
      if (!mounted || _deviceKey() != queryDevice) return;
      setState(() {
        _releaseDevice = queryDevice;
        _releases = releases;
        _checked = true;
      });
    } on FirmwareCatalogUnavailable {
      if (!mounted) return;
      setState(() {
        _releases = const [];
        _checked = true;
        _sourceUnavailable = true;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _releases = const [];
        _checked = true;
        _error = localizedErrorMessage(AppLocalizations.of(context)!, error);
      });
    } finally {
      if (mounted) setState(() => _checking = false);
    }
  }

  @override
  void dispose() {
    _downloadCancellation?.cancel();
    super.dispose();
  }

  Future<void> _downloadLatest(FirmwareRelease release) async {
    if (_installingUpdate) return;
    final target = ref.read(deviceManagerProvider).currentDevice;
    final targetModel = ref.read(deviceManagerProvider).systemInfo?.model;
    if (target == null || _releaseDevice != _deviceKey()) return;
    final downloader = ref.read(firmwareDownloadServiceProvider);
    final cancel = CancelToken();
    _downloadCancellation = cancel;
    setState(() {
      _installingUpdate = true;
      _downloadProgress = 0;
      _error = null;
    });
    String? path;
    try {
      // Every online action uses the complete package, regardless of the
      // device's current version or availability of incremental assets.
      path = await downloader.downloadFull(
        release.fullPackage,
        cancelToken: cancel,
        onProgress: (progress) {
          if (mounted) setState(() => _downloadProgress = progress);
        },
      );
      if (!mounted) return;
      final current = ref.read(deviceManagerProvider);
      if (current.currentDevice?.addr != target.addr ||
          current.currentDevice?.codename != target.codename ||
          current.systemInfo?.model != targetModel) {
        throw StateError(
          'Device changed during firmware download; check updates again',
        );
      }
      await confirmAndEnqueueResourceFile(
        context: context,
        ref: ref,
        file: downloader.open(path),
        selectedType: LocalDeviceInstallType.firmware,
        canEnqueue: () => mounted && _releaseDevice == _deviceKey(),
        targetDevice: target.addr,
      );
    } catch (error) {
      if (mounted && !(error is DioException && CancelToken.isCancel(error))) {
        setState(
          () => _error = localizedErrorMessage(
            AppLocalizations.of(context)!,
            error,
          ),
        );
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(_error!)));
      }
    } finally {
      if (path != null) await downloader.release(path);
      _downloadCancellation = null;
      if (mounted) setState(() => _installingUpdate = false);
    }
  }

  Future<void> _installLatest(FirmwareRelease release) =>
      _downloadLatest(release);

  Future<void> _installLocal() async {
    final file = await pickFileData(
      type: FileType.any,
      loadBytes: shouldLoadPickedFileData,
    );
    final selected = file?.xFile;
    if (selected == null || !mounted) return;
    if (!mounted) return;
    await confirmAndEnqueueResourceFile(
      context: context,
      ref: ref,
      file: selected,
      selectedType: LocalDeviceInstallType.firmware,
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final state = ref.watch(deviceManagerProvider);
    final device = state.currentDevice;
    final currentVersion = state.systemInfo?.firmwareVersion.trim() ?? '';
    final releases = _releaseDevice == _deviceKey()
        ? _releases
        : const <FirmwareRelease>[];
    final latest = releases.firstOrNull;
    final history = releases.length > 1
        ? releases.skip(1).toList(growable: false)
        : const <FirmwareRelease>[];
    final hasUpdate =
        latest != null &&
        currentVersion.isNotEmpty &&
        _compareVersions(latest.version, currentVersion) > 0;

    return Scaffold(
      appBar: SysAppBar(
        secondary: true,
        title: Text(l10n.deviceFeaturesInstallFirmware),
      ),
      body: ListView(
        padding: EdgeInsets.zero,
        children: [
          PageContainer(
            padding: EdgeInsets.fromLTRB(
              StyleConstants.pagePadding,
              8,
              StyleConstants.pagePadding,
              bottomContentPadding(context, extra: 24),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                TextButton.icon(
                  onPressed: () => launchUrl(
                    Uri.parse(
                      'https://github.com/AstralSightStudios/MiWearFirmwareArchives/releases',
                    ),
                  ),
                  icon: const Icon(Icons.open_in_new),
                  label: const Text('GitHub · MiWearFirmwareArchives'),
                ),
                SectionHeader(title: l10n.firmwareCurrentVersion),
                Text(
                  currentVersion.isEmpty
                      ? l10n.firmwareVersionUnknown
                      : currentVersion,
                  style: Theme.of(context).textTheme.displaySmall,
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(child: Text(_statusText(l10n, latest, hasUpdate))),
                    TextButton.icon(
                      onPressed: _checking || _installingUpdate
                          ? null
                          : _checkForUpdates,
                      icon: _checking
                          ? const SizedBox.square(
                              dimension: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.refresh),
                      label: Text(_checking ? l10n.refreshing : l10n.refresh),
                    ),
                  ],
                ),
                const SizedBox(height: 24),
                Row(
                  children: [
                    Expanded(
                      child: FilledButton.tonalIcon(
                        onPressed: latest == null || _installingUpdate
                            ? null
                            : () => _downloadLatest(latest),
                        icon: const Icon(Icons.download_outlined),
                        label: Text(l10n.firmwareDownloadLatestFull),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: FilledButton.tonalIcon(
                        onPressed: device == null || _installingUpdate
                            ? null
                            : _installLocal,
                        icon: const Icon(Icons.folder_open_outlined),
                        label: Text(l10n.localFirmwareInstall),
                      ),
                    ),
                  ],
                ),
                if (_installingUpdate) ...[
                  const SizedBox(height: 16),
                  LinearProgressIndicator(
                    value: _downloadProgress > 0 ? _downloadProgress : null,
                  ),
                  const SizedBox(height: 8),
                  Text(l10n.firmwareDownloadVerifying),
                ],
                const SizedBox(height: 28),
                SectionHeader(title: l10n.firmwareLatestRelease),
                const SizedBox(height: 8),
                SectionCard(
                  margin: EdgeInsets.zero,
                  child: latest == null
                      ? EmptyStateView(
                          compact: true,
                          icon: Icons.system_update_alt,
                          title: _emptyText(l10n),
                        )
                      : Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              latest.version,
                              style: Theme.of(context).textTheme.headlineSmall,
                            ),
                            if (hasUpdate) ...[
                              const SizedBox(height: 12),
                              FilledButton.icon(
                                onPressed: _installingUpdate
                                    ? null
                                    : () => _installLatest(latest),
                                icon: _installingUpdate
                                    ? const SizedBox.square(
                                        dimension: 18,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2,
                                        ),
                                      )
                                    : const Icon(Icons.system_update_alt),
                                label: Text(l10n.firmwareUpdateNow),
                              ),
                            ],
                            const SizedBox(height: 20),
                            Text(
                              l10n.firmwareReleaseNotes,
                              style: Theme.of(context).textTheme.titleSmall,
                            ),
                            const SizedBox(height: 8),
                            if (latest.releaseNotes?.trim().isNotEmpty == true)
                              ReleaseNotesView(
                                data: latest.releaseNotes!.trim(),
                              )
                            else
                              Text(l10n.firmwareReleaseNotesUnavailable),
                          ],
                        ),
                ),
                if (history.isNotEmpty) ...[
                  const SizedBox(height: 28),
                  SectionHeader(title: l10n.firmwareHistoricalReleases),
                  const SizedBox(height: 8),
                  for (final release in history) ...[
                    SectionCard(
                      margin: const EdgeInsets.only(bottom: 8),
                      padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Expanded(
                                child: Text(
                                  release.version,
                                  style: Theme.of(context).textTheme.titleMedium
                                      ?.copyWith(fontWeight: FontWeight.w700),
                                ),
                              ),
                              IconButton(
                                tooltip: l10n.download,
                                icon: const Icon(Icons.download_outlined),
                                onPressed: _installingUpdate
                                    ? null
                                    : () => _downloadLatest(release),
                              ),
                            ],
                          ),
                          if (release.releaseNotes?.trim().isNotEmpty ==
                              true) ...[
                            const SizedBox(height: 8),
                            ReleaseNotesView(
                              data: release.releaseNotes!.trim(),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ],
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  String _statusText(
    AppLocalizations l10n,
    FirmwareRelease? latest,
    bool hasUpdate,
  ) {
    if (_checking) return l10n.firmwareCheckingUpdates;
    if (_error != null) return l10n.updateCheckFailed;
    if (_sourceUnavailable) return l10n.firmwareSourceUnavailable;
    if (!_checked || latest == null) return l10n.firmwareNoUpdatesFound;
    return hasUpdate ? l10n.firmwareUpdateAvailable : l10n.firmwareUpToDate;
  }

  String _emptyText(AppLocalizations l10n) {
    if (_checking) return l10n.firmwareCheckingUpdates;
    if (_error != null) return l10n.updateCheckFailed;
    if (_sourceUnavailable) return l10n.firmwareSourceUnavailable;
    return l10n.firmwareNoUpdatesFound;
  }

  int _compareVersions(String left, String right) {
    List<int> parts(String value) => value
        .trim()
        .replaceFirst(RegExp(r'^[vV]'), '')
        .split('.')
        .map((part) => int.tryParse(part) ?? 0)
        .toList();
    final a = parts(left);
    final b = parts(right);
    final length = a.length > b.length ? a.length : b.length;
    for (var index = 0; index < length; index++) {
      final result = (index < a.length ? a[index] : 0).compareTo(
        index < b.length ? b[index] : 0,
      );
      if (result != 0) return result;
    }
    return 0;
  }
}
