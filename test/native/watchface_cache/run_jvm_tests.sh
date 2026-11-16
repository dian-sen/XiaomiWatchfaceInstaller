#!/usr/bin/env bash
# Narrow JVM regression plus Android API signature compilation; no device/root access.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../../.."
compiler_lib=${ORONBOX_TEST_GRADLE_LIB:-/opt/gradle-8.9/lib}
android_jar=${ORONBOX_TEST_ANDROID_JAR:-/opt/android-sdk/platforms/android-35/android.jar}
shizuku_jar=${WATCHFACE_TEST_SHIZUKU_JAR:?Set WATCHFACE_TEST_SHIZUKU_JAR to Shizuku API 13.1.5 classes.jar}
result_log=${1:-/tmp/watchface-native-tests.log}
output=$(mktemp -d /tmp/watchface-native.XXXXXX)
trap 'rm -rf "$output"' EXIT
base=android/app/src/main/kotlin/org/zxor/oronbox
fixtures=test/native/watchface_cache
production=(
  "$base/RootWatchfaceCacheReader.kt" "$base/WatchfaceCacheRootShell.kt"
  "$base/WatchfaceCacheRootPolicy.kt" "$base/WatchfaceCacheMetadata.kt"
  "$base/WatchfaceCacheScan.kt" "$base/WatchfaceCacheBridge.kt"
  "$base/WatchfaceCacheRequest.kt" "$base/WatchfaceCacheShizukuRequest.kt"
  "$base/PlatformChannelNames.kt" "$base/OpenedResourceFiles.kt"
  "$base/PrivateCacheFileIdentity.kt"
)
classpath="$android_jar:$compiler_lib/kotlin-stdlib-1.9.23.jar:$shizuku_jar"
{
  mkdir -p "$output/aidl" "$output/android" "$output/jvm"
  # This checks API signatures only; Gradle --android performs actual AIDL generation.
  javac -classpath "$android_jar" -d "$output/aidl" "$fixtures/aidl_stubs/IWatchfaceCacheShizukuService.java"
  java -Xmx768m -cp "$compiler_lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-jdk -no-stdlib -no-reflect -jvm-target 17 -Werror \
    -classpath "$classpath:$output/aidl" -d "$output/android" \
    "${production[@]}" "$base/WatchfaceCacheShizukuBackend.kt" "$base/WatchfaceCacheShizukuService.kt" \
    "$fixtures"/flutter_stubs/*.kt
  echo 'Narrow Android API compilation: PASS (AIDL signature stub only)'
  java -Xmx768m -cp "$compiler_lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -Werror \
    -classpath "$classpath:$compiler_lib/junit-4.13.2.jar:$compiler_lib/hamcrest-core-1.3.jar" \
    -d "$output/jvm" "${production[@]}" "$fixtures"/backend_stubs/*.kt \
    "$fixtures"/flutter_stubs/*.kt "$fixtures"/android_stubs/*.kt "$fixtures"/*.kt
  java -Xmx768m -cp "$output/jvm:$classpath:$compiler_lib/*" org.junit.runner.JUnitCore \
    org.zxor.oronbox.WatchfaceCacheMetadataTest org.zxor.oronbox.WatchfaceCacheRootPolicyTest \
    org.zxor.oronbox.RootWatchfaceCacheReaderTest org.zxor.oronbox.WatchfaceCacheRootShellTest \
    org.zxor.oronbox.WatchfaceCacheBridgeTest org.zxor.oronbox.OpenedResourceFilesTest \
    org.zxor.oronbox.WatchfaceCacheShizukuContractTest
} 2>&1 | tee "$result_log"
