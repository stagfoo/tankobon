import java.util.Properties

plugins {
    id("com.android.application")
}

// The Next Up address isn't in the (public) repo: it comes from nextup.properties
// (gitignored), e.g. `url=https://recs.example.workers.dev`. Without it the app just reads.
val nextUp = Properties().apply {
    rootProject.file("nextup.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.tankobon.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tankobon.app"
        minSdk = 30 // All files access (MANAGE_EXTERNAL_STORAGE) arrived in Android 11.
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.3"
        buildConfigField("String", "NEXT_UP_URL", "\"${nextUp.getProperty("url", "")}\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        // Committed debug key so a build from any machine installs as an update
        // over a build from any other. Without it AGP invents a fresh throwaway
        // key per environment and Android refuses the install.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
