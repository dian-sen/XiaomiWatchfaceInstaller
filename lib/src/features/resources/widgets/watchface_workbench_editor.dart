import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:oronbox/src/app/generated/app_localizations.dart';
import 'package:oronbox/src/app/widgets/page_container.dart';
import 'package:oronbox/src/core/constants/style_constants.dart';
import 'package:oronbox/src/device/core/watchface_install_policy.dart';
import 'package:oronbox/src/features/resources/services/watchface_document.dart';
import 'package:oronbox/src/features/resources/widgets/watchface_workbench_previews.dart';

class WatchfaceWorkbenchEditor extends StatefulWidget {
  const WatchfaceWorkbenchEditor({
    super.key,
    required this.document,
    required this.busy,
    required this.canInstall,
    required this.onExportBin,
    required this.onExportBundle,
    required this.onExportPreview,
    required this.onInstall,
  });

  final WatchfaceDocument document;
  final bool busy;
  final bool canInstall;
  final ValueChanged<WatchfaceDocument> onExportBin;
  final ValueChanged<WatchfaceDocument> onExportBundle;
  final void Function(WatchfaceDocument document, int index) onExportPreview;
  final ValueChanged<WatchfaceDocument> onInstall;

  @override
  State<WatchfaceWorkbenchEditor> createState() =>
      _WatchfaceWorkbenchEditorState();
}

class _WatchfaceWorkbenchEditorState extends State<WatchfaceWorkbenchEditor> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _name;
  late final TextEditingController _id;
  String? _editError;

  @override
  void initState() {
    super.initState();
    _name = TextEditingController(text: widget.document.name);
    _id = TextEditingController(text: widget.document.id ?? '');
  }

  @override
  void didUpdateWidget(WatchfaceWorkbenchEditor oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.document, widget.document)) {
      _formKey.currentState?.reset();
      _name.text = widget.document.name;
      _id.text = widget.document.id ?? '';
      _editError = null;
    }
  }

  void _exportPreview(int index) {
    if (widget.busy) return;
    final value = _name.text.trim();
    if (value.isEmpty) {
      setState(
        () =>
            _editError = AppLocalizations.of(context)!
                .watchfaceWorkbenchNameRequired,
      );
      return;
    }
    try {
      widget.onExportPreview(
        widget.document.edit(name: value, id: widget.document.id ?? ''),
        index,
      );
    } on FormatException {
      setState(
        () =>
            _editError = AppLocalizations.of(context)!
                .watchfaceWorkbenchEditFailed,
      );
    }
  }

  void _submit(ValueChanged<WatchfaceDocument> action) {
    if (widget.busy || !_formKey.currentState!.validate()) return;
    try {
      final edited = widget.document.edit(
        name: _name.text.trim(),
        id: widget.document.canEditId
            ? _id.text.trim()
            : (widget.document.id ?? ''),
      );
      setState(() => _editError = null);
      FocusScope.of(context).unfocus();
      action(edited);
    } on FormatException {
      setState(
        () =>
            _editError = AppLocalizations.of(context)!
                .watchfaceWorkbenchEditFailed,
      );
    }
  }

  @override
  void dispose() {
    _name.dispose();
    _id.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final document = widget.document;
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SectionCard(
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SectionHeader(title: l10n.watchfaceWorkbenchMetadata),
                TextFormField(
                  key: const ValueKey('watchface-name'),
                  controller: _name,
                  enabled: !widget.busy,
                  decoration: InputDecoration(
                    labelText: l10n.watchfaceWorkbenchFileName,
                    suffixText: '.bin',
                    border: const OutlineInputBorder(),
                  ),
                  validator: (value) => value == null || value.trim().isEmpty
                      ? l10n.watchfaceWorkbenchNameRequired
                      : null,
                ),
                const SizedBox(height: StyleConstants.pagePadding),
                Text(
                  l10n.watchfaceWorkbenchOriginalId(
                    document.id ?? l10n.watchfaceWorkbenchUnknownId,
                  ),
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: StyleConstants.safeSpace),
                TextFormField(
                  key: const ValueKey('watchface-id'),
                  controller: _id,
                  enabled: !widget.busy && document.canEditId,
                  keyboardType: TextInputType.number,
                  inputFormatters: [
                    FilteringTextInputFormatter.digitsOnly,
                    LengthLimitingTextInputFormatter(12),
                  ],
                  decoration: InputDecoration(
                    labelText: l10n.watchfaceWorkbenchNewId,
                    helperText: document.canEditId
                        ? l10n.watchfaceWorkbenchIdHint
                        : l10n.watchfaceWorkbenchIdReadOnly,
                    helperMaxLines: 3,
                    border: const OutlineInputBorder(),
                  ),
                  validator: (value) {
                    if (!document.canEditId || value == document.id) {
                      return null;
                    }
                    final id = value?.trim() ?? '';
                    return isValidVelaWatchfaceId(id)
                        ? null
                        : l10n.watchfaceWorkbenchInvalidId;
                  },
                ),
                Align(
                  alignment: AlignmentDirectional.centerEnd,
                  child: TextButton.icon(
                    key: const ValueKey('watchface-random-id'),
                    onPressed: widget.busy || !document.canEditId
                        ? null
                        : () {
                            _id.text = generateRandomWatchfaceId();
                            setState(() => _editError = null);
                          },
                    icon: const Icon(Icons.shuffle),
                    label: Text(l10n.watchfaceWorkbenchRandomId),
                  ),
                ),
                if (document.warnings.any(
                  (warning) =>
                      warning.startsWith('XML_') ||
                      warning.startsWith('PREVIEW_') ||
                      warning == 'NAME_FILENAME_FALLBACK',
                ))
                  Text(
                    l10n.watchfaceWorkbenchMetadataWarning,
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                if (_editError != null)
                  Text(
                    _editError!,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: theme.colorScheme.error,
                    ),
                  ),
              ],
            ),
          ),
        ),
        WatchfaceWorkbenchPreviews(
          previews: document.previews,
          busy: widget.busy,
          onExport: _exportPreview,
        ),
        SectionCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SectionHeader(title: l10n.watchfaceWorkbenchExport),
              Wrap(
                spacing: StyleConstants.cardSpace,
                runSpacing: StyleConstants.cardSpace,
                children: [
                  FilledButton.icon(
                    key: const ValueKey('watchface-export-bin'),
                    onPressed: widget.busy
                        ? null
                        : () => _submit(widget.onExportBin),
                    icon: const Icon(Icons.save_alt),
                    label: Text(l10n.watchfaceWorkbenchExportBin),
                  ),
                  OutlinedButton.icon(
                    key: const ValueKey('watchface-export-bundle'),
                    onPressed: widget.busy
                        ? null
                        : () => _submit(widget.onExportBundle),
                    icon: const Icon(Icons.folder_zip_outlined),
                    label: Text(l10n.watchfaceWorkbenchExportBundle),
                  ),
                  OutlinedButton.icon(
                    key: const ValueKey('watchface-install'),
                    onPressed: widget.busy || !widget.canInstall
                        ? null
                        : () => _submit(widget.onInstall),
                    icon: const Icon(Icons.watch_outlined),
                    label: Text(l10n.install),
                  ),
                ],
              ),
              if (!widget.canInstall) ...[
                const SizedBox(height: StyleConstants.safeSpace),
                Text(
                  l10n.watchfaceWorkbenchConnectToInstall,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }
}
