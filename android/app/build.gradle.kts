import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // The Flutter Gradle Plugin must follow the Android and Kotlin plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val keyProperties = Properties()
rootProject.file("key.properties").takeIf { it.isFile }?.inputStream()?.use {
    keyProperties.load(it)
}
fun signingValue(environmentName: String, propertyName: String): String? =
    providers.environmentVariable(environmentName).orNull?.takeIf { it.isNotBlank() }
        ?: keyProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseStorePath = signingValue("SIGNING_STORE_FILE", "storeFile")
val releaseStorePassword = signingValue("SIGNING_STORE_PASSWORD", "storePassword")
val releaseKeyPassword = signingValue("SIGNING_KEY_PASSWORD", "keyPassword")
val releaseKeyAlias = signingValue("SIGNING_KEY_ALIAS", "keyAlias") ?: "release"
val releaseStoreFile = releaseStorePath?.let { file(it) }
val releaseSigningReady = releaseStoreFile?.isFile == true &&
    releaseStorePassword != null && releaseKeyPassword != null && releaseKeyAlias == "release"

// Debug/analyze tasks work without credentials. Any release task must fail closed.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.project == project && it.name.contains("release", ignoreCase = true) }) {
        check(releaseSigningReady) {
            "Release requires an existing keystore, store/key passwords and alias 'release'. " +
                "Set SIGNING_STORE_FILE, SIGNING_STORE_PASSWORD and SIGNING_KEY_PASSWORD " +
                "or android/key.properties; debug signing is never used for release."
        }
    }
}

android {
    namespace = "org.zxor.oronbox"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "dev.xiaom.watchfaceinstaller"
        manifestPlaceholders["oronboxAppLabel"] = "Watchface Studio"
        // The native cache reader uses java.nio.file (Android API 26).
        minSdk = 26
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        // Flutter supplies a broad default list. Adding to that list does not restrict it.
        ndk.abiFilters.clear()
        when (val abi = System.getenv("PERSONAL_ABI")?.takeIf { it.isNotBlank() } ?: "arm64-v8a") {
            "all" -> ndk.abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a", "x86_64"))
            "arm64-v8a", "armeabi-v7a", "x86_64" -> ndk.abiFilters.add(abi)
            else -> error("Unsupported PERSONAL_ABI: $abi")
        }
    }

    buildFeatures {
        aidl = true
    }

    signingConfigs {
        create("release") {
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            storeFile = releaseStoreFile
            storePassword = releaseStorePassword
        }
    }

    // Keep native libraries uncompressed so Android loads them from the APK.
    packaging {
        jniLibs {
            useLegacyPackaging = false
            excludes += setOf(
                "**/libonnxruntime.so",
                "**/libsherpa-onnx-c-api.so",
                "**/libsherpa-onnx-cxx-api.so",
            )
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation(project(":xms-wearable-lib"))
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
