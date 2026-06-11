// SPDX-License-Identifier: GPL-2.0-or-later
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

plugins {
    alias(libs.plugins.android.application)
}

// Build identity stamped into BuildConfig (mirrors the main AnyFile app). The CI
// release runner passes -PgitSha / -PbuildTimestamp; local builds fall back to a
// live git call. Used for the About/version line and (later, Phase 4) the headless
// update check.
val gitShaValue: String = (project.findProperty("gitSha") as String?)?.takeIf { it.isNotBlank() }
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            workingDir = rootDir
        }.standardOutput.asText.get().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "dev"

val buildTimestamp: String = (project.findProperty("buildTimestamp") as String?)?.takeIf { it.isNotBlank() }
    ?: DateTimeFormatter.ISO_INSTANT
        .format(Instant.now().truncatedTo(ChronoUnit.SECONDS))

// Semantic version — the single source of truth for this app's releases (#187). Bump this per
// release; CI cuts a tagged `vX.Y.Z` GitHub release from it (see .github/workflows/build.yml), and
// AnyFile's companion-apps section compares installed-vs-latest by semver ordering to drive its
// Install / Update buttons. `versionCode` is DERIVED from the semver so the OS install/update
// ordering matches: MAJOR*10000 + MINOR*100 + PATCH (each component < 100). A tag push verifies the
// tag matches this value (CI guard), so the build.gradle version and the git tag can't drift.
val appVersionName = "0.2.0"
val appVersionCode: Int = appVersionName.split(".").let { (maj, min, pat) ->
    maj.toInt() * 10000 + min.toInt() * 100 + pat.toInt()
}

android {
    namespace = "com.nachtalb.anyfiledjvu"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nachtalb.anyfiledjvu"
        minSdk = 28
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GIT_SHA", "\"$gitShaValue\"")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"$buildTimestamp\"")

        // Pin the NDK to the version verified during the Phase 0 cross-compile spike.
        // CI installs this exact version via sdkmanager so the toolchain (and the
        // c++14/register + no-libjpeg build behaviour) is identical to local.
        ndkVersion = "28.2.13676358"

        ndk {
            // Release targets real devices (arm only). The emulator x86_64 slice is
            // added for debug builds (see buildTypes.debug) so on-device smoke tests
            // run on the Pixel 3a AVD.
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++14"
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += "x86_64"
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    signingConfigs {
        // Shared committed debug key (same cert as AnyFile) so the signature-level
        // BIND_CONVERTER permission lets AnyFile bind without a prompt, and so the
        // companion installs + self-updates with a stable certificate.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("androidTest").java.srcDirs("src/androidTest/kotlin")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
