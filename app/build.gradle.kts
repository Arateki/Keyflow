plugins {
    id("com.android.application")
    // AGP 9.2.0 aplica o Kotlin internamente; aqui só o plugin do compilador Compose.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.keyflow"
    compileSdk = 36

    // Fixa a keystore debug num arquivo só: esta máquina tem duas (~/.android/debug.keystore e
    // ~/.config/.android/debug.keystore) com fingerprints diferentes, e o AGP resolve a "padrão" pelas
    // variáveis de ambiente do momento — se o build pegar uma diferente da que assinou a versão já
    // instalada, o Android exige desinstalar antes de instalar, apagando os dados do app (aconteceu de
    // verdade, Yan 2026-07-03). Fixando aqui, todo `installDebug` sempre usa a mesma.
    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.config/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.keyflow"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // UI
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // ExploreByTouchHelper (KC-T8/A11Y-01..02): nós virtuais de acessibilidade na grade Canvas.
    implementation("androidx.customview:customview:1.1.0")

    // Jetpack Compose (BOM 2024.09.03 → Compose 1.7.x, compatível com compileSdk 34)
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // HTTP — upload multipart para API Whisper
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Config persistida
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Auth key criptografada (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- Testes ---
    // Unit
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.google.truth:truth:1.4.4")
    // org.json real p/ os unit tests JVM (o android.jar mockado lança em JSONObject/JSONArray); o app usa
    // o org.json do próprio Android — zero dependência nova no APK (ShortcutCodec, SB-T1).
    testImplementation("org.json:json:20240303")

    // Instrumented
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
