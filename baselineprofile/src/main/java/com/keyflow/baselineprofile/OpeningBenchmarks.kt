package com.keyflow.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Abertura a frio do app e do teclado, sem e com o perfil de base — a diferença entre as duas é o ganho do perfil.
 *
 *   ANDROID_SERIAL=emulator-5554 ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 *
 * Resultados em baselineprofile/build/outputs/connected_android_test_additional_output/. Número de emulador só vale comparado
 * com outro da mesma máquina.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class OpeningBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun appColdStartWithoutProfile() = appColdStart(CompilationMode.None())

    @Test fun appColdStartWithProfile() = appColdStart(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test fun keyboardColdOpenWithoutProfile() = keyboardColdOpen(CompilationMode.None())

    @Test fun keyboardColdOpenWithProfile() = keyboardColdOpen(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun appColdStart(compilation: CompilationMode) = rule.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    /**
     * O processo do KeyFlow morto, o campo já aberto noutro app; mede do toque no campo até a janela do teclado assentar. As
     * seções vêm do KeyFlowKeyboardService (Trace.beginSection) — o tempo de cada parte na thread principal.
     */
    private fun keyboardColdOpen(compilation: CompilationMode) = rule.measureRepeated(
        packageName = TARGET,
        metrics = listOf(
            TraceSectionMetric("KeyFlow.keyboardOpen", TraceSectionMetric.Mode.First),
            TraceSectionMetric("KeyFlow.onCreateInputView", TraceSectionMetric.Mode.First),
            TraceSectionMetric("KeyFlow.onStartInputView", TraceSectionMetric.Mode.First),
            TraceSectionMetric("bindApplication", TraceSectionMetric.Mode.First),
        ),
        compilationMode = compilation,
        iterations = ITERATIONS,
        setupBlock = {
            killProcess()
            useKeyFlowKeyboard()
            openField()
        },
    ) {
        tapFieldAndAwaitKeyboard()
        closeKeyboard()
    }

    private companion object {
        const val ITERATIONS = 10
    }
}
