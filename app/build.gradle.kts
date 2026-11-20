import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing, from (in order): CI env vars, or ~/.config/morph-signing/keystore.properties.
 * Absent both (e.g. PR builds), release falls back to the debug key.
 */
val signing: Map<String, String>? = run {
    System.getenv("MORPH_KEYSTORE_FILE")?.let { file ->
        return@run mapOf(
            "storeFile" to file,
            "storePassword" to System.getenv("MORPH_KEYSTORE_PASSWORD").orEmpty(),
            "keyAlias" to (System.getenv("MORPH_KEY_ALIAS") ?: "morph"),
            "keyPassword" to System.getenv("MORPH_KEYSTORE_PASSWORD").orEmpty(),
        )
    }
    val local = File(System.getProperty("user.home"), ".config/morph-signing/keystore.properties")
    if (!local.exists()) return@run null
    Properties().apply { local.inputStream().use(::load) }.let { p -> p.stringPropertyNames().associateWith { p.getProperty(it) } }
}

// Nightlies pass -PversionCode=YYMMDDNN -PversionName=nightly-YYYYMMDD
val morphVersionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
val morphVersionName = (findProperty("versionName") as String?) ?: "0.1.0-dev"

android {
    namespace = "bhupendra.morph"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "bhupendra.morph"
        // No backwards compatibility: this is a vision prototype for Android 16+.
        minSdk = 36
        targetSdk = 37
        versionCode = morphVersionCode
        versionName = morphVersionName
    }

    signingConfigs {
        if (signing != null) create("release") {
            storeFile = file(signing.getValue("storeFile"))
            storePassword = signing["storePassword"]
            keyAlias = signing["keyAlias"]
            keyPassword = signing["keyPassword"]
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
