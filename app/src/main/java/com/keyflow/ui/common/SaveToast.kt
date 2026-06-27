package com.keyflow.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceMono
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Sinaliza "salvo" para a UI sem botão global (CFG-04/05, C22). A camada de auto-save chama
 * [notifySaved]; um [SaveToast] observa e mostra um toast discreto que some sozinho.
 */
class SaveController {
    val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    suspend fun notifySaved() {
        events.emit(Unit)
    }
    fun notifySavedAsync() {
        events.tryEmit(Unit)
    }
}

const val SAVE_TOAST_TAG = "save-toast"

/** Toast discreto "salvo" (Space Mono). Aparece ~1.4 s a cada evento de [controller]. */
@Composable
fun SaveToast(controller: SaveController, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    var visible by remember { mutableStateOf(false) }
    var generation by remember { mutableStateOf(0) }

    LaunchedEffect(controller) {
        controller.events.collect {
            generation++
            visible = true
        }
    }
    LaunchedEffect(generation) {
        if (visible) {
            kotlinx.coroutines.delay(1400)
            visible = false
        }
    }

    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Text(
                text = "salvo",
                color = palette.onFill,
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier
                    .padding(bottom = 22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.fg)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag(SAVE_TOAST_TAG),
            )
        }
    }
}
