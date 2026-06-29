package com.keyflow.ui.config

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.ButtonState
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.ime.KeyFlowKeyboardView
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.orb.OrbCenter
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.VoiceOrb
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

const val ACTIVATE_OVERLAY_TAG = "activate-overlay"
const val ACTIVATE_KEYBOARD_TAG = "activate-keyboard"
const val ACTIVATE_MIC_TAG = "activate-mic"
const val KEYBOARD_SWITCH_TAG = "keyboard-switch"

/**
 * Aba Modo (CFG-06 / REC-07): permissão geral do microfone + cards Overlay/Keyboard. Cada card liga/
 * desliga o modo (afetando o gating das abas), mostra o chip da permissão específica (com "Ativar"
 * quando pendente), um placeholder de GIF estático e a dica de pausa contextual ao modo de gravação.
 */
@Composable
fun ModoPanel(
    settings: Settings,
    permissions: PermissionStatus,
    onOverlayModeChange: (Boolean) -> Unit,
    onKeyboardModeChange: (Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestKeyboardPermission: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Permissão de microfone, repetida e sincronizada nos dois cards (mesmo estado).
    val micPermission = CardPermission(
        label = "MICROFONE",
        granted = permissions.microphoneGranted,
        onActivate = onRequestMicPermission,
        activateTag = ACTIVATE_MIC_TAG,
    )
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        ModeCard(
            title = "Modo Sobrepor",
            checked = settings.overlayModeEnabled,
            onCheckedChange = onOverlayModeChange,
            toggleTag = TOGGLE_OVERLAY_MODE_TAG,
            permissions = listOf(
                micPermission,
                CardPermission(
                    label = "SOBREPOR",
                    granted = permissions.overlayGranted,
                    onActivate = onRequestOverlayPermission,
                    activateTag = ACTIVATE_OVERLAY_TAG,
                ),
            ),
            pauseHint = pauseHintFor(settings.overlayRecordingMode),
            features = listOf(
                "Transcreva voz para texto em qualquer situação para sua área de transferência.",
            ),
            // Orbe demonstrativa (mock): só visual, em estado gravando — não dispara nada real.
            demo = {
                DemoFrame {
                    VoiceOrb(
                        state = OrbState.Listening,
                        modifier = Modifier.size(70.dp),
                        center = OrbCenter.Time(5),
                    )
                }
            },
        )

        ModeCard(
            title = "Modo Teclado",
            checked = settings.keyboardModeEnabled,
            onCheckedChange = onKeyboardModeChange,
            toggleTag = TOGGLE_KEYBOARD_MODE_TAG,
            permissions = listOf(
                micPermission,
                CardPermission(
                    label = "TECLADO",
                    granted = permissions.keyboardEnabled,
                    onActivate = onRequestKeyboardPermission,
                    activateTag = ACTIVATE_KEYBOARD_TAG,
                ),
            ),
            pauseHint = pauseHintFor(settings.keyboardRecordingMode),
            features = listOf(
                "Cole a transcrição de voz automaticamente.",
                "Personalize em estilo e funcionalidade.",
            ),
            footer = if (permissions.keyboardEnabled) {
                {
                    KeyboardActiveLine(
                        active = permissions.keyboardActive,
                        modeOn = settings.keyboardModeEnabled,
                        onSwitch = onSwitchKeyboard,
                    )
                }
            } else {
                null
            },
            // Teclado demonstrativo (mock): View real, em estado gravando, com callbacks no-op — não
            // digita nem grava de verdade. O ticker é parado ao sair de cena (onRelease) para não vazar.
            demo = {
                DemoFrame {
                    val dark = LocalKeyFlowPalette.current.isDark
                    // key(dark): as cores do teclado são fixadas no init da View, então recriamos a
                    // demo quando o tema muda.
                    key(dark) {
                        AndroidView(
                            factory = { ctx ->
                                KeyFlowKeyboardView(
                                    context = ctx,
                                    onKey = {},
                                    onMic = {},
                                    onHide = {},
                                    onExpand = {},
                                    dark = dark,
                                    frozenSeconds = 5,
                                    scale = 0.7f,
                                ).apply {
                                    setExpanded(true)
                                    updateMicState(ButtonState.RECORDING)
                                }
                            },
                            onRelease = { it.updateMicState(ButtonState.IDLE_NO_FIELD) },
                            // Largura também a 72% (mesma proporção do scale das alturas/texto) → redução
                            // uniforme em todas as direções, mantendo o espaçamento do dispositivo.
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
        )
    }
}

/**
 * Status do "teclado ativo" (DEFAULT_INPUT_METHOD) no card do Teclado, no mesmo estilo das permissões:
 * sempre acionável (abre o seletor para trocar) e pulsando quando o modo está ligado mas o KeyFlow ainda
 * não é o teclado ativo. Só aparece se o teclado já estiver habilitado.
 */
@Composable
private fun KeyboardActiveLine(active: Boolean, modeOn: Boolean, onSwitch: () -> Unit) {
    StatusChip(
        text = "TECLADO ATIVO · ${if (active) "SIM" else "NÃO"}",
        granted = active,
        pulsing = !active && modeOn,
        onClick = onSwitch,
        tag = KEYBOARD_SWITCH_TAG,
    )
}

/** Uma permissão exibida dentro de um card de modo (chip + botão "Ativar" quando pendente). */
private data class CardPermission(
    val label: String,
    val granted: Boolean,
    val onActivate: () -> Unit,
    val activateTag: String,
)

/** Dica de pausa contextual ao modo de gravação (REC-07). Reusada pela aba Docs (T15). */
fun pauseHintFor(mode: RecordingMode): String = when (mode) {
    RecordingMode.TOGGLE -> "Toque para gravar ou parar · segure para pausar e retomar."
    RecordingMode.PUSH_TO_TALK -> "Segure para gravar · solte para enviar · toque rápido após soltar para pausar."
}

@Composable
private fun ModeCard(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    toggleTag: String,
    permissions: List<CardPermission>,
    pauseHint: String,
    features: List<String>,
    footer: (@Composable () -> Unit)? = null,
    demo: (@Composable () -> Unit)? = null,
) {
    val palette = LocalKeyFlowPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, palette.line, RoundedCornerShape(6.dp))
            .background(palette.card)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                color = palette.fg,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.5.sp,
                modifier = Modifier.weight(1f),
            )
            PillToggle(checked = checked, onCheckedChange = onCheckedChange, tag = toggleTag)
        }
        FeatureList(features)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            permissions.forEach { perm ->
                // O próprio chip é o botão: abre as configs (concedido ou não) e pulsa quando o modo
                // está ligado mas a permissão ainda falta.
                StatusChip(
                    text = "${perm.label} · ${if (perm.granted) "CONCEDIDO" else "PENDENTE"}",
                    granted = perm.granted,
                    pulsing = !perm.granted && checked,
                    onClick = perm.onActivate,
                    tag = perm.activateTag,
                )
            }
        }
        footer?.invoke()
        if (demo != null) {
            // Demo à esquerda; instruções de toque na área livre à direita.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(0.7f)) { demo() }
                Text(
                    pauseHint,
                    color = palette.faint,
                    fontFamily = SpaceMono,
                    fontSize = 8.5.sp,
                    lineHeight = 12.sp,
                    modifier = Modifier.weight(0.3f),
                )
            }
        } else {
            Text(pauseHint, color = palette.faint, fontFamily = SpaceMono, fontSize = 8.5.sp)
        }
    }
}

