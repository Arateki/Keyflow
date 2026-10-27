package com.keyflow.ui.config

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.data.models.ModelPreparation
import com.keyflow.i18n.tr
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceMono
import kotlinx.coroutines.delay

/** A galeria fixa o relógio do indicador e do tempo; o app usa tempo monotônico real. */
val LocalModelPreparationClock = staticCompositionLocalOf<(() -> Long)?> { null }

@Composable
internal fun ModelPreparationIndicator(preparation: ModelPreparation, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    val fixedClock = LocalModelPreparationClock.current
    var now by remember(preparation, fixedClock) { mutableLongStateOf(fixedClock?.invoke() ?: System.nanoTime() / 1_000_000L) }
    LaunchedEffect(preparation, fixedClock) {
        if (fixedClock == null) {
            while (true) {
                delay(100)
                now = System.nanoTime() / 1_000_000L
            }
        }
    }
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        // Arco que gira: não representa fração concluída. Sem porcentagem ou previsão de término.
        Canvas(Modifier.size(16.dp).progressSemantics()) {
            drawArc(
                color = palette.teal,
                startAngle = (now % 1200) * .3f,
                sweepAngle = 100f,
                useCenter = false,
                style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Text(
            tr("Preparando modelo… %d s", ((now - preparation.startedAtMs).coerceAtLeast(0)) / 1000),
            modifier = Modifier.padding(start = 8.dp),
            color = palette.muted,
            fontFamily = SpaceMono,
            fontSize = 10.sp,
        )
    }
}
