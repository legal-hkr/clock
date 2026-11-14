plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "sh.hnet.clock"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "sh.hnet.clock"
        minSdk = 34
        targetSdk = 37
        versionCode = 3
        versionName = "v0.3.0"
    }

    buildTypes {
        release {
            // R8: unused code and resources out, the rest renamed (rules in proguard-rules.pro)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    androidResources {
        generateLocaleConfig = true
        // Libraries' translations limited to the app's languages
        localeFilters += listOf("en", "pl")
    }
    // F-Droid: no dependency metadata blob in the APK
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// Disable baseline profile generation for F-Droid reproducible builds
// https://f-droid.org/docs/Reproducible_Builds/
tasks.configureEach {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.preferences)


    testImplementation(libs.junit)
}

/**
 * Copies this version's F-Droid changelogs (metadata/<locale>/changelogs/<versionCode>.txt, English and any
 * translations written for the release) into the app's assets as whats_new/<locale>.txt, for the What's new page.
 * Without an English one, the app shows no What's new. As in ComfyChair.
 */
abstract class WhatsNewAssetTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val changelogs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = File(outputDir.get().asFile, "whats_new")
        outputDir.get().asFile.deleteRecursively()
        dir.mkdirs()
        // metadata/<locale>/changelogs/<versionCode>.txt -> whats_new/<locale>.txt
        changelogs.files.filter { it.isFile }.forEach { it.copyTo(File(dir, "${it.parentFile.parentFile.name}.txt")) }
    }
}

val whatsNewAsset = tasks.register<WhatsNewAssetTask>("whatsNewAsset") {
    changelogs.from(
        rootProject.fileTree("metadata") { include("*/changelogs/${android.defaultConfig.versionCode}.txt") }
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(whatsNewAsset, WhatsNewAssetTask::outputDir)
    }
}
