plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

fun quotedEnvironment(name: String): String {
    val value = System.getenv(name).orEmpty()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$value\""
}

android {
    namespace = "com.treepolo.dailyfortune"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.treepolo.dailyfortune"
        minSdk = 26
        targetSdk = 36
        // CI builds use the monotonically increasing GitHub Actions run number so
        // every distributed APK can upgrade the previous one in place.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 2
        versionName = "0.5.0"

        // Empty values keep the app fully offline with embedded experiment defaults.
        // Production builds can inject these once; future experiment changes happen remotely.
        buildConfigField("String", "REMOTE_CONFIG_URL", quotedEnvironment("DAILY_FORTUNE_CONFIG_URL"))
        buildConfigField("String", "ANALYTICS_INGEST_URL", quotedEnvironment("DAILY_FORTUNE_ANALYTICS_URL"))
    }

    signingConfigs {
        create("stableApp") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stableApp")
        }
        release {
            signingConfig = signingConfigs.getByName("stableApp")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room3.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
