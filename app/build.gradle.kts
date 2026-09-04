import java.net.URI
import java.util.zip.ZipInputStream

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

val generatedKaiResDir = layout.buildDirectory.dir("generated/res/kai").get().asFile
val generatedKaiFont = generatedKaiResDir.resolve("font/tw_kai_98_1.ttf")

val prepareKaiFont by tasks.registering {
    outputs.file(generatedKaiFont)
    doLast {
        val target = generatedKaiFont
        if (target.exists() && target.length() > 1_000_000L) return@doLast

        target.parentFile.mkdirs()
        val cacheDir = layout.buildDirectory.dir("font-cache").get().asFile
        cacheDir.mkdirs()
        val zipFile = cacheDir.resolve("Fonts_Kai.zip")

        if (!zipFile.exists() || zipFile.length() < 1_000_000L) {
            val url = URI("https://www.cns11643.gov.tw/opendata/Fonts_Kai.zip").toURL()
            val connection = url.openConnection().apply {
                setRequestProperty("User-Agent", "daily-fortune-android-build/0.6.9")
                connectTimeout = 20_000
                readTimeout = 120_000
            }
            connection.getInputStream().use { input ->
                zipFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        var extracted = false
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith("TW-Kai-98_1.ttf")) {
                    target.outputStream().buffered().use { output -> zip.copyTo(output) }
                    extracted = true
                    break
                }
            }
        }
        check(extracted && target.length() > 1_000_000L) {
            "Unable to extract TW-Kai-98_1.ttf from the official CNS11643 font package"
        }
    }
}

val releaseKeystorePath = System.getenv("DAILY_FORTUNE_RELEASE_KEYSTORE_PATH").orEmpty()
val releaseStorePassword = System.getenv("DAILY_FORTUNE_RELEASE_STORE_PASSWORD").orEmpty()
val releaseKeyAlias = System.getenv("DAILY_FORTUNE_RELEASE_KEY_ALIAS").orEmpty()
val releaseKeyPassword = System.getenv("DAILY_FORTUNE_RELEASE_KEY_PASSWORD").orEmpty()
val canSignRelease = listOf(
    releaseKeystorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it.isNotBlank() }

android {
    namespace = "com.treepolo.dailyfortune"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.treepolo.dailyfortune"
        minSdk = 26
        targetSdk = 36
        // CI builds use the monotonically increasing GitHub Actions run number so
        // every distributed QA APK can upgrade the previous one in place.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 2
        versionName = "0.6.9"

        // Empty values keep the app fully offline with embedded experiment defaults.
        // Production builds inject these once; future experiment and ad-policy changes happen remotely.
        buildConfigField("String", "REMOTE_CONFIG_URL", quotedEnvironment("DAILY_FORTUNE_CONFIG_URL"))
        buildConfigField("String", "ANALYTICS_INGEST_URL", quotedEnvironment("DAILY_FORTUNE_ANALYTICS_URL"))
    }

    sourceSets.getByName("main").res.srcDir(generatedKaiResDir)

    signingConfigs {
        // QA/debug builds keep the historical repository debug key so existing device installs
        // remain upgradeable during development. It is never used for the Play production bundle.
        create("stableDebug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (canSignRelease) {
            create("releaseUpload") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stableDebug")
        }
        release {
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("releaseUpload")
            }
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

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(prepareKaiFont)
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

    implementation(libs.google.mobile.ads)
    implementation(libs.google.ump)

    testImplementation(libs.junit)
}
