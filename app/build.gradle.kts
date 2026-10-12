plugins {
    id("com.android.application")
    kotlin("plugin.compose") version "2.4.0"
}

android {
    namespace = "io.github.northster.xsaver"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.northster.xsaver"
        minSdk = 29 // MediaStore RELATIVE_PATH: saving to the gallery needs no storage permission
        targetSdk = 36
        val build = System.getenv("XS_BUILD_NUMBER")?.toIntOrNull() ?: 0
        // 1.0.N came from the northster/- repository; this one restarts its run numbers, so 1.1.N and a higher base
        versionCode = 1000 + build
        versionName = "1.1.$build"
        ndk {
            // Galaxy Z Fold is arm64; FFmpeg and Python for yt-dlp are native, so other ABIs would only add size
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
    }

    // signing/xtogif.p12 is this app's own key, kept in the repository but locked with a strong password that only
    // lives in the XTOGIF_SIGNING_PASSWORD secret. CI passes it here; without it (local builds) the debug key is used.
    val keystore = rootProject.file("signing/xtogif.p12")
    val keyPassword = System.getenv("XS_SIGNING_PASSWORD")?.takeIf { it.isNotBlank() }
    signingConfigs {
        create("personal") {
            if (keyPassword != null) {
                storeFile = keystore
                storeType = "pkcs12"
                storePassword = keyPassword
                keyAlias = "xtogif"
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            // no R8: FFmpegKit and youtubedl-android call into Java from native code and ship no keep rules
            isMinifyEnabled = false
            signingConfig = if (keyPassword != null) signingConfigs.getByName("personal") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // youtubedl-android runs Python as a program from the extracted native library folder
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName = "XSaver-${variant.buildType}.apk"
            }
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // thumbnails and the animated GIF preview
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("io.coil-kt.coil3:coil-gif:3.3.0")
    implementation("io.coil-kt.coil3:coil-video:3.3.0")

    // GIF conversion: FFmpeg 8.1 in FFmpegKit, maintained fork (the original arthenica builds were retired in 2025).
    // "min" has everything needed here (H.264/HEVC decoding, scale, fps, palettegen/paletteuse, GIF encoder) and is LGPL.
    implementation("com.antonkarpenko:ffmpeg-kit-min:2.2.3")
    // last fallback for getting video URLs: yt-dlp (Python) packaged for Android, can update itself
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
}
