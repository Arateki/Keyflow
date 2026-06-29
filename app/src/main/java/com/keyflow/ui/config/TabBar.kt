package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceMono

fun tabTag(tab: ConfigTab): String = "tab-${tab.name.lowercase()}"

/**
 * Barra de 5 abas (CFG-01/02/03). A aba ativa fica preenchida (ink/onFill); abas desabilitadas
 * (modo desligado) ficam esmaecidas e não-clicáveis.
 */
@Composable
fun TabBar(
    active: ConfigTab,
    enabled: (ConfigTab) -> Boolean,
    onSelect: (ConfigTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        ConfigTab.entries.forEach { tab ->
            val isActive = tab == active
            val isEnabled = enabled(tab)
            // Aba travada mantém o estilo apagado (sem usar Modifier.alpha, que cria um graphics layer
            // e podia deixar o contorno "preso" em cache ao destravar; aplicamos o alpha nas cores).
            val dim = if (isEnabled || isActive) 1f else 0.26f
            val tint = (if (isActive) palette.onFill else palette.fg2).copy(alpha = dim)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (isActive) Modifier.background(palette.fg)
                        else Modifier.border(1.dp, palette.line.copy(alpha = dim), RoundedCornerShape(8.dp))
                    )
                    .clickable(enabled = !isActive) { onSelect(tab) }
                    .padding(vertical = 5.dp)
                    .testTag(tabTag(tab)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tab.Icon(tint = tint, modifier = Modifier.size(17.dp))
                Text(
                    text = tab.label,
                    color = tint,
                    fontFamily = SpaceMono,
                    fontWeight = FontWeight.Normal,
                    fontSize = 8.5.sp,
                )
            }
        }
    }
}
