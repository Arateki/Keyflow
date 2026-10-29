package com.keyflow.ui.orb

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.i18n.tr
import com.keyflow.ui.theme.KeyFlowPalette
import com.keyflow.vision.VisionFlowState

/** A fase encobre o centro de voz sem alterar o ButtonState nem a skin do orbe. */
@Composable
fun VisionPhaseBadge(state: VisionFlowState, palette: KeyFlowPalette) {
    if (state == VisionFlowState.NONE) return
    val accent = when (state) {
        VisionFlowState.CONSENT -> palette.terracotta
        VisionFlowState.WORKING, VisionFlowState.COPYING -> palette.record
        else -> palette.teal
    }
    Box(Modifier.size(36.dp).background(palette.panel, CircleShape).border(2.dp, accent, CircleShape)
        .semantics { contentDescription = tr("Visão") }, contentAlignment = Alignment.Center) {
        Text(when (state) {
            VisionFlowState.CONSENT -> "?"
            VisionFlowState.SELECTING -> "⊡"
            VisionFlowState.ACTIONS -> "⋯"
            VisionFlowState.WORKING -> "◌"
            VisionFlowState.COPYING -> "✓"
            VisionFlowState.NONE -> ""
        }, color = accent, fontSize = 22.sp)
    }
}
