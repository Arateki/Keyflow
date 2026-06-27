package com.keyflow.ui.config

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
 * Testes do scaffold de abas (T10 / CFG-01..05). Exercitam navegação, gating, reset da aba ativa,
 * auto-save (callbacks) e os toggles inertes/persistentes do header — sem DataStore, controlando o
 * estado direto no harness.
 */
@RunWith(AndroidJUnit4::class)
class ConfigScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val allGranted = PermissionStatus(
        microphoneGranted = true,
        overlayGranted = true,
        keyboardEnabled = true,
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
    fun disabledTabDoesNotNavigate() {
        val h = Harness() // overlay/keyboard off por padrão
        rule.setContent { h.Content() }

        rule.onNodeWithTag(tabTag(ConfigTab.OVERLAY)).performClick()

        // Continua na aba Modo (gating bloqueou o clique).
        rule.onNodeWithTag(panelTag(ConfigTab.MODO)).assertIsDisplayed()
    }

    @Test
    fun activeTabResetsWhenModeTurnsOff() {
        val h = Harness().also { it.settings = Settings(overlayModeEnabled = true) }
        rule.setContent { h.Content() }

        rule.onNodeWithTag(tabTag(ConfigTab.OVERLAY)).performClick()
        rule.onNodeWithTag(panelTag(ConfigTab.OVERLAY)).assertIsDisplayed()

        rule.runOnIdle { h.settings = h.settings.copy(overlayModeEnabled = false) }

        // Modo desligado → volta para a aba Modo.
        rule.onNodeWithTag(panelTag(ConfigTab.MODO)).assertIsDisplayed()
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
}
