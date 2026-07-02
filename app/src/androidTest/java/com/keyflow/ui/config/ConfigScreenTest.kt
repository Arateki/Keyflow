package com.keyflow.ui.config

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.data.Settings
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.common.SaveController
import com.keyflow.ui.orb.LocalOrbClock
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Testes do scaffold de abas (T10 / CFG-01..05; gating redesenhado na Fase 2 de gestos). Exercitam
 * navegação, gating somente-leitura (scrim, em vez de bloquear o clique), auto-save (callbacks) e os
 * toggles inertes/persistentes do header — sem DataStore, controlando o estado direto no harness.
 */
@RunWith(AndroidJUnit4::class)
class ConfigScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val allGranted = PermissionStatus(
        microphoneGranted = true,
        overlayGranted = true,
        keyboardEnabled = true,
        keyboardActive = true,
        notificationsGranted = true,
    )

    /** Harness: ConfigScreen com estado mutável; callbacks aplicam a mudança e registram efeitos. */
    private inner class Harness {
        var settings by mutableStateOf(Settings())
        var lastOverlayChange: Boolean? = null
        var lastKeyboardChange: Boolean? = null
        var lastThemeChange: Boolean? = null
        var languageClicks = 0
        val saveController = SaveController()

        @Composable
        fun Content() {
            // Congela o orbe do header (senão a animação infinita nunca deixa a árvore "idle").
            KeyFlowTheme(dark = settings.themeDark) {
                CompositionLocalProvider(LocalOrbClock provides { 0f }) {
                ConfigScreen(
                    settings = settings,
                    permissions = allGranted,
                    saveController = saveController,
                    onOverlayModeChange = {
                        lastOverlayChange = it
                        settings = settings.copy(overlayModeEnabled = it)
                        saveController.notifySavedAsync()
                    },
                    onKeyboardModeChange = {
                        lastKeyboardChange = it
                        settings = settings.copy(keyboardModeEnabled = it)
                        saveController.notifySavedAsync()
                    },
                    onThemeChange = {
                        lastThemeChange = it
                        settings = settings.copy(themeDark = it)
                    },
                    onLanguageClick = { languageClicks++ },
                    onRequestOverlayPermission = {},
                    onRequestKeyboardPermission = {},
                    onRequestMicPermission = {},
                    onSwitchKeyboard = {},
                    onSettingsChange = { settings = it },
                    modifier = Modifier.fillMaxSize(),
                )
                }
            }
        }
    }

    @Test
    fun navigatesBetweenEnabledTabs() {
        val h = Harness().also { it.settings = Settings(overlayModeEnabled = true, keyboardModeEnabled = true) }
        rule.setContent { h.Content() }

        rule.onNodeWithTag(panelTag(ConfigTab.MODO)).assertIsDisplayed()

        rule.onNodeWithTag(tabTag(ConfigTab.VOZ)).performClick()
        rule.onNodeWithTag(panelTag(ConfigTab.VOZ)).assertIsDisplayed()

        rule.onNodeWithTag(tabTag(ConfigTab.OVERLAY)).performClick()
        rule.onNodeWithTag(panelTag(ConfigTab.OVERLAY)).assertIsDisplayed()
    }

    @Test
    fun disabledTabNavigatesReadOnly() {
        val h = Harness() // overlay/keyboard off por padrão
        rule.setContent { h.Content() }

        rule.onNodeWithTag(tabTag(ConfigTab.OVERLAY)).performClick()

        // A aba navega normalmente, mas fica somente-leitura sob o scrim (não bloqueia mais o clique).
        rule.onNodeWithTag(panelTag(ConfigTab.OVERLAY)).assertIsDisplayed()
        rule.onNodeWithTag(READONLY_SCRIM_TAG).assertIsDisplayed()
    }

    @Test
    fun activeTabBecomesReadOnlyWhenModeTurnsOff() {
        val h = Harness().also { it.settings = Settings(overlayModeEnabled = true) }
        rule.setContent { h.Content() }

        rule.onNodeWithTag(tabTag(ConfigTab.OVERLAY)).performClick()
        rule.onNodeWithTag(panelTag(ConfigTab.OVERLAY)).assertIsDisplayed()
        rule.onAllNodesWithTag(READONLY_SCRIM_TAG).assertCountEquals(0)

        rule.runOnIdle { h.settings = h.settings.copy(overlayModeEnabled = false) }

        // Modo desligado → continua na aba Overlay, mas agora somente-leitura (scrim aparece).
        rule.onNodeWithTag(panelTag(ConfigTab.OVERLAY)).assertIsDisplayed()
        rule.onNodeWithTag(READONLY_SCRIM_TAG).assertIsDisplayed()
    }

    @Test
    fun overlayModeToggleInvokesAutoSave() {
        val h = Harness()
        rule.setContent { h.Content() }

        rule.onNodeWithTag(TOGGLE_OVERLAY_MODE_TAG).performClick()

        rule.runOnIdle { assertEquals(true, h.lastOverlayChange) }
        // Aba Overlay agora liberada e selecionável.
        rule.onNodeWithTag(tabTag(ConfigTab.OVERLAY)).performClick()
        rule.onNodeWithTag(panelTag(ConfigTab.OVERLAY)).assertIsDisplayed()
    }

    @Test
    fun keyboardModeToggleInvokesAutoSave() {
        val h = Harness()
        rule.setContent { h.Content() }

        rule.onNodeWithTag(TOGGLE_KEYBOARD_MODE_TAG).performClick()

        rule.runOnIdle { assertEquals(true, h.lastKeyboardChange) }
    }

    @Test
    fun themeTogglePersistsAndLanguageIsInert() {
        val h = Harness()
        rule.setContent { h.Content() }

        rule.onNodeWithTag(THEME_TOGGLE_TAG).performClick()
        rule.runOnIdle { assertEquals(true, h.lastThemeChange) }

        rule.onNodeWithTag(LANG_TOGGLE_TAG).performClick()
        rule.runOnIdle { assertTrue(h.languageClicks > 0) }
    }

    @Test
    fun unlockSheetOpensAndCloses() {
        val h = Harness().also { it.settings = Settings(keyboardModeEnabled = true) }
        rule.setContent { h.Content() }

        rule.onNodeWithTag(tabTag(ConfigTab.TECLADO)).performClick()
        // A prévia ao vivo de tamanho do teclado (2026-07-02) empurrou "Desbloquear skins" pra fora do
        // viewport padrão do teste — precisa rolar até o nó antes do clique (performClick é por coordenada).
        rule.onNodeWithTag(TECLADO_UNLOCK_TAG).performScrollTo().performClick()
        rule.onNodeWithTag(UNLOCK_SHEET_TAG).assertIsDisplayed()

        rule.onNodeWithTag(UNLOCK_CLOSE_TAG).performClick()
        rule.onAllNodesWithTag(UNLOCK_SHEET_TAG).assertCountEquals(0)
    }
}
