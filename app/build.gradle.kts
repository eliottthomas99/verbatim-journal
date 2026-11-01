import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Credentials come only from the build environment, never a tracked file.
val releaseKeystore = providers.environmentVariable("VERBATIM_KEYSTORE").orNull

android {
    namespace = "app.verbatim"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.verbatim"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        manifestPlaceholders["appLabel"] = "@string/app_name"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Shown in the licences screen: the GPL requires pointing to the source.
        buildConfigField("String", "SOURCE_URL", "\"https://github.com/eliottthomas99/verbatim-journal\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("VERBATIM_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("VERBATIM_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("VERBATIM_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Development happens in a private repository. Never embed its Git
            // revision or remote in an APK; SOURCE_URL points to public source.
            vcsInfo { include = false }
            optimization {
                enable = true
                // Whole-program R8 keeps access changes consistent across Kotlin
                // callers/superclasses; partial package shrinking crashed on device.
            }
        }
        create("releaseCheck") {
            initWith(getByName("release"))
            applicationIdSuffix = ".releasecheck"
            versionNameSuffix = "-test"
            manifestPlaceholders["appLabel"] = "Verbatim Release Test"
            matchingFallbacks += listOf("release")
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

    // Bundle Piper voices; Parakeet is downloaded explicitly on the phone.
    sourceSets {
        getByName("main").assets.srcDirs(
            // Piper voices + espeak-ng data for reading answers aloud (tools/prepare_tts.py)
            rootProject.layout.projectDirectory.dir(".local-models/tts/assets")
        )
        getByName("androidTest").assets.srcDirs(
            layout.buildDirectory.dir("generated/asr-test-assets").get().asFile
        )
    }

}

// Same for the voices that read answers aloud (checksums are verified by the script).
val verifyTtsAssets = tasks.register("verifyTtsAssets") {
    val tts = rootProject.layout.projectDirectory.dir(".local-models/tts")
    doLast {
        listOf("sherpa-onnx.aar", "assets/tts/fr/model.onnx", "assets/tts/en/model.onnx", "assets/tts/espeak-ng-data/phondata")
            .forEach { check(tts.file(it).asFile.isFile) { "Missing .local-models/tts/$it. Run python -I tools/prepare_tts.py" } }
        val manifest = groovy.json.JsonSlurper().parse(rootProject.file("tools/native_licenses.json")) as List<*>
        manifest.forEach { entry ->
            val spec = entry as Map<*, *>
            val file = tts.file("assets/licenses/${spec["name"]}").asFile
            check(file.isFile) { "Missing licence ${spec["name"]}. Run python -I tools/prepare_licenses.py" }
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            check(digest.joinToString("") { "%02x".format(it) } == spec["sha256"]) { "Invalid licence ${spec["name"]}" }
        }
        check(tts.file("assets/licenses/GPL-3.0.txt").asFile.readBytes().contentEquals(rootProject.file("LICENSE").readBytes())) {
            "GPL text is missing or outdated. Run python -I tools/prepare_licenses.py"
        }
        check(tts.file("assets/licenses/NOTICES.txt").asFile.readBytes().contentEquals(rootProject.file("THIRD_PARTY_NOTICES.md").readBytes())) {
            "Notices are missing or outdated. Run python -I tools/prepare_licenses.py"
        }
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
