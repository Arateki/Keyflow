package com.keyflow.ui.config

import com.keyflow.i18n.AppLocale
import com.keyflow.i18n.tr
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.ButtonState
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.fluxos.ConfigFluxos
import com.keyflow.ime.KeyFlowKeyboardView
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.orb.OrbCenter
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.VoiceOrb
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIconSpec
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.tour.GestureScript
import com.keyflow.ui.tour.GestureTourPlayer
import com.keyflow.ui.tour.TourFrame
import com.keyflow.ui.tour.buildKeyboardScripts
import com.keyflow.ui.tour.buildOrbScripts
import com.keyflow.ui.tour.rememberTourTimeMs
import com.keyflow.ui.tour.tourArrowPulse
import com.keyflow.ui.tour.rememberGestureTourState
import com.keyflow.ui.tour.scenes.KeyboardScene
import com.keyflow.ui.tour.scenes.OrbScene

const val ACTIVATE_OVERLAY_TAG = "activate-overlay"
const val ACTIVATE_KEYBOARD_TAG = "activate-keyboard"
const val ACTIVATE_MIC_TAG = "activate-mic"
const val ACTIVATE_NOTIFICATION_TAG = "activate-notification"
const val KEYBOARD_SWITCH_TAG = "keyboard-switch"

/** Tours dos cards. Título/descrição/setas derivam destas: `<tag>-title`, `-desc`, `-prev`, `-next`. */
const val MODO_TOUR_OVERLAY_TAG = "modo-tour-overlay"
const val MODO_TOUR_KEYBOARD_TAG = "modo-tour-keyboard"

/** Permissão de microfone: a mesma nos dois cartões (sincronizada, estado único). */
private fun micPermission(permissions: PermissionStatus, onRequestMicPermission: () -> Unit) = CardPermission(
    label = tr("Microfone"),
    granted = permissions.microphoneGranted,
    onActivate = onRequestMicPermission,
    activateTag = ACTIVATE_MIC_TAG,
)

/**
 * Cartão do Modo Sobrepor (CFG-06 / REC-07): liga/desliga a orbe flutuante, mostra as permissões que ela
 * precisa (com "Ativar" quando pendente) e o tour dos gestos. Primeiro cartão da aba Sobrepor desde que a
 * aba Modo saiu (Yan, 2026-09-28) — é ele que destrava o resto da aba.
 */