/**
 * Moldura de "prévia": fundo levemente variante (o `bg`, mais recuado que o card) + borda e o rótulo
 * PRÉVIA, deixando claro que o conteúdo é uma **representação**, não a interface real.
 */
@Composable
private fun DemoFrame(content: @Composable () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.bg)
            .border(1.dp, palette.line2, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("PRÉVIA", color = palette.faint, fontFamily = SpaceMono, fontSize = 7.5.sp)
        content()
    }
}

/**
 * Funcionalidades do modo como uma tabelinha minimalista: linhas com separadores finos, marcador teal
 * e o texto à direita. Logo abaixo da descrição de uma linha.
 */
@Composable
private fun FeatureList(features: List<String>) {
    val palette = LocalKeyFlowPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(palette.panel),
    ) {
        features.forEachIndexed { index, feature ->
            if (index > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line2))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "✓",
                    color = palette.teal,
                    fontFamily = SpaceMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 1.dp),
                )
                Text(
                    feature,
                    color = palette.muted,
                    fontFamily = SpaceGrotesk,
                    fontSize = 10.5.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Chip de status que **também é botão**: toca → abre as configs/seletor (concedido ou não). Quando
 * [pulsing], anima um leve pulso de escala para chamar a atenção. Verde quando [granted]; senão âmbar.
 */
@Composable
private fun StatusChip(
    text: String,
    granted: Boolean,
    pulsing: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    val palette = LocalKeyFlowPalette.current
    val accent = if (granted) palette.teal else palette.recordSoft
    val scale = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "chip-pulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse),
            label = "chip-scale",
        ).value
    } else {
        1f
    }
    Text(
        text = text,
        color = accent,
        fontFamily = SpaceMono,
        fontSize = 8.sp,
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(2.dp))
            .background(accent.copy(alpha = 0.16f))
            .clickable { onClick() }
            .testTag(tag)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}
