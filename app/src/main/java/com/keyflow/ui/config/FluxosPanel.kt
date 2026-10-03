package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

const val FLUXOS_PANEL_TAG = "fluxos-panel"
const val FLUXOS_NEW_BUTTON_TAG = "fluxos-novo-fluxo"
const val FLUXOS_CARD_VOICE_TAG = "fluxos-card-voz"
const val FLUXOS_CARD_VISION_TAG = "fluxos-card-visao"
const val FLUXOS_CARD_COMPUTER_TAG = "fluxos-card-computador"

/**
 * Aba Fluxos: orquestração e pipelines de interação entre as capacidades do modelo e as interfaces de entrada.
 */
@Composable
fun FluxosPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()

    Column(
        modifier
            .fillMaxWidth()
            .testTag(FLUXOS_PANEL_TAG)
            .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
            .verticalScroll(scroll)
            .padding(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
    ) {
        SettingsGroup(tr("Fluxos de processamento")) {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SettingSub(
                    tr("Conecte entradas das interfaces com modelos de IA e pipelines de execução."),
                )

                FlowCard(
                    title = tr("Voz → Raciocínio → Síntese de Voz"),
                    description = tr("Transcreve áudio do microfone, envia para raciocínio e sintetiza a resposta em áudio."),
                    status = tr("Ativo"),
                    tag = FLUXOS_CARD_VOICE_TAG,
                )

                FlowCard(
                    title = tr("Captura de Visão → Raciocínio"),
                    description = tr("Reconhece o texto ou imagem na tela e gera resposta para colar no campo focado."),
                    status = tr("Ativo"),
                    tag = FLUXOS_CARD_VISION_TAG,
                )

                FlowCard(
                    title = tr("Instrução de Voz → Computador (SSH)"),
                    description = tr("Interpreta comandos falados e executa ações diretamente no terminal ou servidor conectado."),
                    status = tr("Configurado"),
                    tag = FLUXOS_CARD_COMPUTER_TAG,
                )

                SecondaryButton(
                    text = tr("+ Novo fluxo"),
                    tag = FLUXOS_NEW_BUTTON_TAG,
                    icon = KfIcons.Plus,
                    onClick = { },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        SettingsGroup(tr("Gatilhos e atalhos")) {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingSub(
                    tr("Mapeamento rápido de ativação dos fluxos através dos gestos da orbe ou teclado."),
                )
                TriggerRow(trigger = tr("Gesto da orbe: toque duplo"), target = tr("Voz → Raciocínio → Síntese de Voz"))
                TriggerRow(trigger = tr("Gesto da orbe: arrastar p/ cima"), target = tr("Captura de Visão → Raciocínio"))
                TriggerRow(trigger = tr("Comando do teclado"), target = tr("Instrução de Voz → Computador (SSH)"))
            }
        }

        SettingsGroup(tr("Orquestração")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                InfoNote(
                    text = tr("O editor visual de nós e conexões entre capacidades será disponibilizado em atualizações futuras."),
                    tag = "fluxos-info-futuro",
                )
            }
        }
    }
}

@Composable
private fun FlowCard(
    title: String,
    description: String,
    status: String,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, palette.line2, shape)
            .background(palette.panel)
            .testTag(tag)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = palette.fg,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.teal.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    status,
                    color = palette.teal,
                    fontFamily = SpaceMono,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            description,
            color = palette.fg2,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Light,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun TriggerRow(trigger: String, target: String) {
    val palette = LocalKeyFlowPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.panel.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            trigger,
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
        )
        Text(
            target,
            color = palette.muted,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Light,
            fontSize = 11.sp,
        )
    }
}
