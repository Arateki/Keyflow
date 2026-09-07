package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk

/**
 * Modal centralizado das configurações (Q8): scrim clicável + card flutuante com margem nas 4 bordas,
 * header FIXO (voltar opcional + título + `✕`) e corpo rolável.
 *
 * Substitui os bottom sheets que cresciam com o conteúdo: uma grade 9×9 ([GRID_MAX_DIMENSION]) estourava
 * a tela por baixo e não sobrava faixa de scrim pra fechar clicando fora (achado do Yan no A32,
 * 2026-09-07). O card encolhe pro conteúdo (`heightIn`, não `fillMaxHeight` — conteúdo curto não deve
 * abrir um card gigante vazio) até [MAX_HEIGHT_FRACTION] do espaço disponível; daí em diante o CORPO
 * rola e o header fica parado.
 *
 * [onBack] não-nulo mostra a seta `←` à esquerda do título — é como o mesmo modal troca de conteúdo (ex.:
 * editor de grade → picker de tecla) em vez de empilhar um segundo overlay por cima do primeiro.
 */
@Composable
fun ConfigModal(
    title: String,
    onDismiss: () -> Unit,
    closeTag: String,
    modifier: Modifier = Modifier,
    cardTag: String? = null,
    onBack: (() -> Unit)? = null,
    backTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    val noRipple = remember { MutableInteractionSource() }

    // Janela própria (Popup) em vez de desenhar na árvore de quem chamou: o modal nasce dentro do
    // `Box` do editor, que por sua vez vive numa coluna rolável do painel — então `fillMaxSize()` se
    // limitava às constraints DAQUELE Box e o "modal" aparecia espremido dentro da seção, com o scrim
    // cobrindo só ela (achado no A32, 2026-09-07). `focusable` também dá o botão Voltar do Android.
    Popup(
        alignment = Alignment.Center,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false),
        onDismissRequest = onDismiss,
    ) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(SCRIM)
            .clickable(interactionSource = noRipple, indication = null) { onDismiss() }
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = MAX_WIDTH_DP.dp)
                .heightIn(max = maxHeight * MAX_HEIGHT_FRACTION)
                .clip(RoundedCornerShape(20.dp))
                .background(palette.bg)
                .clickable(interactionSource = noRipple, indication = null) { /* consome o clique */ }
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .then(if (cardTag != null) Modifier.testTag(cardTag) else Modifier),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clickable(interactionSource = noRipple, indication = null) { onBack() }
                                .then(if (backTag != null) Modifier.testTag(backTag) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            StrokeIcon(listOf(BACK_PATH), palette.faint, Modifier.size(16.dp), strokeWidth = 1.8f)
                        }
                    }
                    Text(
                        title,
                        color = palette.fg,
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(start = if (onBack != null) 6.dp else 0.dp),
                    )
                }
                Box(
                    Modifier
                        .size(26.dp)
                        .clickable(interactionSource = noRipple, indication = null) { onDismiss() }
                        .testTag(closeTag),
                    contentAlignment = Alignment.Center,
                ) {
                    StrokeIcon(listOf(CLOSE_PATH), palette.faint, Modifier.size(16.dp), strokeWidth = 1.8f)
                }
            }

            // `weight(fill = false)`: o corpo ocupa só o que precisa (card encolhe junto) e, quando o
            // conteúdo passa do teto, para de crescer e rola — sem empurrar o header pra fora do card.
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
    }
}

private val SCRIM = Color(0x751C1813)

/** Teto de altura do card dentro da área já sem as margens — sempre sobra scrim pra fechar fora. */
private const val MAX_HEIGHT_FRACTION = 0.88f

/** Teto de largura (tablet/paisagem): o card não vira uma faixa esticada de ponta a ponta. */
private const val MAX_WIDTH_DP = 420

private const val CLOSE_PATH = "M18 6 6 18M6 6l12 12"
private const val BACK_PATH = "M15 5 8 12l7 7"
