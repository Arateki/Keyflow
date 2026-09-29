package com.keyflow.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keyflow.data.Settings
import com.keyflow.ui.PermissionStatus

/**
 * Os dois cartões de modo lado a lado, numa coluna rolável — o que a antiga aba Modo mostrava. Desde que
 * ela saiu (2026-09-28) cada cartão mora no topo da aba da própria interface; os testes dos cartões em si
 * (permissões, interruptores, tours) seguem valendo e usam este harness.
 */
@Composable
fun ModeCards(
    settings: Settings,
    permissions: PermissionStatus,
    onOverlayModeChange: (Boolean) -> Unit,
    onKeyboardModeChange: (Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestKeyboardPermission: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    overlayHighlightKey: Int = 0,
    keyboardHighlightKey: Int = 0,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OverlayModeCard(
            settings = settings,
            permissions = permissions,
            onOverlayModeChange = onOverlayModeChange,
            onRequestOverlayPermission = onRequestOverlayPermission,
            onRequestMicPermission = onRequestMicPermission,
            onRequestNotificationPermission = onRequestNotificationPermission,
            highlightKey = overlayHighlightKey,
        )
        KeyboardModeCard(
            settings = settings,
            permissions = permissions,
            onKeyboardModeChange = onKeyboardModeChange,
            onRequestKeyboardPermission = onRequestKeyboardPermission,
            onRequestMicPermission = onRequestMicPermission,
            onSwitchKeyboard = onSwitchKeyboard,
            highlightKey = keyboardHighlightKey,
        )
    }
}
