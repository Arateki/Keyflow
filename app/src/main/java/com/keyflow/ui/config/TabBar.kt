package com.keyflow.ui.config

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk

fun tabTag(tab: ConfigTab): String = "tab-${tab.name.lowercase()}"

const val NAV_RAIL_TAG = "nav-rail"
const val NAV_RAIL_TOGGLE_TAG = "nav-rail-toggle"

/**
 * Largura da barra recolhida — só os ícones. O painel da aba começa depois dela. Enxuta de propósito
 * (Yan, 2026-09-28: 60 → 48dp): o ícone segue com o mesmo tamanho; encolheram só as folgas em volta dele.
 */
val NAV_RAIL_COLLAPSED_WIDTH = 48.dp

/** Largura aberta: ícone + nome. Abre POR CIMA do painel, sem empurrá-lo (o conteúdo não reflui). */
private val NAV_RAIL_EXPANDED_WIDTH = 176.dp

/**
 * Navegação da configuração (CFG-01/02/03) como barra lateral: as opções empilhadas à esquerda, só com
 * os ícones na posição normal (recolhida) e com os nomes quando a setinha do topo abre a barra. Pedido
 * do Yan (2026-09-28): a faixa de abas no topo não comporta os menus que ainda vão entrar — uma coluna
 * cresce para baixo (e rola, se precisar).
 *
 * Abas travadas (modo desligado ou sem permissão) ficam esmaecidas, mas continuam tocáveis — o toque
 * explica o motivo. Escolher uma aba com a barra aberta já a recolhe.
 */
@Composable
fun NavRail(
    active: ConfigTab,
    enabled: (ConfigTab) -> Boolean,
    onSelect: (ConfigTab) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    val width by animateDpAsState(
        if (expanded) NAV_RAIL_EXPANDED_WIDTH else NAV_RAIL_COLLAPSED_WIDTH,
        animationSpec = tween(200),
        label = "railWidth",
    )
    val panelShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
    Column(
        modifier
            .width(width)
            .fillMaxHeight()
            .then(
                if (expanded) {
                    // Aberta, vira uma folha sobre o painel: mesma cor do papel, com fio e sombra leve.
                    Modifier
                        .clip(panelShape)
                        .background(palette.card)
                        .border(1.dp, palette.line2, panelShape)
                } else {
                    Modifier
                },
            )
            .testTag(NAV_RAIL_TAG)
            .padding(top = 8.dp, bottom = 12.dp),
    ) {
        RailToggle(expanded = expanded, onClick = { onExpandedChange(!expanded) })
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ConfigTab.entries.forEach { tab ->
                RailItem(
                    tab = tab,
                    isActive = tab == active,
                    isEnabled = enabled(tab),
                    expanded = expanded,
                    onClick = {
                        onSelect(tab)
                        if (expanded) onExpandedChange(false)
                    },
                )
            }
        }
    }
}

@Composable
private fun RailToggle(expanded: Boolean, onClick: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    Box(
        Modifier
            .padding(start = (NAV_RAIL_COLLAPSED_WIDTH - RAIL_TOGGLE_SIZE) / 2)
            .size(RAIL_TOGGLE_SIZE)
            .clip(CircleShape)
            .border(1.dp, palette.line, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = if (expanded) "Recolher menu" else "Mostrar nomes do menu"
                stateDescription = if (expanded) "aberto" else "recolhido"
            }
            .testTag(NAV_RAIL_TOGGLE_TAG),
        contentAlignment = Alignment.Center,
    ) {
        KfIcon(if (expanded) KfIcons.ChevronLeft else KfIcons.ChevronRight, palette.fg2, Modifier.size(16.dp), strokeWidth = 1.9f)
    }
}

@Composable
private fun RailItem(
    tab: ConfigTab,
    isActive: Boolean,
    isEnabled: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    // Aba travada: alpha aplicado nas cores (Modifier.alpha cria um graphics layer que já deixou
    // contorno "preso" em cache ao destravar).
    val tint = when {
        isActive -> palette.fg
        isEnabled -> palette.muted
        else -> palette.faint.copy(alpha = 0.45f)
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = RAIL_ITEM_INSET)
            .height(44.dp)
            .clip(shape)
            .then(if (isActive) Modifier.background(raisedFill(palette)).border(1.dp, palette.line, shape) else Modifier)
            .clickable(
                enabled = !isActive,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
            ) { onClick() }
            // Recolhida não há texto na tela: o nome vai para o leitor de tela.
            .semantics {
                contentDescription = tab.label
                selected = isActive
            }
            .testTag(tabTag(tab)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(RAIL_ICON_SLOT), contentAlignment = Alignment.Center) {
            tab.Icon(tint = tint, modifier = Modifier.size(21.dp))
        }
        AnimatedVisibility(visible = expanded, enter = fadeIn(tween(200)), exit = fadeOut(tween(120))) {
            Text(
                tab.label,
                color = tint,
                fontFamily = SpaceGrotesk,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(start = 4.dp, end = 12.dp),
            )
        }
    }
}

/** Faixa do ícone dentro do item — igual recolhida e aberta, para o ícone não "andar" ao abrir. */
private val RAIL_ICON_SLOT = 38.dp

/** Folga lateral do item: com [RAIL_ICON_SLOT], fecha a largura recolhida ([NAV_RAIL_COLLAPSED_WIDTH]). */
private val RAIL_ITEM_INSET = (NAV_RAIL_COLLAPSED_WIDTH - RAIL_ICON_SLOT) / 2

private val RAIL_TOGGLE_SIZE = 32.dp

/** Véu sobre o painel com a barra aberta: toque fora recolhe. Quase transparente — é só um alvo. */
@Composable
internal fun NavRailScrim(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Color(0x141C1813))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
    )
}
