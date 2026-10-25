package com.keyflow.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.data.SettingsRepository
import com.keyflow.ui.onboarding.DISCLOSURE_ACCEPT_TAG
import com.keyflow.ui.onboarding.DISCLOSURE_CONTINUE_TAG
import com.keyflow.ui.onboarding.DISCLOSURE_DECLINE_TAG
import com.keyflow.ui.onboarding.DISCLOSURE_DECLINED_EXPLANATION_TAG
import com.keyflow.ui.config.ACTIVATE_MIC_TAG
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
    fun firstRunRequiresDisclosureBeforeMicPermissionAndDeclineCanBeReopened() {
        compose.mainClock.autoAdvance = false
        // A `SetupActivity` compõe um placeholder vazio até o DataStore emitir (`settingsLoaded`). Com o relógio
        // congelado a árvore só ganha a divulgação se o teste empurrar o relógio, e o prazo tem de ser em tempo real:
        // contar quadros acabava antes da emissão num emulador ocupado (CI de 2026-10-24, duas vezes).
        compose.pumpUntilTag(DISCLOSURE_DECLINE_TAG)

        // Primeiro uso deve exibir a divulgação antes de qualquer permissão.
        compose.onNodeWithTag(DISCLOSURE_DECLINE_TAG).assertExists()
        compose.onNodeWithTag(DISCLOSURE_DECLINE_TAG).performClick()
        compose.pumpUntilTag(DISCLOSURE_DECLINED_EXPLANATION_TAG)
        compose.onNodeWithTag(DISCLOSURE_DECLINED_EXPLANATION_TAG).assertExists()
        compose.onNodeWithTag(DISCLOSURE_CONTINUE_TAG).performClick()

        // Recusar e continuar persiste a conclusão sem consentimento.
        val deadline = SystemClock.elapsedRealtime() + PUMP_TIMEOUT_MS
        var persisted = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = runBlocking { SettingsRepository(context).settingsFlow().first() }
            persisted = current.onboardingDone && current.disclosureDeclined
            if (persisted) break
            SystemClock.sleep(100)
        }
        assertTrue("recusa e conclusão sem ditado deveriam ter sido persistidas", persisted)
        compose.pumpUntilTag(ACTIVATE_MIC_TAG)
        compose.onNodeWithTag(ACTIVATE_MIC_TAG).performClick()
        compose.pumpUntilTag(DISCLOSURE_ACCEPT_TAG)
        compose.onNodeWithTag(DISCLOSURE_CONTINUE_TAG).assertExists()
        compose.onNodeWithTag(DISCLOSURE_ACCEPT_TAG).assertExists()
        compose.onNodeWithTag(DISCLOSURE_DECLINED_EXPLANATION_TAG).assertExists()
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