@Composable
fun OverlayModeCard(
    settings: Settings,
    permissions: PermissionStatus,
    onOverlayModeChange: (Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    highlightKey: Int = 0,
    configFluxos: ConfigFluxos = ConfigFluxos(emptyList(), emptyList()),
) {
    ModeCard(
        title = tr("Modo Sobrepor"),
        checked = settings.overlayModeEnabled,
        onCheckedChange = onOverlayModeChange,
        toggleTag = TOGGLE_OVERLAY_MODE_TAG,
        highlightKey = highlightKey,
        permissions = listOf(
            micPermission(permissions, onRequestMicPermission),
            CardPermission(
                label = tr("Sobrepor"),
                granted = permissions.overlayGranted,
                onActivate = onRequestOverlayPermission,
                activateTag = ACTIVATE_OVERLAY_TAG,
            ),
            CardPermission(
                label = tr("Notificações"),
                granted = permissions.notificationsGranted,
                onActivate = onRequestNotificationPermission,
                activateTag = ACTIVATE_NOTIFICATION_TAG,
            ),
        ),
        features = listOf(
            tr("Transcreva voz para texto em qualquer situação para sua área de transferência."),
        ),
        // Tour dos gestos da orbe (MODO-01). `remember` pelos campos que os roteiros consultam:
        // mudou o modo ou um atalho, a lista se refaz e o tour recomeça já correto (SCR-07).
        tourTag = MODO_TOUR_OVERLAY_TAG,
        tourScripts = remember(
            settings.overlayRecordingMode,
            settings.shortcut1Tap,
            settings.shortcut2Tap,
            settings.shortcut3Tap,
            configFluxos.gatilhos, // gesto ligado a fluxo: o tour descreve o fluxo
            configFluxos.fluxos,
            AppLocale.current, // os títulos dos gestos são texto traduzido
        ) { buildOrbScripts(settings, configFluxos) },
        tourScene = { frame -> OrbScene(frame, Modifier.fillMaxWidth()) },
    )
}

/**
 * Cartão do Modo Teclado: liga/desliga o KeyFlow como teclado, mostra as permissões, se ele é o teclado
 * ativo e o tour dos gestos. Primeiro cartão da aba Teclado desde que a aba Modo saiu (Yan, 2026-09-28).
 */
@Composable
fun KeyboardModeCard(
    settings: Settings,
    permissions: PermissionStatus,
    onKeyboardModeChange: (Boolean) -> Unit,
    onRequestKeyboardPermission: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    highlightKey: Int = 0,
) {
    ModeCard(
        title = tr("Modo Teclado"),
        checked = settings.keyboardModeEnabled,
        onCheckedChange = onKeyboardModeChange,
        toggleTag = TOGGLE_KEYBOARD_MODE_TAG,
        highlightKey = highlightKey,
        permissions = listOf(
            micPermission(permissions, onRequestMicPermission),
            CardPermission(
                label = tr("Teclado"),
                granted = permissions.keyboardEnabled,
                onActivate = onRequestKeyboardPermission,
                activateTag = ACTIVATE_KEYBOARD_TAG,
            ),
        ),
        features = listOf(
            tr("Cole a transcrição de voz automaticamente."),
            tr("Personalize em estilo e funcionalidade."),
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
        // Tour dos gestos do teclado (MODO-01/04): a cena usa a grade Canvas real, em vez da
        // demo parada que ficou anos mostrando a grade antiga.
        tourTag = MODO_TOUR_KEYBOARD_TAG,
        tourScripts = remember(AppLocale.current) { buildKeyboardScripts(settings) },
        tourScene = { frame -> KeyboardScene(frame, Modifier.fillMaxWidth()) },
    )
}

/**
 * Status do "teclado ativo" (DEFAULT_INPUT_METHOD) no card do Teclado, no mesmo estilo das permissões:
 * sempre acionável (abre o seletor para trocar) e pulsando quando o modo está ligado mas o KeyFlow ainda
 * não é o teclado ativo. Só aparece se o teclado já estiver habilitado.
 */
@Composable
private fun KeyboardActiveLine(active: Boolean, modeOn: Boolean, onSwitch: () -> Unit) {
    StatusChip(
        text = tr("Teclado ativo · %s", if (active) tr("sim") else tr("não")),
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
fun pauseHintFor(mode: RecordingMode): String = recordingHintParts(mode).joinToString(" · ") + "."

/**
 * A mesma frase de [pauseHintFor], em pedaços — um por gesto, na ordem em que se aprende.
 *
 * O tour de gestos (`ui/tour`) descreve um gesto por vez e precisa da frase partida; a config e a Docs
 * mostram a frase inteira. Manter os dois lados lendo daqui evita o texto divergir com o tempo, que é o
 * que aconteceu com as demos estáticas desta aba (GT/T1). `pauseHintFor` continua devolvendo exatamente
 * a mesma string de antes — há teste fixando isso, porque a aba Modo tem golden.
 */
fun recordingHintParts(mode: RecordingMode): List<String> = when (mode) {
    RecordingMode.TOGGLE -> listOf(
        tr("Toque para gravar ou parar"),
        tr("segure para pausar e retomar"),
    )
    RecordingMode.PUSH_TO_TALK -> listOf(
        tr("Segure para gravar"),
        tr("solte para enviar"),
        tr("toque rápido após soltar para pausar"),
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ModeCard(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    toggleTag: String,
    permissions: List<CardPermission>,
    features: List<String>,
    footer: (@Composable () -> Unit)? = null,
    /** Dica estática, para um card SEM tour. Com tour, quem descreve é o passo corrente (MODO-03). */
    pauseHint: String? = null,
    tourTag: String? = null,
    tourScripts: List<GestureScript>? = null,
    tourScene: (@Composable (TourFrame) -> Unit)? = null,
    highlightKey: Int = 0,
) {
    val palette = LocalKeyFlowPalette.current
    // Insight visual discreto: ao chegar de um aviso, o card pulsa uma borda teal que esvai.
    val highlight = remember { Animatable(0f) }
    LaunchedEffect(highlightKey) {
        if (highlightKey > 0) {
            highlight.snapTo(1f)
            highlight.animateTo(0f, animationSpec = tween(durationMillis = 1700))
        }
    }
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, palette.line2, shape)
            .then(
                if (highlight.value > 0f) {
                    Modifier.border(1.5.dp, palette.teal.copy(alpha = highlight.value), shape)
                } else {
                    Modifier
                },
            )
            .background(palette.card)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                title,
                color = palette.fg,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Medium,
                fontSize = 14.5.sp,
                letterSpacing = (-0.2).sp,
                modifier = Modifier.weight(1f),
            )
            PillToggle(checked = checked, onCheckedChange = onCheckedChange, tag = toggleTag)
        }
        FeatureList(features)
        FlowRow(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            permissions.forEach { perm ->
                // O próprio chip é o botão: abre as configs (concedido ou não) e pulsa quando o modo
                // está ligado mas a permissão ainda falta.
                StatusChip(
                    text = "${perm.label} · ${if (perm.granted) tr("concedido") else tr("pendente")}",
                    granted = perm.granted,
                    pulsing = !perm.granted && checked,
                    onClick = perm.onActivate,
                    tag = perm.activateTag,
                )
            }
        }
        footer?.invoke()
        if (tourTag != null && tourScripts != null && tourScene != null) {
            // Tour à esquerda; a descrição do gesto corrente na área livre à direita (MODO-03).
            val tour = rememberGestureTourState(tourScripts)
            // Altura FIXA: a prévia não pode crescer nem encolher com o gesto da vez (título de 1 ou 2
            // linhas, descrição longa ao lado) — o card inteiro saltava a cada troca (Yan, 2026-09-28).
            Row(
                Modifier.fillMaxWidth().height(DEMO_FRAME_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.weight(0.7f)) {
                    DemoFrame(nav = { TourNav(tourTag, tour.title, tour::prev, tour::next) }) {
                        GestureTourPlayer(
                            scripts = tourScripts,
                            state = tour,
                            modifier = Modifier.fillMaxWidth().testTag(tourTag),
                        ) { frame -> tourScene(frame) }
                    }
                }
                Text(
                    tour.description,
                    color = palette.muted,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Light,
                    fontSize = 9.sp,
                    lineHeight = 12.5.sp,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.3f).testTag("$tourTag-desc"),
                )
            }
        } else if (pauseHint != null) {
            Text(pauseHint, color = palette.muted, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Light, fontSize = 9.5.sp)
        }
    }
}

/** Altura fixa da moldura da prévia (rótulo + cena de [TOUR_SCENE_HEIGHT] + navegação de 2 linhas). */
private val DEMO_FRAME_HEIGHT = 184.dp

/**
 * Moldura de "prévia": fundo levemente variante (o `bg`, mais recuado que o card) + borda e o rótulo
 * PRÉVIA, deixando claro que o conteúdo é uma **representação**, não a interface real.
 */
@Composable
private fun DemoFrame(nav: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .height(DEMO_FRAME_HEIGHT)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.bg)
            .border(1.dp, palette.line2, RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Eyebrow(tr("Prévia"), color = palette.faint.copy(alpha = 0.8f))
        // A cena ocupa o que sobra entre o rótulo e a navegação — a moldura é que manda na altura.
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { content() }
        nav?.invoke()
    }
}

