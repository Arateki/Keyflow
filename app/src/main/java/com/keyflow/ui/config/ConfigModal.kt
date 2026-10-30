package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.keyflow.i18n.tr
import androidx.compose.ui.window.PopupProperties
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
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
    accessibleHeader: Boolean = false,
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
                .clip(RoundedCornerShape(24.dp))
                .background(palette.bg)
                .border(1.dp, palette.line2, RoundedCornerShape(24.dp))
                .clickable(interactionSource = noRipple, indication = null) { /* consome o clique */ }
                .padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 20.dp)
                .then(if (cardTag != null) Modifier.testTag(cardTag) else Modifier),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(modifier = if (accessibleHeader) Modifier.weight(1f) else Modifier, verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) {
                        Box(
                            Modifier
                                .size(if (accessibleHeader) 48.dp else 32.dp)
                                .clip(CircleShape)
                                .border(1.dp, palette.line, CircleShape)
                                .clickable(interactionSource = noRipple, indication = null, role = Role.Button) { onBack() }
                                .semantics { contentDescription = tr("Voltar") }
                                .then(if (backTag != null) Modifier.testTag(backTag) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            KfIcon(KfIcons.ChevronLeft, palette.fg2, Modifier.size(16.dp), strokeWidth = 1.8f)
                        }
                    }
                    Text(
                        title,
                        color = palette.fg,
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp,
                        letterSpacing = (-0.3).sp,
                        maxLines = if (accessibleHeader) 1 else Int.MAX_VALUE,
                        overflow = if (accessibleHeader) TextOverflow.Ellipsis else TextOverflow.Clip,
                        modifier = Modifier.padding(start = if (onBack != null) 10.dp else 0.dp),
                    )
                }
                Box(
                    Modifier
                        .size(if (accessibleHeader) 48.dp else 32.dp)
                        .clip(CircleShape)
                        .border(1.dp, palette.line, CircleShape)
                        .clickable(interactionSource = noRipple, indication = null, role = Role.Button) { onDismiss() }
                        .semantics { contentDescription = tr("Fechar") }
                        .testTag(closeTag),
                    contentAlignment = Alignment.Center,
                ) {
                    KfIcon(KfIcons.Close, palette.fg2, Modifier.size(15.dp), strokeWidth = 1.8f)
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

