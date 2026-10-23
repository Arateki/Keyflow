package com.keyflow.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.auth.CredentialMask
import com.keyflow.core.TranscriptionSource
import com.keyflow.data.Settings
import com.keyflow.ui.theme.KeyFlowTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Testes da aba Voz (T12 / VOZ-01..06): selecionar a origem persiste, Créditos fica indisponível e
 * editar o token persiste via auto-save (ImeAction Done).
 */
@RunWith(AndroidJUnit4::class)
class VozPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeApiKeyActions(initial: String = "") : AuthActions by AuthActions.None {
        private var raw = initial
        private val mask = MutableStateFlow(CredentialMask.of(initial))

        val saved: String get() = raw

        override fun apiKey(): String? = mask.value
        override fun apiKeyFlow(): Flow<String?> = mask
        override fun saveApiKey(key: String) {
            raw = key
            mask.value = CredentialMask.of(key)
        }
    }

    private inner class Harness(initial: Settings, val openAiAuth: AuthActions = AuthActions.None) {
        var settings by mutableStateOf(initial)

        @Composable
        fun Content() {
            KeyFlowTheme(dark = false) {
                VozPanel(settings = settings, onSettingsChange = { settings = it }, openAiAuth = openAiAuth)
            }
        }
    }

    @Test
    fun selectingSourcePersists() {
        val h = Harness(Settings(source = TranscriptionSource.CREDITS))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(sourceTag(TranscriptionSource.OPENAI)).performClick()

        rule.runOnIdle { assertEquals(TranscriptionSource.OPENAI, h.settings.source) }
    }

    /** MGR-01: "Local" é a 5ª opção; escolhida, mostra o gerenciador de modelos, sem campos de token. */
    @Test
    fun localIsTheFifthSourceAndShowsItsPanel() {
        val h = Harness(Settings(source = TranscriptionSource.CREDITS))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(sourceTag(TranscriptionSource.LOCAL)).assertIsDisplayed().performClick()

        rule.runOnIdle { assertEquals(TranscriptionSource.LOCAL, h.settings.source) }
        rule.onNodeWithTag(VOZ_LOCAL_PRIVACY_TAG).assertExists()
        rule.onAllNodesWithTag(VOZ_TOKEN_TAG).assertCountEquals(0)
    }

    /** PAR-01: os dois toggles do parcial persistem; o 2º só responde com o 1º ligado. */
    @Test
    fun partialToggles() {
        val h = Harness(Settings(source = TranscriptionSource.OPENAI))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(VOZ_PARTIAL_VIEW_EDIT_TAG).assertIsNotEnabled()
        rule.onNodeWithTag(VOZ_PARTIAL_ON_PAUSE_TAG).performClick()
        rule.runOnIdle { assertEquals(true, h.settings.partialOnPause) }

        rule.onNodeWithTag(VOZ_PARTIAL_VIEW_EDIT_TAG).assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(true, h.settings.partialViewEdit) }
    }

    @Test
    fun legacyCreditsFallsBackToCustomAndShowsDisabledOption() {
        val h = Harness(Settings(source = TranscriptionSource.CREDITS))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(sourceTag(TranscriptionSource.CUSTOM)).assertIsDisplayed()
        rule.onNodeWithTag(sourceTag(TranscriptionSource.CREDITS))
            .assertIsNotEnabled()
            .assertTextContains("Em breve", substring = true)
        rule.onAllNodesWithTag(VOZ_CREDITS_CARD_TAG).assertCountEquals(0)
        rule.onAllNodesWithTag(VOZ_TOKEN_TAG).assertCountEquals(0)
    }

    @Test
    fun editingTokenPersistsOnImeDone() {
        val auth = FakeApiKeyActions()
        val h = Harness(Settings(source = TranscriptionSource.OPENAI), auth)
        rule.setContent { h.Content() }

        rule.onNodeWithTag(VOZ_TOKEN_TAG).performTextReplacement("sk-new-token")
        rule.onNodeWithTag(VOZ_TOKEN_TAG).performImeAction()

        rule.runOnIdle { assertEquals("sk-new-token", auth.saved) }
    }

    @Test
    fun timeoutDefaultsTo15SecondsSelected() {
        val h = Harness(Settings(source = TranscriptionSource.OPENAI))
        rule.setContent { h.Content() }

        // O cabeçalho do provedor e a origem em duas linhas empurraram a seção "Geral" para baixo da dobra: rola até lá.
        rule.onNodeWithTag(timeoutTag(15_000)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun selectingTimeoutPersists() {
        val h = Harness(Settings(source = TranscriptionSource.OPENAI))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(timeoutTag(30_000)).performScrollTo().performClick()

        rule.runOnIdle { assertEquals(30_000, h.settings.transcriptionTimeoutMs) }
    }

    @Test
    fun timeoutSectionIsSharedAcrossEveryProvider() {
        // Não é por preset: o legado CREDITS cai no Custom e continua compartilhando Geral.
        val h = Harness(Settings(source = TranscriptionSource.CREDITS))
        rule.setContent { h.Content() }

        rule.onAllNodesWithTag(timeoutTag(15_000)).assertCountEquals(1)
    }

    @Test
    fun maxRecordingDefaultsTo5MinutesSelected() {
        val h = Harness(Settings(source = TranscriptionSource.OPENAI))
        rule.setContent { h.Content() }

        // A aba ganhou respiro entre as áreas (2026-09-28): "Geral" fica abaixo da dobra — rola até lá.
        rule.onNodeWithTag(maxRecordingTag(300)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun selectingMaxRecordingPersists() {
        val h = Harness(Settings(source = TranscriptionSource.OPENAI))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(maxRecordingTag(600)).performScrollTo().performClick()

        rule.runOnIdle { assertEquals(600, h.settings.maxRecordingSec) }
    }

    @Test
    fun infinityOptionTurnsBothLimitsOff() {
        // "∞" (Yan, 2026-09-28): 0 = sem limite, nos dois seletores da seção Geral. Desenhado como ícone;
        // o leitor de tela ouve "Sem limite".
        val h = Harness(Settings(source = TranscriptionSource.OPENAI))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(timeoutTag(0)).performScrollTo().assertContentDescriptionEquals("Sem limite").performClick()
        rule.onNodeWithTag(maxRecordingTag(0)).performScrollTo().assertContentDescriptionEquals("Sem limite").performClick()

        rule.runOnIdle {
            assertEquals(0, h.settings.transcriptionTimeoutMs)
            assertEquals(0, h.settings.maxRecordingSec)
        }
    }
}
