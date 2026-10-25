plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

// Perfil de base (Baseline Profile) e benchmarks de abertura do KeyFlow — ver .specs/codebase/TESTING.md.
// Instala uma variante RELEASE do app (mesmo applicationId com.arateki.keyflow, assinada com a keystore de debug): rode com
// ANDROID_SERIAL apontando para um EMULADOR. Num aparelho com o app de debug, a troca de variante apaga os dados.
android {
    namespace = "com.keyflow.baselineprofile"
    compileSdk = 36

    defaultConfig {
        // Gerar perfil sem root pede Android 13+ (API 33); medir aceita 28+.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Emulador: o Macrobenchmark recusa medir nele por padrão (números de emulador não valem como absolutos). Aqui a
        // comparação é relativa — com e sem o perfil, na mesma máquina.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.0")
    // 1.5.0: estável de 2026-09-09 no Maven do Google (a 1.6.0 ainda é alpha).
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
