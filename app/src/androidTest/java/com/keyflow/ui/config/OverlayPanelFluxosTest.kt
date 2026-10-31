package com.keyflow.ui.config

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.core.OrbShortcutAction
import com.keyflow.data.Settings
import com.keyflow.fluxos.*
import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import com.keyflow.orbe.LugarDaOrbe
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** FLX-35, FLX-51: a aba Sobrepor mostra o gesto ligado a fluxo (somente leitura) e leva à aba Fluxos. */
@RunWith(AndroidJUnit4::class)
class OverlayPanelFluxosTest {
    @get:Rule val rule = createComposeRule()
    private var idioma: AppLanguage? = null
    private val fluxo = Fluxo("a", "Ouvir e responder", etapas = listOf(Etapa.Ditar(), Etapa.Inserir))
    private var config by mutableStateOf(ConfigFluxos(listOf(fluxo), emptyList()))
    private var settings by mutableStateOf(Settings(shortcut2Tap = OrbShortcutAction.TOGGLE_KEYBOARD, shortcut3Tap = OrbShortcutAction.SWITCH_IME))
    private var abriuFolha = mutableListOf<Int>()
    private var abriuFluxos = 0

    @Before fun pt() { InstrumentationRegistry.getInstrumentation().runOnMainSync { idioma = AppLocale.pinned; AppLocale.pinned = AppLanguage.PT } }
    @After fun restaurar() { InstrumentationRegistry.getInstrumentation().runOnMainSync { AppLocale.pinned = idioma } }

    private fun abrir() = rule.setContent {
        KeyFlowTheme(dark = false) {
            OverlayPanel(settings = settings, onSettingsChange = { settings = it }, onUnlockSkins = {},
                onOpenShortcutSheet = { abriuFolha += it }, configFluxos = config, onOpenFluxos = { abriuFluxos++ })
        }
    }

    @Test fun semGatilhoALinhaMostraAAcaoENaoEFluxo() {
        abrir()
        rule.onNodeWithTag("overlay-shortcut-2").performScrollTo().assertTextContains("Retrair / expandir teclado", substring = true)
        rule.onNodeWithTag("overlay-shortcut-2").performClick()
        assertEquals(listOf(2), abriuFolha)
        assertEquals(0, abriuFluxos)
    }

    @Test fun comGatilhoALinhaNomeiaOFluxoETocarLevaAAbaFluxos() {
        config = ConfigFluxos(listOf(fluxo), listOf(Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), "a")))
        abrir()
        rule.onNodeWithTag("overlay-shortcut-2").performScrollTo().assertTextContains("Fluxo: Ouvir e responder", substring = true)
        rule.onNodeWithTag("overlay-shortcut-2").assert(hasText("Retrair", substring = true).not())
        rule.onNodeWithTag("overlay-shortcut-2").performClick()
        assertEquals(1, abriuFluxos)
        assertEquals("a folha de ação não abre para gesto de fluxo", emptyList<Int>(), abriuFolha)
        // o 3 toques, sem gatilho, segue como sempre
        rule.onNodeWithTag("overlay-shortcut-3").performScrollTo().assertTextContains("Trocar teclado do sistema", substring = true)
        rule.onNodeWithTag("overlay-shortcut-3").performClick()
        assertEquals(listOf(3), abriuFolha)
        // as Settings nunca mudam por causa do gatilho
        assertEquals(OrbShortcutAction.TOGGLE_KEYBOARD, settings.shortcut2Tap)
    }

    @Test fun fluxoIncompletoLigadoAvisaNaLinha() {
        val ruim = Fluxo("r", "Pela metade", etapas = listOf(Etapa.Inserir))
        config = ConfigFluxos(listOf(ruim), listOf(Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 3), "r")))
        abrir()
        rule.onNodeWithTag("overlay-shortcut-3").performScrollTo().assertTextContains("incompleto", substring = true)
    }
}
