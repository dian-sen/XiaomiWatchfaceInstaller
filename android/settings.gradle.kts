pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
}

include(":app")
val xmsWearableProject = file("xms-wearable-lib-cleanroom/xms-wearable-lib")
require(xmsWearableProject.resolve("build.gradle.kts").isFile) {
    "XMS Wearable library is missing. Initialize android/xms-wearable-lib-cleanroom " +
        "at commit 6483f939785e9c1dd011465d573931f669a6adab before building Android."
}
include(":xms-wearable-lib")
project(":xms-wearable-lib").projectDir = xmsWearableProject
