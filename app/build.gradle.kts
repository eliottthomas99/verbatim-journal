import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    ndkVersion = "28.2.13676358"
    namespace = "app.verbatim"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.verbatim"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Shown in the licences screen: the GPL requires pointing to the source.
        buildConfigField("String", "SOURCE_URL", "\"https://github.com/eliottthomas99/verbatim-journal\"")
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs.excludes += listOf("lib/armeabi-v7a/**", "lib/x86/**", "lib/x86_64/**")
    }

    // Bundle Whistle model + licenses into APK assets (not downloaded on phone)
    sourceSets {
        getByName("main").assets.srcDirs(
            rootProject.layout.projectDirectory.dir(".local-models/whistle/assets"),
            // Piper voices + espeak-ng data for reading answers aloud (tools/prepare_tts.py)
            rootProject.layout.projectDirectory.dir(".local-models/tts/assets")
        )
        getByName("androidTest").assets.srcDirs(
            layout.buildDirectory.dir("generated/whistle-test-assets").get().asFile
        )
    }

    // CMake build targeting the Needle (Whistle) JNI library.
    // Only arm64-v8a (modern Android devices).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    androidComponents {
        onVariants { variant ->
            variant.externalNativeBuild?.abiFilters?.set(setOf("arm64-v8a"))
        }
    }
}

// Do not silently build an APK without its offline model.
val verifyWhistleAssets = tasks.register("verifyWhistleAssets") {
    val assets = rootProject.layout.projectDirectory.dir(".local-models/whistle")
    inputs.dir(assets)
    doLast {
        val hashes = mapOf(
            "assets/asr/whistle.cact" to "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb",
            "native/arm64-v8a/libneedle.a" to "7aa6de6132a22bf45f5e903425feb66f159ad46f47fa558921bc4fd02c3a2fa0",
            "native/needle.h" to "a27dac4b3239eead91d124b7f7dbce5495c13019903fd817b68c16d39b055a99",
            "assets/asr/WHISTLE-LICENSE.txt" to "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
            "assets/asr/NEEDLE-LICENSE.txt" to "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
        )
        hashes.forEach { (path, expected) ->
            val file = assets.file(path).asFile
            check(file.isFile) { "Missing $path. Run python -I tools/prepare_whistle.py" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == expected) {
                "Invalid $path. Run python -I tools/prepare_whistle.py"
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyWhistleAssets) }

// Same for the voices that read answers aloud (checksums are verified by the script).
val verifyTtsAssets = tasks.register("verifyTtsAssets") {
    val tts = rootProject.layout.projectDirectory.dir(".local-models/tts")
    doLast {
        listOf("sherpa-onnx.aar", "assets/tts/fr/model.onnx", "assets/tts/en/model.onnx", "assets/tts/espeak-ng-data/phondata")
            .forEach { check(tts.file(it).asFile.isFile) { "Missing .local-models/tts/$it. Run python -I tools/prepare_tts.py" } }
    }
}
tasks.named("preBuild") { dependsOn(verifyTtsAssets) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.migration)
    ksp(libs.androidx.room.compiler)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    // Official k2-fsa sherpa-onnx runtime (not on Maven): pinned + verified by tools/prepare_tts.py
    implementation(files(rootProject.layout.projectDirectory.file(".local-models/tts/sherpa-onnx.aar")))
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
