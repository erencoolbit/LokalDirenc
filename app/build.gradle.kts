import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.lokaldirenc.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.lokaldirenc.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-prototip"
    }

    buildFeatures {
        compose = true
    }

    // Anahtar ve şifresi bilerek proje dışında: proje klasörü OneDrive'a eşitleniyor.
    val signingFile = file("${System.getProperty("user.home")}/.lokaldirenc/signing.properties")
    if (signingFile.exists()) {
        val props = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
        // Donanımsız deneme: debug + sahte LoRa istasyonu. Dağıtılan APK'ya girmez.
        create("sim") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") {
        isTransitive = false
    }
}
