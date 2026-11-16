import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:oronbox/src/app/generated/app_localizations.dart';
import 'package:oronbox/src/app/theme/coloros_design_tokens.dart';
import 'package:oronbox/src/device/core/device_state.dart' as proto;
import 'package:oronbox/src/features/devices/controllers/device_manager.dart';

class WorkbenchHomePage extends ConsumerWidget {
  const WorkbenchHomePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context)!;
    final state = ref.watch(deviceManagerProvider);
    final colors = Theme.of(context).colorScheme;
    final ready = state.protocolState == proto.ProtocolState.ready;
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(ColorOsDesignTokens.pagePadding),
          children: [
            const SizedBox(height: 12),
            Text(
              l10n.studioTitle,
              style: Theme.of(context).textTheme.headlineLarge,
            ),
            const SizedBox(height: 8),
            Text(
              l10n.studioSubtitle,
              style: Theme.of(context).textTheme.bodyLarge
                  ?.copyWith(color: colors.onSurfaceVariant),
            ),
            const SizedBox(height: 28),
            Container(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  colors: [colors.primaryContainer, colors.secondaryContainer],
                ),
                borderRadius: BorderRadius.circular(32),
              ),
              padding: const EdgeInsets.all(24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(
                    Icons.watch_rounded,
                    size: 40,
                    color: colors.onPrimaryContainer,
                  ),
                  const SizedBox(height: 16),
                  Text(
                    state.currentDevice?.name ?? l10n.studioNoDevice,
                    style: Theme.of(context).textTheme.titleLarge
                        ?.copyWith(color: colors.onPrimaryContainer),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    ready ? l10n.studioConnected : l10n.studioConnectHint,
                    style: TextStyle(color: colors.onPrimaryContainer),
                  ),
                  const SizedBox(height: 20),
                  FilledButton.icon(
                    onPressed: () => context.push('/devices/switch'),
                    icon: const Icon(Icons.bluetooth_rounded),
                    label: Text(l10n.studioManageDevices),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 28),
            _action(
              context,
              Icons.palette_outlined,
              l10n.studioWorkbench,
              l10n.studioWorkbenchHint,
              '/watchface-tools',
            ),
            _action(
              context,
              Icons.install_mobile_outlined,
              l10n.studioLocalInstall,
              l10n.studioLocalInstallHint,
              '/devices/install/watchface',
            ),
            _action(
              context,
              Icons.key_outlined,
              l10n.keyCenterTitle,
              l10n.studioKeysHint,
              '/device-keys',
            ),
            _action(
              context,
              Icons.account_circle_outlined,
              l10n.studioAccounts,
              l10n.studioAccountsHint,
              '/settings/accounts',
            ),
            const SizedBox(height: 16),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8),
              child: Text(
                l10n.studioOfflineHint,
                style: Theme.of(context).textTheme.bodySmall
                    ?.copyWith(color: colors.onSurfaceVariant),
              ),
            ),
            const SizedBox(height: 16),
          ],
        ),
      ),
    );
  }

  Widget _action(
    BuildContext context,
    IconData icon,
    String title,
    String detail,
    String route,
  ) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Card(
        child: ListTile(
          leading: Container(
            width: 52,
            height: 52,
            decoration: BoxDecoration(
              color: scheme.primaryContainer,
              borderRadius: BorderRadius.circular(18),
            ),
            child: Icon(icon, color: scheme.onPrimaryContainer),
          ),
          title: Text(title),
          subtitle: Text(detail),
          trailing: const Icon(Icons.chevron_right_rounded),
          contentPadding: const EdgeInsets.symmetric(
            horizontal: 20,
            vertical: 14,
          ),
          onTap: () => context.push(route),
        ),
      ),
    );
  }
}
