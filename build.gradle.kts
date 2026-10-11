plugins {
    id("com.android.application") version "9.2.0" apply false
    // AGP 9.2.0 embute KGP 2.2.10 (built-in Kotlin); este apply-false só documenta a versão real.
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    // Compilador Compose: versão atrelada ao Kotlin embutido pelo AGP (2.2.10), não fabricada.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    // Módulo :baselineprofile (perfil de base + benchmarks de abertura): APK de teste que mira o :app.
    id("com.android.test") version "9.2.0" apply false
    // 1.5.0: estável de 2026-09-09 no Maven do Google.
    id("androidx.baselineprofile") version "1.5.0" apply false
}
