#!/usr/bin/env bash
# Focused Flutter migration checks. Use --android only on a prepared Android host.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
case "${1-}" in
  '') verify_android=false ;;
  --android) verify_android=true ;;
  *) echo 'Usage: bash tool/ci_verify.sh [--android]' >&2; exit 2 ;;
esac

flutter --version --machine | python3 -c '
import json, pathlib, sys
actual = json.load(sys.stdin)
expected = json.loads(pathlib.Path(".fvmrc").read_text())["flutter"]
assert expected == "3.47.9", "Unexpected .fvmrc version"
assert actual["frameworkVersion"] == expected, "Flutter does not match .fvmrc"
assert actual["frameworkRevision"] == "c197ca5199e9ee5661e865596c9c07294dfab51c", "Unexpected Flutter commit"
print("Flutter version and official commit: PASS")
'
flutter pub get --enforce-lockfile
flutter gen-l10n

analysis_targets=(
  lib/src/app/theme
  lib/src/app/router
  lib/src/app/layout
  lib/src/app/oronbox_app.dart
  lib/src/features/workbench
  lib/src/features/devices/pages/keys
  lib/src/core/services/private_app_state.dart
  lib/src/command_bus/local_command_bus.dart
  lib/src/features/resources/services/watchface_bundle_import.dart
  lib/src/features/resources/services/watchface_document.dart
  lib/src/features/resources/services/watchface_payload_repair.dart
  lib/src/features/resources/services/watchface_workbench_service.dart
  lib/src/features/resources/services/watchface_cache_import_service.dart
  lib/src/features/resources/controllers/watchface_workbench_controller.dart
  lib/src/features/resources/widgets/watchface_workbench_status.dart
  lib/src/features/resources/widgets/watchface_workbench_sources.dart
  lib/src/features/resources/widgets/watchface_workbench_previews.dart
  lib/src/features/resources/widgets/watchface_workbench_editor.dart
  lib/src/features/resources/pages/watchface_workbench_page.dart
  lib/src/device/core/watchface_install_policy.dart
)
test_targets=(
  test/features/workbench
  test/core/services/private_app_state_test.dart
  test/personal
  test/features/accounts/services/mi_account_service_test.dart
  test/features/accounts/services/huami_auth_service_test.dart
  test/features/resources/services/watchface_bundle_import_test.dart
  test/features/resources/services/watchface_document_test.dart
  test/features/resources/services/watchface_payload_repair_test.dart
  test/features/resources/services/watchface_workbench_service_test.dart
  test/features/resources/services/watchface_cache_import_service_test.dart
  test/features/resources/controllers/watchface_workbench_controller_test.dart
  test/features/resources/widgets/watchface_workbench_status_test.dart
  test/features/resources/widgets/watchface_workbench_sources_test.dart
  test/features/resources/widgets/watchface_workbench_editor_test.dart
  test/device/core/watchface_install_policy_test.dart
)
flutter analyze --no-pub "${analysis_targets[@]}"
flutter test --no-pub --reporter expanded --concurrency=2 "${test_targets[@]}"
python3 -m unittest discover -s test/features/resources/services -p 'adb_watchface_export_test.py'
python3 -m unittest discover -s test/tool -p 'verify_apk_abi_test.py'

if "$verify_android"; then
  xms_revision=$(git -C android/xms-wearable-lib-cleanroom rev-parse HEAD)
  test "$xms_revision" = 6483f939785e9c1dd011465d573931f669a6adab || {
    echo 'XMS Wearable library does not match the pinned commit' >&2; exit 1;
  }
  python3 tool/verify_native.py
  flutter build apk --debug --no-pub --target-platform android-arm64
  python3 tool/verify_apk_abi.py build/app/outputs/flutter-apk/app-debug.apk --abi arm64-v8a --debug
  (
    cd android
    ./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:lintDebug :xms-wearable-lib:testDebugUnitTest :xms-wearable-lib:lintDebug --stacktrace
  )
fi
