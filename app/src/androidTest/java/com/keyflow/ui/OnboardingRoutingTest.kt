package com.keyflow.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.data.SettingsRepository
import com.keyflow.ui.onboarding.ONBOARDING_SKIP_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Rota inicial 1º-uso → onboarding (T27, ONB-03). Semeia `onboardingDone=false` antes do launch; a
 * Activity abre no onboarding. "Pular por agora" persiste `onboardingDone=true` (e a próxima
 * composição iria para a config). `autoAdvance=false` por causa do orbe `Listening` animado.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingRoutingTest {

    private val compose = createAndroidComposeRule<SetupActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(SeedOnboardingRule(done = false))
        .around(compose)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun firstRunShowsOnboardingAndSkipPersistsDone() {
        compose.mainClock.autoAdvance = false
        // A `SetupActivity` compõe um placeholder vazio até o DataStore emitir (`settingsLoaded`), e o
        // relógio congelado acima não gera o frame da recomposição que traria o onboarding — a árvore
        // fica só com o nó raiz e o teste falha com "não achei o nó". Sozinho passa porque a emissão
        // chega antes de congelar; depois do `OverlayServiceCancelTest` o processo está ocupado e ela
        // chega depois (medido em 2026-09-09, com a árvore impressa na falha). Esperar por idle não
        // serve: a orbe `Listening` anima sem parar. Então bombeia o relógio à mão até a tela existir.
        var pumps = 0
        while (compose.onAllNodesWithTag(ONBOARDING_SKIP_TAG).fetchSemanticsNodes().isEmpty() && pumps++ < 100) {
            compose.mainClock.advanceTimeBy(100)
        }

        // A tela inicial é o onboarding (semeado false antes do launch).
        compose.onNodeWithTag(ONBOARDING_SKIP_TAG).performClick()

        // "Pular" persiste onboardingDone=true.
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var persisted = false
        while (SystemClock.elapsedRealtime() < deadline) {
            persisted = runBlocking { SettingsRepository(context).settingsFlow().first().onboardingDone }
            if (persisted) break
            SystemClock.sleep(100)
        }
        assertTrue("onboardingDone deveria ter sido persistido", persisted)
    }

    /**
     * O "Pular" liga o que já está permitido: com o teclado do KeyFlow habilitado mas não ativo, abre o seletor de teclado
     * do Android (`showInputMethodPicker`, assíncrono — sobe ~1 s depois). Se ele subir depois de a Activity fechar, fica
     * aberto com o foco: no gate da main de 2026-10-11 ficou minutos, e a galeria inteira saiu com a barra de status (o
     * modo imersivo só vale com foco); no meio da suíte, rouba o foco dos testes seguintes. Espera até 2 s e fecha.
     */
    @After
    fun closeKeyboardPickerLeftBySkip() {
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (focusedWindow().contains("Select input method")) {
                shell("input keyevent KEYCODE_BACK")
                return
            }
            SystemClock.sleep(100)
        }
    }

    private fun focusedWindow(): String =
        shell("dumpsys window").lineSequence().firstOrNull { "mCurrentFocus" in it }.orEmpty()

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).bufferedReader().readText()
        }
}
