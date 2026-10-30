plugins {
    id("com.android.application") version "9.2.0" apply false
    // AGP 9.2.0 mantém o Kotlin embutido; a declaração abaixo eleva o KGP mínimo 2.2.10 para 2.4.21.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    // Compilador Compose alinhado ao KGP 2.4.21, mantendo AGP 9.2.0 e Gradle 9.6.1.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    // Módulo :baselineprofile (perfil de base + benchmarks de abertura): APK de teste que mira o :app.
    id("com.android.test") version "9.2.0" apply false
    // 1.5.0: estável de 2026-09-09 no Maven do Google.
    id("androidx.baselineprofile") version "1.5.0" apply false
}
