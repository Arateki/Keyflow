package com.keyflow.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gera o perfil de base do KeyFlow: o caminho que o ART deve deixar compilado de antemão na instalação, em vez de interpretar
 * e compilar na hora (JIT) — a abertura a frio do teclado é o caminho que o Android cronometra (1º quadro em 1 s).
 *
 *   ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateBaselineProfile
 *
 * Grava em app/src/release/generated/baselineProfiles/. Só emulador (ver baselineprofile/build.gradle.kts).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = TARGET, includeInStartupProfile = true) {
        // 1. O app a frio (tela inicial em Compose).
        pressHome()
        startActivityAndWait()
        // 2. O teclado a frio sobre outro app, digitando um pouco e fechando.
        killProcess()
        useKeyFlowKeyboard()
        openField()
        val frame = tapFieldAndAwaitKeyboard()
        typeSomething(frame)
        closeKeyboard()
    }
}
