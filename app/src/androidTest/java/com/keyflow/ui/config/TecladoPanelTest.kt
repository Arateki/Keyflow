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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.orbe.AcaoDaOrbeDoTeclado
import com.keyflow.orbe.ConfigOrbeTeclado
import com.keyflow.orbe.ModoDaOrbe
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithText
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

    private inner class Harness(
        initial: Settings,
        val skinsUnlocked: Boolean = false,
        orbe: ConfigOrbeTeclado = ConfigOrbeTeclado(),
    ) {
        var settings by mutableStateOf(initial)
        var orbe by mutableStateOf(orbe)
        var orbeWrites = 0
        var unlockClicks = 0

        @Composable
        fun Content() {
            KeyFlowTheme(dark = false) {
                TecladoPanel(
                    settings = settings,
                    onSettingsChange = { settings = it },
                    onUnlockSkins = { unlockClicks++ },
                    skinsUnlocked = skinsUnlocked,
                    orbeConfig = orbe,
                    onOrbeConfigChange = { orbe = it; orbeWrites++ },
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
    fun showWithHardKeyboardTogglePersists() {
        val h = Harness(Settings(keyboardShowWithHardKeyboard = true))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(TECLADO_SHOW_WITH_HARD_KEYBOARD_TAG).performScrollTo().performClick()

        rule.runOnIdle { assertEquals(false, h.settings.keyboardShowWithHardKeyboard) }
    }

    @Test
    fun unlockSkinsInvokesCallback() {
        val h = Harness(Settings())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(TECLADO_UNLOCK_TAG).performScrollTo().performClick()

        rule.runOnIdle { assertTrue(h.unlockClicks > 0) }
    }

    @Test
    fun keyboardSkinLeavesTheOrbSkinAlone() {
        // O Aero no teclado vale só para o teclado; a orbe tem a skin dela na aba Sobrepor (Yan, 2026-09-29).
        val h = Harness(Settings(keyboardSkin = "system", orbSkin = "atelie"), skinsUnlocked = true)
        rule.setContent { h.Content() }

        rule.onNodeWithTag(skinTag("aero")).performScrollTo().performClick()

        rule.runOnIdle {
            assertEquals("aero", h.settings.keyboardSkin)
            assertEquals("atelie", h.settings.orbSkin)
        }
    }

    @Test
    fun keyboardSkinGlitchPersists() {
        val h = Harness(Settings(keyboardSkin = "system", orbSkin = "atelie"), skinsUnlocked = true)
        rule.setContent { h.Content() }

        rule.onNodeWithTag(skinTag("glitch")).performScrollTo().performClick()

        rule.runOnIdle {
            assertEquals("glitch", h.settings.keyboardSkin)
            assertEquals("atelie", h.settings.orbSkin)
        }
    }

    @Test
    fun keyboardSkinRoseGoldPersists() {
        val h = Harness(Settings(keyboardSkin = "system", orbSkin = "atelie"), skinsUnlocked = true)
        rule.setContent { h.Content() }

        rule.onNodeWithTag(skinTag("rosegold")).performScrollTo().performClick()

        rule.runOnIdle {
            assertEquals("rosegold", h.settings.keyboardSkin)
            assertEquals("atelie", h.settings.orbSkin)
        }
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
    fun keyboardSizePreviewStaysInsideThePanel() {
        // A prévia ocupava a largura da TELA (menos 14dp) e vazava pelo padding do painel — de propósito,
        // para bater com o teclado real (Yan, 2026-07-03). Com a navegação lateral (2026-09-28), vazar
        // passou a invadir o menu: agora ela ocupa exatamente a largura do painel, simétrica.
        var paddingPx = 0
        val h = Harness(Settings(keyboardScalePercent = 100))
        rule.setContent {
            KeyFlowTheme(dark = false) {
                paddingPx = with(LocalDensity.current) { 12.dp.roundToPx() }
                Box(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    h.Content()
                }
            }
        }
        rule.waitForIdle()

        val rootWidthPx = rule.onRoot().fetchSemanticsNode().size.width
        val previewNode = rule.onNodeWithTag(TECLADO_SIZE_PREVIEW_TAG).fetchSemanticsNode()
        val leftPx = previewNode.positionInRoot.x
        val rightPx = rootWidthPx - (leftPx + previewNode.size.width)

        assertTrue("prévia vazou do painel à esquerda (esquerda=$leftPx px)", leftPx >= paddingPx - 1)
        assertTrue("prévia vazou do painel à direita (direita=$rightPx px)", rightPx >= paddingPx - 1)
        assertTrue(
            "prévia deveria ficar centralizada no painel (esquerda=$leftPx px, direita=$rightPx px)",
            kotlin.math.abs(leftPx - rightPx) <= 1,
        )
    }

    @Test
    fun keyboardLanguagePersistsAndDrivesThePreview() {
        // A linha era um "pt-BR" fixo; o teclado já tinha inglês e espanhol pelo globo (Yan, 2026-09-28).
        val h = Harness(Settings(keyboardLanguage = "pt"))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(keyboardLanguageTag("es")).performScrollTo().performClick()

        rule.runOnIdle { assertEquals("es", h.settings.keyboardLanguage) }
    }

    @Test
    fun keyboardSizePreviewKeepsTheRealBarProportionsWithoutScrolling() {
        // O cartão é mais estreito que a tela (Yan, 2026-09-28): sem a área das sugestões, o mic esticava
        // para o cartão todo; com ela e o mínimo de toque, a barra rolava. Agora é a barra de fábrica
        // dividida na largura que houver — e um aviso explica que a prévia acerta a altura, não a largura.
        val h = Harness(Settings(keyboardScalePercent = 100))
        rule.setContent { h.Content() }
        rule.waitForIdle()

        // A view monta as barras dos dois modos; a da prévia (expandida) é a que está na tela.
        val bar = keyboardSizePreviewForTest?.barViewsForTest?.single { it.isAttachedToWindow } ?: error("prévia não montou")
        val layout = bar.layoutForTest
        assertTrue("a barra da prévia não deveria rolar", !layout.scrollable)
        assertTrue(
            "a barra da prévia deveria ter a área das sugestões",
            layout.buttons.any { it.button == com.keyflow.core.shortcuts.BarButton.Candidates },
        )
        val mic = layout.buttons.first { it.button == com.keyflow.core.shortcuts.BarButton.Mic }
        assertTrue("o mic não deveria passar de 1/3 da barra (${mic.width} de ${layout.viewWidth})", mic.width < layout.viewWidth / 3f)
        rule.onNodeWithTag(TECLADO_SIZE_PREVIEW_NOTE_TAG).performScrollTo().assertIsDisplayed()
    }

    private fun escolherToque(toques: Int, acao: AcaoDaOrbeDoTeclado) {
        rule.onNodeWithTag(orbeToqueTag(toques)).performScrollTo().performClick()
        rule.onNodeWithTag("${orbeToqueTag(toques)}-${acao.id}").performClick()
    }

    @Test
    fun orbeCopiadaRefleteOrbSkinENaoEscreveAoAbrirAAba() {
        val h = Harness(Settings(orbSkin = "glitch"), skinsUnlocked = true)
        rule.setContent { h.Content() }

        rule.onNodeWithTag(TECLADO_ORBE_COPIAR_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TECLADO_ORBE_COPIA_SKIN_TAG).performScrollTo().assertTextContains("Glitch", substring = true)
        rule.onNodeWithText("Copiado da orbe principal", substring = true).assertExists()
        rule.onNodeWithTag(orbeToqueTag(1)).assertDoesNotExist()
        rule.onNodeWithTag(TECLADO_ORBE_SKIN_TAG).assertDoesNotExist()
        rule.onNodeWithTag(TECLADO_ORBE_PREVIA_TAG).performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, h.orbeWrites) }
    }

    @Test
    fun desligarCopiarRestauraAConfigPropriaPlantada() {
        val propria = ConfigOrbeTeclado(
            copiarDaOrbePrincipal = true,
            skin = "aero",
            modo = ModoDaOrbe.SEGURAR_PARA_FALAR,
            toque1 = AcaoDaOrbeDoTeclado.Nenhuma,
            toque2 = AcaoDaOrbeDoTeclado.PausarRetomar,
            toque3 = AcaoDaOrbeDoTeclado.LerTela,
        )
        val h = Harness(Settings(orbSkin = "atelie"), skinsUnlocked = true, orbe = propria)
        rule.setContent { h.Content() }

        rule.onNodeWithTag(TECLADO_ORBE_COPIAR_TAG).performScrollTo().performClick()

        rule.runOnIdle { assertEquals(propria.copy(copiarDaOrbePrincipal = false), h.orbe) }
        rule.onNodeWithTag(orbeToqueTag(2)).performScrollTo().assertTextContains("Pausar", substring = true)
        rule.onNodeWithTag(TECLADO_ORBE_SKIN_TAG).assertTextContains("Aero", substring = true)

        rule.onNodeWithTag(TECLADO_ORBE_COPIAR_TAG).performScrollTo().performClick()
        rule.runOnIdle { assertEquals(propria, h.orbe) }
    }

    private fun salvarSemGravarEnviar(modo: ModoDaOrbe) {
        val h = Harness(Settings(), orbe = ConfigOrbeTeclado.padraoParaModo(modo).copy(copiarDaOrbePrincipal = false))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(orbeModoTag(modo.id)).performScrollTo().performClick()
        for (toques in 1..3) escolherToque(toques, AcaoDaOrbeDoTeclado.Nenhuma)

        rule.runOnIdle {
            assertEquals(modo, h.orbe.modo)
            assertEquals(AcaoDaOrbeDoTeclado.Nenhuma, h.orbe.toque1)
            assertEquals(AcaoDaOrbeDoTeclado.Nenhuma, h.orbe.toque2)
            assertEquals(AcaoDaOrbeDoTeclado.Nenhuma, h.orbe.toque3)
        }
    }

    @Test
    fun salvarSemGravarEnviarGravaNoAlternar() = salvarSemGravarEnviar(ModoDaOrbe.ALTERNAR)

    @Test
    fun salvarSemGravarEnviarGravaNoSegurarParaFalar() = salvarSemGravarEnviar(ModoDaOrbe.SEGURAR_PARA_FALAR)

    @Test
    fun substituirToqueQueJaTinhaAcaoMostraOAvisoDoQueSaiu() {
        val h = Harness(Settings(), orbe = ConfigOrbeTeclado(copiarDaOrbePrincipal = false))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(TECLADO_ORBE_AVISO_TAG).assertDoesNotExist()
        escolherToque(1, AcaoDaOrbeDoTeclado.LerTela)

        rule.runOnIdle { assertEquals(AcaoDaOrbeDoTeclado.LerTela, h.orbe.toque1) }
        rule.onNodeWithTag(TECLADO_ORBE_AVISO_TAG).performScrollTo().assertTextContains("Gravar e enviar", substring = true)
        rule.onNodeWithTag(TECLADO_ORBE_AVISO_TAG).assertTextContains("1 toque", substring = true)
    }

    @Test
    fun segurarEArrastarSaoTextoFixoSemLinhasEditaveis() {
        val h = Harness(Settings(), orbe = ConfigOrbeTeclado(copiarDaOrbePrincipal = false))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(TECLADO_ORBE_FIXOS_TAG).performScrollTo().assertIsDisplayed()
    }
}
