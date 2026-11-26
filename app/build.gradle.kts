plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "sk.domov.ovladanie"
    compileSdk = 35

    defaultConfig {
        applicationId = "sk.domov.ovladanie"
        minSdk = 26
        targetSdk = 35
        versionCode = 88
        versionName = "1.66.3"
    }

    // Pevný ladiaci kľúč: každý build má rovnaký podpis, takže sa nová verzia dá nainštalovať
    // priamo cez starú (inak by ju Android odmietol, lebo GitHub vytvára nový kľúč pri každom builde).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Release build: bez ladiaceho režimu, R8 zmenší a zoptimalizuje kód (rýchlejší štart, menej práce CPU = menej batérie).
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // rovnaký kľúč ako debug build → release sa nainštaluje priamo cez doterajšiu verziu, bez straty údajov
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG: ukážkový režim (extra "demo") len v ladiacom builde
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    // widget Nákup: zoznam položiek vo widgete (RemoteCollectionItems, aj na Androide < 12)
    implementation("androidx.core:core-remoteviews:1.1.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // QR kód pozvánky do domácnosti (len generovanie, bez kamery)
    implementation("com.google.zxing:core:3.5.3")
    // skenovanie QR kódu pri pripojení k domácnosti (malá knižnica nad zxing, vlastná obrazovka kamery)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
}
