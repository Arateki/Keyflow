package com.keyflow.ui.config

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Testes da aba Teclado (T13): colar-auto e modo de gravação persistem; "Desbloquear skins" dispara o
 * callback (a sheet real chega na T16).
 */
@RunWith(AndroidJUnit4::class)
class TecladoPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private inner class Harness(initial: Settings) {
        var settings by mutableStateOf(initial)
        var unlockClicks = 0

        @Composable
        fun Content() {
            KeyFlowTheme(dark = false) {
                TecladoPanel(
                    settings = settings,
                    onSettingsChange = { settings = it },
                    onLanguageClick = {},
                    onUnlockSkins = { unlockClicks++ },
                )
            }
        }
    }

    @Test
    fun autoPasteInFieldTogglePersists() {
        val h = Harness(Settings(autoPasteInField = true))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(TECLADO_AUTO_PASTE_IN_FIELD_TAG).performClick()

        rule.runOnIdle { assertEquals(false, h.settings.autoPasteInField) }
    }

    @Test
    fun autoPasteOnFocusTogglePersists() {
        // "Colar ao focar" só fica habilitado com o Modo Sobrepor ligado (senão "gravar fora" não existe).
        val h = Harness(Settings(autoPasteOnFocus = true, overlayModeEnabled = true))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(TECLADO_AUTO_PASTE_ON_FOCUS_TAG).performClick()

        rule.runOnIdle { assertEquals(false, h.settings.autoPasteOnFocus) }
    }

    @Test
    fun unlockSkinsInvokesCallback() {
        val h = Harness(Settings())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(TECLADO_UNLOCK_TAG).performClick()

        rule.runOnIdle { assertTrue(h.unlockClicks > 0) }
    }

    @Test
    fun keyboardSizeSliderPersists() {
        val h = Harness(Settings(keyboardScalePercent = 100))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(TECLADO_SIZE_SLIDER_TAG)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(85f) }

        rule.runOnIdle { assertEquals(85, h.settings.keyboardScalePercent) }
    }

    @Test
    fun keyboardSizePreviewGrowsLiveWithTheSlider() {
        // Pedido do Yan (2026-07-02): ver o tamanho mudar SEM precisar reabrir o teclado de verdade.
        val h = Harness(Settings(keyboardScalePercent = 70))
        rule.setContent { h.Content() }
        rule.waitForIdle()
        val smallHeight = keyboardSizePreviewForTest?.height ?: error("prévia não montou")

        h.settings = h.settings.copy(keyboardScalePercent = 130)
        rule.waitForIdle()
        val bigHeight = keyboardSizePreviewForTest?.height ?: error("prévia não remontou após mudar o setting")

        assertTrue(
            "prévia deveria crescer com o slider (70%=$smallHeight px, 130%=$bigHeight px)",
            bigHeight > smallHeight,
        )
    }

    @Test
    fun keyboardSizePreviewIsCenteredUnderThePanelsRealPadding() {
        // Pedido do Yan (2026-07-03, 2ª rodada): a 1ª versão (um `layout{}` manual "vazando" o padding
        // do painel) tinha um bug de assimetria — a prévia ficava encostada na borda esquerda com folga
        // só do lado direito, mesmo sob o padding SIMÉTRICO real do `ConfigScreen` (12dp dos dois lados,
        // reproduzido aqui — o Harness sozinho não tem esse padding, por isso o bug não aparecia nesta
        // suíte). `Box(contentAlignment = Alignment.Center)` centraliza corretamente sob um ancestral
        // simétrico (o caso real); um ancestral assimétrico deslocaria o centro local — cenário que não
        // ocorre no `ConfigScreen` de produção, então não é o que este teste protege.
        var marginPx = 0
        val h = Harness(Settings(keyboardScalePercent = 100))
        rule.setContent {
            KeyFlowTheme(dark = false) {
                marginPx = with(LocalDensity.current) { PREVIEW_MARGIN_DP.roundToPx() }
                Box(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    h.Content()
                }
            }
        }
        rule.waitForIdle()

        // Posição/tamanho via semantics do Compose (relativos ao ROOT), não `View.getLocationOnScreen`
        // (posição FÍSICA de tela, que pode carregar deslocamento fixo do harness de teste sem relação
        // com o bug reportado).
        val rootWidthPx = rule.onRoot().fetchSemanticsNode().size.width
        val previewNode = rule.onNodeWithTag(TECLADO_SIZE_PREVIEW_TAG).fetchSemanticsNode()
        val leftPx = previewNode.positionInRoot.x
        val widthPx = previewNode.size.width
        val rightPx = rootWidthPx - (leftPx + widthPx)

        assertTrue(
            "prévia deveria ficar centralizada na tela, não vazada pra um lado " +
                "(esquerda=$leftPx px, direita=$rightPx px)",
            kotlin.math.abs(leftPx - rightPx) <= 1,
        )
        // Tolerância maior aqui (8px) que na simetria acima (1px): `LocalConfiguration.screenWidthDp` é
        // um Int arredondado pelo sistema e não bate ao pixel exato com a medida do Compose — o que
        // importa pro bug do Yan é a SIMETRIA (checada acima com tolerância apertada), não o valor
        // absoluto exato da margem.
        assertTrue(
            "margem deveria bater aproximadamente com PREVIEW_MARGIN_DP dos dois lados " +
                "(esquerda=$leftPx px, esperado≈$marginPx px)",
            kotlin.math.abs(leftPx - marginPx) <= 8,
        )
    }
}