/**
 * Navegação do tour no rodapé da moldura (MODO-02): ‹ título ›. As setas existem para achar um gesto
 * específico sem esperar o autoplay dar a volta.
 */
@Composable
private fun TourNav(tag: String, title: String, onPrev: () -> Unit, onNext: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Arrow(KfIcons.ChevronLeft, tr("gesto anterior"), "$tag-prev", onPrev)
        Text(
            title,
            color = palette.fg2,
            fontFamily = SpaceGrotesk,
            fontSize = 8.5.sp,
            textAlign = TextAlign.Center,
            // Duas linhas: os títulos dos atalhos ("2 toques · Retrair / expandir teclado") não cabem
            // em uma na largura do card, e cortados viram "Retrair / expa…" — o rótulo perde o sentido
            // justamente nos gestos que a pessoa configurou (visto no A32 do Yan, 2026-09-09).
            // Sempre duas linhas reservadas: título curto ou longo, a moldura não muda de altura.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 11.sp,
            modifier = Modifier.weight(1f).testTag("$tag-title"),
        )
        // Só a seta de AVANÇAR pulsa: o tour não anda sozinho, então ela é o caminho adiante e precisa
        // se anunciar. A de voltar não pulsa — duas piscando viram ruído.
        Arrow(KfIcons.ChevronRight, tr("próximo gesto"), "$tag-next", onNext, pulsing = true)
    }
}

@Composable
private fun Arrow(glyph: KfIconSpec, label: String, tag: String, onClick: () -> Unit, pulsing: Boolean = false) {
    val palette = LocalKeyFlowPalette.current
    // Pulsando, a seta usa o teal — a cor de ação do app — para ler como convite, e não como um
    // elemento apagando e acendendo. Parada, fica no cinza dos textos secundários.
    val color = if (pulsing) {
        palette.teal.copy(alpha = tourArrowPulse(rememberTourTimeMs()))
    } else {
        palette.fg2
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .testTag(tag),
    ) {
        KfIcon(glyph, color, Modifier.size(15.dp), strokeWidth = 2f)
    }
}

/**
 * Funcionalidades do modo como uma tabelinha minimalista: linhas com separadores finos, marcador teal
 * e o texto à direita. Logo abaixo da descrição de uma linha.
 */
@Composable
private fun FeatureList(features: List<String>) {
    val palette = LocalKeyFlowPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        features.forEach { feature ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                KfIcon(KfIcons.Check, palette.teal, Modifier.padding(top = 2.dp).size(13.dp), strokeWidth = 2f)
                Text(
                    feature,
                    color = palette.fg2,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Light,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
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
internal fun StatusChip(
    text: String,
    granted: Boolean,
    pulsing: Boolean,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
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
    // Cápsula de fio com um ponto de status: o chip deixa de ser um bloco colorido e vira etiqueta.
    val shape = RoundedCornerShape(50)
    // Compacta de propósito (Yan, 2026-09-28): em caixa-alta espaçada as etiquetas iam uma por linha e a
    // aba Modo deixava de caber na tela sem rolar. Em caixa normal cabem duas por linha no A32.
    Row(
        modifier
            .scale(scale)
            .clip(shape)
            .border(1.dp, accent.copy(alpha = 0.45f), shape)
            .clickable { onClick() }
            .testTag(tag)
            .padding(start = 7.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(4.dp).clip(CircleShape).background(accent))
        Text(
            text = text,
            color = accent,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 7.2.sp,
            maxLines = 1,
        )
    }
}
