package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.data.Settings
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.common.SaveController
import com.keyflow.ui.common.SaveToast
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.VoiceOrb
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

/** testTag do painel renderizado para a aba [tab] (usado nos testes de navegação). */
fun panelTag(tab: ConfigTab): String = "panel-${tab.name.lowercase()}"

const val LANG_TOGGLE_TAG = "lang-toggle"
const val THEME_TOGGLE_TAG = "theme-toggle"
const val TOGGLE_OVERLAY_MODE_TAG = "toggle-overlay-mode"
const val TOGGLE_KEYBOARD_MODE_TAG = "toggle-keyboard-mode"

/**
 * Scaffold da configuração em abas (CFG-01..05): status-bar falsa + header (orbe, idioma inerte,
 * tema) + [TabBar] com gating + painel da aba ativa + [SaveToast]. A aba ativa é estado interno;
 * se o modo que libera a aba ativa for desligado, volta para [ConfigTab.MODO] (CFG-02).
 *
 * Aba Modo: [ModoPanel] (T11). Demais abas: placeholders até T12–T16.
 */
@Composable
fun ConfigScreen(
    settings: Settings,
    permissions: PermissionStatus,
    saveController: SaveController,
    onOverlayModeChange: (Boolean) -> Unit,
    onKeyboardModeChange: (Boolean) -> Unit,
    onThemeChange: (Boolean) -> Unit,
    onLanguageClick: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestKeyboardPermission: () -> Unit,
    onSettingsChange: (Settings) -> Unit,
    onUnlockSkins: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: ConfigTab = ConfigTab.MODO,
) {
    val palette = LocalKeyFlowPalette.current
    var activeTab by rememberSaveable { mutableStateOf(initialTab) }

    val enabled: (ConfigTab) -> Boolean = { tab ->
        when (tab) {
            ConfigTab.TECLADO -> settings.keyboardModeEnabled
            ConfigTab.OVERLAY -> settings.overlayModeEnabled
            else -> true
        }
    }

    // Desligar o modo da aba ativa devolve o foco à aba Modo (CFG-02).
    LaunchedEffect(settings.overlayModeEnabled, settings.keyboardModeEnabled) {
        if (!enabled(activeTab)) activeTab = ConfigTab.MODO
    }

    Box(modifier.fillMaxSize().background(palette.bg)) {
        Column(Modifier.fillMaxSize()) {
            FakeStatusBar()
            Header(
                activeTab = activeTab,
                dark = palette.isDark,
                onLanguageClick = onLanguageClick,
                onThemeClick = { onThemeChange(!settings.themeDark) },
            )
            TabBar(
                active = activeTab,
                enabled = enabled,
                onSelect = { activeTab = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp)
                    .testTag(panelTag(activeTab)),
            ) {
                when (activeTab) {
                    ConfigTab.MODO -> ModoPanel(
                        settings = settings,
                        permissions = permissions,
                        onOverlayModeChange = onOverlayModeChange,
                        onKeyboardModeChange = onKeyboardModeChange,
                        onRequestOverlayPermission = onRequestOverlayPermission,
                        onRequestKeyboardPermission = onRequestKeyboardPermission,
                    )
                    ConfigTab.VOZ -> VozPanel(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                    )
                    ConfigTab.TECLADO -> TecladoPanel(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        onLanguageClick = onLanguageClick,
                        onUnlockSkins = onUnlockSkins,
                    )
                    ConfigTab.OVERLAY -> OverlayPanel(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        onUnlockSkins = onUnlockSkins,
                    )
                    else -> PanelPlaceholder(activeTab)
                }
            }
        }
        SaveToast(saveController, Modifier.fillMaxSize())
    }
}

@Composable
private fun FakeStatusBar() {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 13.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StatusText("3:02")
        StatusText("5G · 82%")
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        color = LocalKeyFlowPalette.current.faint,
        fontFamily = SpaceMono,
        fontSize = 11.sp,
    )
}

@Composable
private fun Header(
    activeTab: ConfigTab,
    dark: Boolean,
    onLanguageClick: () -> Unit,
    onThemeClick: () -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        VoiceOrb(state = OrbState.Idle, modifier = Modifier.size(30.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "KeyFlow",
                color = palette.fg,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp,
            )
            Text(
                text = activeTab.label.uppercase(),
                color = palette.faint,
                fontFamily = SpaceMono,
                fontSize = 8.5.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        // Idioma — inerte nesta fase (toca → "em breve"); I18N-02.
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, palette.line, RoundedCornerShape(6.dp))
                .clickable { onLanguageClick() }
                .testTag(LANG_TOGGLE_TAG)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            StrokeIcon(GLOBE_PATHS, palette.fg2, Modifier.size(13.dp))
            Text("PT", color = palette.fg2, fontFamily = SpaceMono, fontSize = 10.sp)
        }
        // Tema sol/lua — persiste themeDark (FND-04).
        Box(
            Modifier
                .size(width = 32.dp, height = 27.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, palette.line, RoundedCornerShape(6.dp))
                .clickable { onThemeClick() }
                .testTag(THEME_TOGGLE_TAG),
            contentAlignment = Alignment.Center,
        ) {
            StrokeIcon(if (dark) SUN_PATHS else MOON_PATHS, palette.fg2, Modifier.size(14.dp))
        }
    }
}

@Composable
private fun PanelPlaceholder(tab: ConfigTab) {
    val palette = LocalKeyFlowPalette.current
    Column(Modifier.fillMaxSize().padding(top = 18.dp)) {
        Text(
            text = tab.label,
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Em construção.",
            color = palette.muted,
            fontFamily = SpaceGrotesk,
            fontSize = 12.sp,
        )
    }
}

private val GLOBE_PATHS = listOf(
    "M12 12 m-9 0 a9 9 0 1 0 18 0 a9 9 0 1 0 -18 0",
    "M3 12h18",
    "M12 3c3 4 3 14 0 18",
    "M12 3c-3 4-3 14 0 18",
)
private val SUN_PATHS = listOf(
    "M12 12 m-4 0 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0",
    "M12 2v2M12 20v2M4 12H2M22 12h-2M5.6 5.6 4.2 4.2M19.8 19.8l-1.4-1.4M18.4 5.6l1.4-1.4M4.2 19.8l1.4-1.4",
)
private val MOON_PATHS = listOf("M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8z")
