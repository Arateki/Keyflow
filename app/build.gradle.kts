import java.net.URI
import java.security.MessageDigest

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
        // ABIs do sherpa-onnx (STO-02): celulares ARM de 64 e 32 bits; x86_64 só no debug, para o
        // emulador. O x86 de 32 bits que vem no AAR não entra em nenhum.
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
        release {
            isMinifyEnabled = false
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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

// sherpa-onnx (NM-T1): STT on-device. A distribuição Android oficial é só o AAR anexado ao release do
// GitHub (o `com.github.k2-fsa:sherpa-onnx` do JitPack é o jar Java de desktop, sem .so Android).
// São ~50 MB — em vez de versionar o binário, o build baixa a versão fixada quando falta em
// app/libs/ (ignorado no git) e confere o SHA256 abaixo; divergência derruba o build. O espelho no
// HuggingFace (csukuangfj2/sherpa-onnx-libs) NÃO serve: é regravado a cada push do upstream e tem
// outro SHA para o mesmo nome de arquivo.
// Subir de versão = trocar as duas constantes pelo AAR do novo release e o SHA256 dele.
val sherpaOnnxVersion = "1.13.8"
val sherpaOnnxSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaOnnxAar: File = file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")

fun sha256Of(f: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// Na configuração (e não numa task) porque o sync do Android Studio resolve o classpath antes de
// qualquer task rodar. Só baixa quando o arquivo falta ou não bate com o SHA.
if (!sherpaOnnxAar.isFile || sha256Of(sherpaOnnxAar) != sherpaOnnxSha256) {
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/" +
        "sherpa-onnx-$sherpaOnnxVersion.aar"
    logger.lifecycle("sherpa-onnx: baixando $url")
    sherpaOnnxAar.parentFile.mkdirs()
    val partial = File(sherpaOnnxAar.path + ".part")
    URI(url).toURL().openStream().use { input ->
        partial.outputStream().use { output -> input.copyTo(output) }
    }
    val actual = sha256Of(partial)
    if (actual != sherpaOnnxSha256) {
        partial.delete()
        throw GradleException(
            "sherpa-onnx $sherpaOnnxVersion: SHA256 $actual não bate com o fixado ($sherpaOnnxSha256)",
        )
    }
    partial.renameTo(sherpaOnnxAar)
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

    // STT on-device (NM-T1) — ver o bloco sherpa-onnx acima do `dependencies`.
    implementation(files(sherpaOnnxAar))

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
