package com.keyflow.ui.config

import com.keyflow.i18n.tr
import androidx.compose.foundation.layout.fillMaxSize
import com.keyflow.ime.KeyboardSkin
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.OrbStyle
import com.keyflow.ui.orb.VoiceOrb
import com.keyflow.ui.orb.glitchBurstMidMs
import com.keyflow.ui.orb.orbStyleFor
import com.keyflow.ime.KeyboardColors
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.RecordingMode
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIconSpec
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

/** Rótulo curto de [RecordingMode] para o [Segmented] (Teclado/Overlay). */
internal fun recordingModeLabel(mode: RecordingMode): String = when (mode) {
    RecordingMode.TOGGLE -> tr("Alternar")
    RecordingMode.PUSH_TO_TALK -> "Push-to-talk"
}

/**
 * Controle segmentado no estilo Ateliê (trilho `panel`, célula ativa preenchida `fg`/`onFill`).
 * Genérico — reusado por origem da transcrição (Voz), modo de gravação (Teclado/Overlay), posição e
 * tamanho do overlay.
 *
 * [hint]: explicação curta sob cada opção, alinhada à célula. Todas reservam [hintLines] linhas, para a
 * altura não mudar com o texto de cada uma (gatilho de grade, pedido do Yan, 2026-09-28).
 */
@Composable
internal fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    tag: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    hint: ((T) -> String)? = null,
    hintLines: Int = 3,
    // Opção desenhada como ícone (ex.: ∞ = sem limite); o [label] dela vira o nome acessível.
    icon: ((T) -> KfIconSpec?)? = null,
    allowTwoRows: Boolean = true,
    disabled: (T) -> Boolean = { false },
    disabledLabel: ((T) -> String?)? = null,
) {
    val palette = LocalKeyFlowPalette.current
    val raised = raisedFill(palette)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val shouldWrapTwoRows = allowTwoRows && options.size >= 4 && (options.size >= 5 || maxWidth < (options.size * 72).dp)
        val itemsPerRow = if (shouldWrapTwoRows) (options.size + 1) / 2 else options.size
        // Com muitas opções num painel estreito, o rótulo encolhe até caber inteiro na célula.
        // Em 2 linhas, as células ganham mais largura e os nomes não são ocultados/cortados.
        val cellText = (maxWidth - SEGMENT_TRACK_PADDING * 2 - SEGMENT_GAP * (itemsPerRow - 1)) / itemsPerRow -
            SEGMENT_TEXT_PADDING * 2
        val fontSp = fittedFontSizeSp(
            labels = options.filter { icon?.invoke(it) == null }.map(label),
            maxTextWidth = cellText.coerceAtLeast(1.dp),
            maxSp = 12f,
            minSp = 8.5f,
            fontFamily = SpaceGrotesk,
        )
        Column(Modifier.fillMaxWidth()) {
            if (shouldWrapTwoRows) {
                val row1Count = (options.size + 1) / 2
                val row1 = options.take(row1Count)
                val row2 = options.drop(row1Count)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.panel)
                        .padding(SEGMENT_TRACK_PADDING),
                    verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
                    ) {
                        row1.forEach { option ->
                            SegmentCell(option, selected, label, tag, onSelect, fontSp, palette, raised, icon, disabled, disabledLabel)
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
                    ) {
                        row2.forEach { option ->
                            SegmentCell(option, selected, label, tag, onSelect, fontSp, palette, raised, icon, disabled, disabledLabel)
                        }
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.panel)
                        .padding(SEGMENT_TRACK_PADDING),
                    horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
                ) {
                    options.forEach { option ->
                        SegmentCell(option, selected, label, tag, onSelect, fontSp, palette, raised, icon, disabled, disabledLabel)
                    }
                }
            }
        if (hint != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = SEGMENT_TRACK_PADDING, end = SEGMENT_TRACK_PADDING, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
        ) {
            options.forEach { option ->
                val isActive = option == selected
                Text(
                    text = hint(option),
                    color = if (isActive) palette.muted else palette.faint,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Light,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    textAlign = TextAlign.Center,
                    minLines = hintLines,
                    maxLines = hintLines,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = SEGMENT_TEXT_PADDING)
                        .testTag("${tag(option)}-hint"),
                )
            }
        }
    }
    }
    }
}

/** Célula padrão de texto ou ícone do [Segmented]. */
@Composable
private fun <T> androidx.compose.foundation.layout.RowScope.SegmentCell(
    option: T,
    selected: T,
    label: (T) -> String,
    tag: (T) -> String,
    onSelect: (T) -> Unit,
    fontSp: Float,
    palette: com.keyflow.ui.theme.KeyFlowPalette,
    raised: Color,
    icon: ((T) -> KfIconSpec?)?,
    isDisabledOption: (T) -> Boolean,
    disabledLabel: ((T) -> String?)?,
) {
    val isActive = option == selected
    val isDisabled = isDisabledOption(option)
    val status = disabledLabel?.invoke(option)
    val optionIcon = icon?.invoke(option)
    if (optionIcon != null) {
        SegmentIcon(
            icon = optionIcon,
            description = label(option),
            isActive = isActive,
            sizeSp = fontSp * 1.5f,
            onClick = { onSelect(option) },
            tag = tag(option),
        )
        return
    }
    val cellModifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(9.dp))
            .then(
                if (isActive) {
                    Modifier
                        .background(raised)
                        .border(1.dp, palette.line, RoundedCornerShape(9.dp))
                } else {
                    Modifier
                },
            )
            .clickable(enabled = !isActive && !isDisabled) { onSelect(option) }
            .then(
                if (isDisabled) {
                    Modifier.semantics(mergeDescendants = true) { disabled() }
                } else {
                    Modifier
                },
            )
    if (status == null) {
        Text(
            text = label(option),
            color = when {
                isDisabled -> palette.faint
                isActive -> palette.fg
                else -> palette.muted
            },
            fontFamily = SpaceGrotesk,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
            fontSize = fontSp.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = cellModifier
                .padding(horizontal = SEGMENT_TEXT_PADDING, vertical = 9.dp)
                .testTag(tag(option)),
        )
    } else {
        Column(
            modifier = cellModifier
                .padding(horizontal = SEGMENT_TEXT_PADDING, vertical = 7.dp)
                .testTag(tag(option)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label(option),
                color = palette.faint,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Normal,
                fontSize = fontSp.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text(
                text = status,
                color = palette.faint,
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Medium,
                fontSize = 7.5.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/** Célula do [Segmented] desenhada como ícone: mesma altura das de texto, fundo "que sobe" quando ativa. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.SegmentIcon(
    icon: KfIconSpec,
    description: String,
    isActive: Boolean,
    sizeSp: Float,
    onClick: () -> Unit,
    tag: String,
) {
    val palette = LocalKeyFlowPalette.current
    val iconSize = with(androidx.compose.ui.platform.LocalDensity.current) { sizeSp.sp.toDp() }
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(9.dp))
            .then(
                if (isActive) {
                    Modifier
                        .background(raisedFill(palette))
                        .border(1.dp, palette.line, RoundedCornerShape(9.dp))
                } else {
                    Modifier
                },
            )
            .clickable(enabled = !isActive, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        KfIcon(icon, if (isActive) palette.fg else palette.muted, Modifier.size(iconSize))
    }
}

private val SEGMENT_TRACK_PADDING = 3.dp
private val SEGMENT_GAP = 2.dp

/** Folga lateral do rótulo dentro da célula — inclui a margem para o peso Medium da opção ativa. */
private val SEGMENT_TEXT_PADDING = 4.dp

/**
 * Superfície "elevada" sobre o trilho `panel` (opção ativa do [Segmented], cartões em destaque): o papel
 * mais claro no tema claro; no escuro, um degrau acima do trilho.
 */
internal fun raisedFill(palette: com.keyflow.ui.theme.KeyFlowPalette): Color =
    if (palette.isDark) palette.line else palette.kbKey

/**
 * "?" de ajuda: círculo fino com o ponto de interrogação, alvo de toque próprio de 28dp — o mesmo desenho
 * do "?" das permissões no onboarding. [description] é o que o leitor de tela anuncia.
 */
@Composable
internal fun HelpButton(description: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    Box(
        modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape).border(1.dp, palette.faint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("?", color = palette.faint, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 9.5.sp)
        }
    }
}

/**
 * O resto de uma aba de interface (Teclado/Sobrepor) abaixo do cartão do modo: travado, fica esmaecido e
 * uma camada por cima barra os controles — o toque avisa por quê ([onLockedTap]), e arrastar continua
 * rolando a aba (o toque não é consumido pela camada, só o dos controles embaixo dela).
 */
@Composable
internal fun LockedSection(
    locked: Boolean,
    onLockedTap: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().then(if (locked) Modifier.alpha(LOCKED_ALPHA) else Modifier),
            verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
            content = content,
        )
        if (locked) {
            Box(
                Modifier
                    .matchParentSize()
                    .testTag(READONLY_SCRIM_TAG)
                    .pointerInput(onLockedTap) { detectTapGestures(onTap = { onLockedTap() }) },
            )
        }
    }
}

/** Esmaecido do conteúdo travado — o mesmo que a aba inteira usava antes. */
private const val LOCKED_ALPHA = 0.45f

/** Papel quente do knob do interruptor e da tinta sobre acentos — igual nos dois temas. */
internal val PaperWarm = Color(0xFFF4ECCF)

/**
 * Rótulo de seção (eyebrow): caixa-alta com espaçamento largo, na cor discreta. Substitui o mono em
 * negrito, que lia como terminal ao lado da tipografia da tela.
 */
@Composable
internal fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalKeyFlowPalette.current.faint,
) {
    Text(
        text.uppercase(),
        color = color,
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Medium,
        fontSize = 8.5.sp,
        letterSpacing = 1.4.sp,
        modifier = modifier,
    )
}

/** Título de um ajuste (a pergunta da linha): tinta cheia, peso de texto. */
@Composable
internal fun SettingTitle(text: String, modifier: Modifier = Modifier, color: Color = LocalKeyFlowPalette.current.fg) {
    Text(
        text,
        color = color,
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        modifier = modifier,
    )
}

/** Explicação sob o título do ajuste: mais leve e mais clara, com entrelinha folgada. */
@Composable
internal fun SettingSub(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = LocalKeyFlowPalette.current.muted,
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Light,
        fontSize = 10.5.sp,
        lineHeight = 15.sp,
        modifier = modifier,
    )
}

/**
 * Botão principal (ação que conclui algo): bloco de tinta com cantos amplos e texto espaçado. [enabled]
 * falso só esmaece — quem chama decide se o toque ainda faz algo.
 */
@Composable
internal fun PrimaryButton(
    text: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: com.keyflow.ui.icons.KfIconSpec? = null,
    enabled: Boolean = true,
) {
    val palette = LocalKeyFlowPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) palette.fg else palette.line2)
            .clickable(enabled = enabled) { onClick() }
            .testTag(tag)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (enabled) palette.onFill else palette.faint
        if (icon != null) KfIcon(icon, ink, Modifier.size(15.dp), strokeWidth = 1.9f)
        Text(
            text,
            color = ink,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 12.5.sp,
            letterSpacing = 0.3.sp,
        )
    }
}

/**
 * Botão secundário (adicionar, ações de apoio): contorno fino de cantos amplos, ícone opcional e texto
 * na tinta da tela — [accent] troca a cor quando a ação merece destaque (ex.: teal no "+ grade").
 */
@Composable
internal fun SecondaryButton(
    text: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: com.keyflow.ui.icons.KfIconSpec? = null,
    accent: Color = LocalKeyFlowPalette.current.fg,
) {
    val palette = LocalKeyFlowPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, palette.line, RoundedCornerShape(14.dp))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                onClick()
            }
            .testTag(tag)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) KfIcon(icon, accent, Modifier.size(15.dp), strokeWidth = 1.9f)
        Text(text, color = accent, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.2.sp)
    }
}

/** Nota de apoio (avisos brandos do editor): fundo `panel`, texto leve — sem caixa de contorno. */
@Composable
internal fun InfoNote(text: String, tag: String, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.panel)
            // O texto é filho da linha: sem juntar a semântica, a tag apontava para um nó sem texto.
            .semantics(mergeDescendants = true) {}
            .testTag(tag)
            .padding(start = 12.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.padding(top = 5.dp).size(5.dp).clip(CircleShape).background(palette.muted))
        Text(
            text,
            color = palette.fg2,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Light,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Uma área da aba: título (e explicação opcional) FORA do cartão, e as opções DENTRO de um cartão de
 * papel, separadas por [GroupDivider]. É o que separa visualmente um assunto do outro — pedido do Yan
 * (2026-09-28): as abas liam como um bloco só de texto, sem distinção entre as áreas. Entre grupos,
 * [GROUP_GAP] de respiro.
 */
@Composable
internal fun SettingsGroup(
    title: String?,
    modifier: Modifier = Modifier,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AreaTitle(title, Modifier.weight(1f))
                trailing?.invoke()
            }
        }
        if (description != null) SettingSub(description, Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = if (title != null || description != null) 12.dp else 0.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(palette.card)
                .border(1.dp, palette.line2, RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 4.dp),
            content = content,
        )
    }
}

/** Respiro entre dois [SettingsGroup] — a separação entre assuntos vem daqui, não de fios grossos. */
internal val GROUP_GAP = 32.dp

/** Fio entre opções dentro de um [SettingsGroup]. */
@Composable
internal fun GroupDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalKeyFlowPalette.current.line2))
}

/**
 * Uma opção dentro de um [SettingsGroup] com título, explicação e o controle embaixo (segmentado,
 * slider…). Padding vertical generoso: cada opção é uma coisa só, com ar em volta.
 */
@Composable
internal fun GroupItem(
    title: String?,
    sub: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        if (title != null) SettingTitle(title)
        if (sub != null) SettingSub(sub, Modifier.padding(top = 3.dp))
        content()
    }
}

/** Fio que separa blocos de ajustes dentro de uma aba. */
@Composable
internal fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalKeyFlowPalette.current.line2))
}

/**
 * Título de uma área da aba (Usabilidade, Atalhos, Layout do teclado): maior e leve, tracking fechado —
 * é o que dá ritmo editorial à rolagem.
 */
@Composable
internal fun AreaTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = LocalKeyFlowPalette.current.fg,
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        letterSpacing = (-0.3).sp,
        modifier = modifier,
    )
}

/**
 * Slider da config: trilho fino (3dp) com a parte percorrida em teal e um botão de papel com aro teal —
 * no lugar do trilho grosso com cursor em barra do Material 3.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun KfSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        modifier = modifier.fillMaxWidth().testTag(tag),
        thumb = {
            Box(
                Modifier
                    .size(20.dp)
                    .shadow(1.5.dp, CircleShape)
                    .clip(CircleShape)
                    .background(PaperWarm)
                    .border(1.5.dp, palette.teal, CircleShape),
            )
        },
        track = { state ->
            val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
            val fraction = ((state.value - valueRange.start) / span).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                val y = size.height / 2f
                drawLine(palette.line, Offset(0f, y), Offset(size.width, y), strokeWidth = size.height, cap = StrokeCap.Round)
                drawLine(palette.teal, Offset(0f, y), Offset(size.width * fraction, y), strokeWidth = size.height, cap = StrokeCap.Round)
            }
        },
    )
}

/** Radio da config: aro fino; selecionado, aro teal com o miolo cheio. */
@Composable
internal fun KfRadio(selected: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    Box(
        modifier
            .size(18.dp)
            .clip(CircleShape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) palette.teal else palette.muted.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(palette.teal))
    }
}

/** Pílula de toggle no estilo Ateliê (track teal quando ligado; vazado com fio quando desligado). */
@Composable
internal fun PillToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    enabled: Boolean = true,
) {
    val palette = LocalKeyFlowPalette.current
    val knobX by animateDpAsState(if (checked) 20.dp else 4.dp, label = "knobX")
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .size(width = 42.dp, height = 24.dp)
            .clip(shape)
            .background(if (checked) palette.teal else Color.Transparent)
            .border(1.dp, if (checked) palette.teal else palette.line, shape)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .testTag(tag),
    ) {
        Box(
            Modifier
                .offset(x = knobX, y = 4.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(if (checked) PaperWarm else palette.faint),
        )
    }
}

/**
 * Stepper de inteiro (CFG-04, dimensões de grade): "−" / valor / "+", clampado em [range]. Os botões
 * ficam esmaecidos e inertes na borda do intervalo (sem baixar de min nem subir de max).
 */
@Composable
internal fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(label, Modifier.weight(1f), color = palette.muted)
        StepperButton("−", enabled = value > range.first, tag = "$tag-dec") { onValueChange(value - 1) }
        Text(
            value.toString(),
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 12.dp).testTag(tag),
        )
        StepperButton("+", enabled = value < range.last, tag = "$tag-inc") { onValueChange(value + 1) }
    }
}

@Composable
private fun StepperButton(symbol: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .border(1.dp, palette.line, CircleShape)
            .clickable(enabled = enabled) { onClick() }
            .alpha(if (enabled) 1f else 0.4f)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 15.sp)
    }
}

/**
 * Campo de texto com auto-save (CFG-05): [label] em mono + valor com underline; persiste via [onCommit]
 * **no desfoque (se mudou)** e também no ImeAction Done. O estado externo [value] reseta o texto após o
 * round-trip de persistência. Com [pending], o texto ainda não confirmado também sai a pedido (o Testar).
 */
@Composable
internal fun AutoSaveField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    masked: Boolean = false,
    pending: PendingEdits? = null,
) {
    val palette = LocalKeyFlowPalette.current
    val focusManager = LocalFocusManager.current
    var text by remember(value) { mutableStateOf(value) }
    var wasFocused by remember { mutableStateOf(false) }
    var maskedEditDirty by remember(value) { mutableStateOf(false) }
    // Sempre o valor mais recente para o commit no descarte (a troca de aba pode pular o focus-out).
    val currentText by rememberUpdatedState(text)
    val currentValue by rememberUpdatedState(value)

    fun commitIfChanged() {
        if (masked && text.isBlank() && !maskedEditDirty) {
            text = value
        } else if (text != value) onCommit(text)
    }

    // Quem age sobre o que está na tela (o Testar) pode pedir o texto ainda não confirmado, sem esperar o desfoque.
    val confirmNow by rememberUpdatedState({ commitIfChanged() })
    DisposableEffect(pending) {
        val untrack = pending?.track { confirmNow() }
        onDispose { untrack?.invoke() }
    }

    // Salva ao sair de cena (ex.: trocar de aba) se o valor mudou — rede de segurança do focus-out.
    DisposableEffect(Unit) {
        onDispose {
            if (currentText != currentValue && !(masked && currentText.isBlank() && !maskedEditDirty)) {
                onCommit(currentText)
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        Eyebrow(label)
        BasicTextField(
            value = text,
            onValueChange = { new ->
                // Inserção em bloco (colar) salva na hora; digitação caractere a caractere só salva no
                // desfoque/Done/descarte.
                val pasted = new.length - text.length > 1
                text = new
                if (masked) maskedEditDirty = true
                if (pasted) commitIfChanged()
            },
            singleLine = true,
            visualTransformation = if (keyboardType == KeyboardType.Password) {
                PasswordVisualTransformation('•')
            } else {
                VisualTransformation.None
            },
            textStyle = LocalTextStyle.current.copy(
                color = palette.fg,
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Light,
                fontSize = 12.sp,
            ),
            cursorBrush = SolidColor(palette.teal),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                commitIfChanged()
                focusManager.clearFocus()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 9.dp)
                .testTag(tag)
                .onFocusChanged { state ->
                    if (state.isFocused && masked && text == value) {
                        text = ""
                        maskedEditDirty = false
                    }
                    if (wasFocused && !state.isFocused) commitIfChanged()
                    wasFocused = state.isFocused
                },
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
    }
}

/**
 * Campo de seleção (dropdown) no estilo Ateliê: [label] em mono + valor atual com chevron e underline.
 * Abre um [DropdownMenu] com as [options]; [display] formata cada opção (ex.: código → nome do idioma).
 * Usado na aba Voz para modelo e idioma com listas pré-definidas (padrão Whisper).
 */
@Composable
internal fun Dropdown(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    display: (String) -> String = { it },
) {
    val palette = LocalKeyFlowPalette.current
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Eyebrow(label)
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(top = 8.dp, bottom = 9.dp)
                    .testTag(tag),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = display(selected),
                    color = palette.fg,
                    fontFamily = SpaceGrotesk,
                    fontSize = 13.sp,
                )
                KfIcon(KfIcons.ChevronDown, palette.muted, Modifier.size(16.dp))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = palette.card,
            ) {
                options.forEach { option ->
                    val isActive = option == selected
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = display(option),
                                color = if (isActive) palette.teal else palette.fg,
                                fontFamily = SpaceGrotesk,
                                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                                fontSize = 13.sp,
                            )
                        },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                        colors = MenuDefaults.itemColors(),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
    }
}

/**
 * Seção "Skins" (SKIN-01): chip BLOQUEADO, swatches esmaecidos e CTA "Desbloquear skins". Com
 * [onSkinSelected] (aba Teclado), as primeiras são as cores do teclado, livres: **Sistema** (padrão,
 * como o Samsung — pedido do Yan, 2026-09-28), **Ateliê** (a ciano que antes vinha marcada) e **Aero**.
 * Com [onOrbSkinSelected] (aba Sobrepor), são as skins da orbe — **Ateliê** e **Aero**, cada uma com a
 * própria orbe de prévia —, que é uma escolha à parte da do teclado (Yan, 2026-09-29).
 */
@Composable
internal fun SkinsSection(
    onUnlock: () -> Unit,
    unlockTag: String,
    modifier: Modifier = Modifier,
    skinsUnlocked: Boolean = false,
    selectedSkin: String? = null,
    onSkinSelected: ((String) -> Unit)? = null,
    selectedOrbSkin: String? = null,
    onOrbSkinSelected: ((String) -> Unit)? = null,
) {
    val palette = LocalKeyFlowPalette.current
    fun selectOrb(style: OrbStyle) {
        if (!style.paid || skinsUnlocked) onOrbSkinSelected?.invoke(style.id) else onUnlock()
    }
    fun selectKeyboard(skin: KeyboardSkin) {
        if (!skin.paid || skinsUnlocked) onSkinSelected?.invoke(skin.id) else onUnlock()
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Skins"), color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, palette.line, RoundedCornerShape(50))
                    .padding(start = 7.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KfIcon(
                    if (skinsUnlocked) KfIcons.Check else KfIcons.Lock,
                    if (skinsUnlocked) palette.teal else palette.faint,
                    Modifier.size(11.dp),
                    strokeWidth = 2f,
                )
                Eyebrow(if (skinsUnlocked) tr("Desbloqueado") else tr("Bloqueado"))
            }
        }
        if (onOrbSkinSelected != null) {
            SettingSub(
                tr("A skin da orbe flutuante. O teclado tem a dele, na aba Teclado."),
                Modifier.padding(top = 6.dp),
            )
        } else if (onSkinSelected != null) {
            SettingSub(
                tr("As cores do teclado. A orbe flutuante tem a skin dela, na aba Sobrepor."),
                Modifier.padding(top = 6.dp),
            )
        }
        // A cor em uso (teal/orbe) aparece SELECIONADA (anel + opaca); as demais ficam esmaecidas (locked).
        Row(
            Modifier.fillMaxWidth().padding(top = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onOrbSkinSelected != null) {
        val orbSelected = orbStyleFor(selectedOrbSkin ?: OrbStyle.ATELIE.id, skinsUnlocked)
                SkinChoice(
                    label = tr("Ateliê"),
                    selected = orbSelected == OrbStyle.ATELIE,
                    onClick = { selectOrb(OrbStyle.ATELIE) },
                    tag = orbSkinTag(OrbStyle.ATELIE.id),
                ) {
                    Box(Modifier.fillMaxSize().background(palette.bg), contentAlignment = Alignment.Center) {
                        VoiceOrb(OrbState.Idle, Modifier.requiredSize(ORB_CHOICE_SIDE), timeProvider = ORB_CHOICE_CLOCK, style = OrbStyle.ATELIE)
                    }
                }
                SkinChoice(
                    label = "Aero",
                    selected = orbSelected == OrbStyle.AERO,
                    onClick = { selectOrb(OrbStyle.AERO) },
                    tag = orbSkinTag(OrbStyle.AERO.id),
                ) {
                    // O céu por trás, para a bolha de vidro mostrar o que atravessa.
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(aeroSkyColors(palette.isDark))), contentAlignment = Alignment.Center) {
                        VoiceOrb(OrbState.Idle, Modifier.requiredSize(ORB_CHOICE_SIDE), timeProvider = ORB_CHOICE_CLOCK, style = OrbStyle.AERO)
                    }
                }
                SkinChoice(
                    label = "Glitch",
                    selected = orbSelected == OrbStyle.GLITCH,
                    onClick = { selectOrb(OrbStyle.GLITCH) },
                    tag = orbSkinTag(OrbStyle.GLITCH.id),
                ) {
                    // Congelada no pico de uma rajada, para a amostra mostrar a falha e não só as franjas.
                    Box(Modifier.fillMaxSize().background(palette.bg), contentAlignment = Alignment.Center) {
                        VoiceOrb(OrbState.Idle, Modifier.requiredSize(ORB_CHOICE_SIDE), timeProvider = ORB_GLITCH_CHOICE_CLOCK, style = OrbStyle.GLITCH)
                    }
                }
                SkinChoice(
                    label = "Rose Gold",
                    selected = orbSelected == OrbStyle.ROSE_GOLD,
                    onClick = { selectOrb(OrbStyle.ROSE_GOLD) },
                    tag = orbSkinTag(OrbStyle.ROSE_GOLD.id),
                ) {
                    Box(Modifier.fillMaxSize().background(palette.bg), contentAlignment = Alignment.Center) {
                        VoiceOrb(OrbState.Idle, Modifier.requiredSize(ORB_CHOICE_SIDE), timeProvider = ORB_CHOICE_CLOCK, style = OrbStyle.ROSE_GOLD)
                    }
                }
                SkinChoice(
                    label = tr("Mascote"),
                    selected = orbSelected == OrbStyle.MASCOT,
                    onClick = { selectOrb(OrbStyle.MASCOT) },
                    tag = orbSkinTag(OrbStyle.MASCOT.id),
                ) {
                    // Menor que as outras prévias: as orelhas do mascote passam do miolo da orbe, e o cartão recorta o que sobra.
                    Box(Modifier.fillMaxSize().background(palette.bg), contentAlignment = Alignment.Center) {
                        VoiceOrb(OrbState.Idle, Modifier.requiredSize(ORB_MASCOT_CHOICE_SIDE), timeProvider = ORB_MASCOT_CHOICE_CLOCK, style = OrbStyle.MASCOT)
                    }
                }
            } else if (onSkinSelected == null) {
                SkinSwatch(palette.fg)
                SkinSwatch(palette.teal, selected = true)
            } else {
                val system = KeyboardColors.of(KeyboardSkin.SYSTEM, palette.isDark)
                SkinChoice(
                    label = tr("Sistema"),
                    selected = nativeKeyboardSkin(selectedSkin, skinsUnlocked) == KeyboardSkin.SYSTEM,
                    onClick = { selectKeyboard(KeyboardSkin.SYSTEM) },
                    tag = skinTag(KeyboardSkin.SYSTEM.id),
                ) {
                    // Fundo e tecla do próprio esquema, como uma tecla do Samsung.
                    Box(Modifier.fillMaxSize().background(Color(system.tray)).padding(horizontal = 10.dp, vertical = 9.dp)) {
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(Color(system.key)))
                    }
                }
                SkinChoice(
                    label = tr("Ateliê"),
                    selected = nativeKeyboardSkin(selectedSkin, skinsUnlocked) == KeyboardSkin.ATELIE,
                    onClick = { selectKeyboard(KeyboardSkin.ATELIE) },
                    tag = skinTag(KeyboardSkin.ATELIE.id),
                ) {
                    Box(Modifier.fillMaxSize().background(palette.teal))
                }
                val aero = KeyboardColors.of(KeyboardSkin.AERO, palette.isDark)
                SkinChoice(
                    label = "Aero",
                    selected = nativeKeyboardSkin(selectedSkin, skinsUnlocked) == KeyboardSkin.AERO,
                    onClick = { selectKeyboard(KeyboardSkin.AERO) },
                    tag = skinTag(KeyboardSkin.AERO.id),
                ) {
                    // Céu e colina da paisagem, com uma tecla de vidro por cima.
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(aeroSkyColors(palette.isDark))).padding(horizontal = 10.dp, vertical = 9.dp)) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(aero.key))
                                .border(1.dp, Color(aero.keyBorder), RoundedCornerShape(4.dp)),
                        )
                    }
                }
                SkinChoice(
                    label = "Glitch",
                    selected = nativeKeyboardSkin(selectedSkin, skinsUnlocked) == KeyboardSkin.GLITCH,
                    onClick = { selectKeyboard(KeyboardSkin.GLITCH) },
                    tag = skinTag(KeyboardSkin.GLITCH.id),
                ) {
                    // Uma tecla cinza escuro sobre o fundo neutro, com as duas marcas de registro: ciano em cima à
                    // esquerda, vermelho embaixo à direita (o efeito só em detalhes, como no teclado).
                    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0C)).padding(horizontal = 10.dp, vertical = 9.dp)) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color(0xFF191919), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(4.dp)),
                        )
                        Box(Modifier.align(Alignment.TopStart).padding(start = 5.dp).width(9.dp).height(1.5.dp).background(Color(0xFF25F4EE)))
                        Box(Modifier.align(Alignment.BottomEnd).padding(end = 5.dp).width(9.dp).height(1.5.dp).background(Color(0xFFFE2C55)))
                    }
                }
                SkinChoice(
                    label = "Rose Gold",
                    selected = nativeKeyboardSkin(selectedSkin, skinsUnlocked) == KeyboardSkin.ROSE_GOLD,
                    onClick = { selectKeyboard(KeyboardSkin.ROSE_GOLD) },
                    tag = skinTag(KeyboardSkin.ROSE_GOLD.id),
                ) {
                    // Fundo blush e uma tecla de cetim rosé com o bisel de metal.
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF8E7E2), Color(0xFFEECFC8)))).padding(horizontal = 10.dp, vertical = 9.dp)) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(listOf(Color(0xFFFFF0EB), Color(0xFFEBC3BA))), RoundedCornerShape(4.dp))
                                .border(1.dp, Brush.verticalGradient(listOf(Color(0xFFFFF7EE), Color(0xFFB97A75))), RoundedCornerShape(4.dp)),
                        )
                    }
                }
            }
            // Amostras travadas (decorativas) para completar a fileira de cinco: o teclado tem 5 escolhas e a orbe também 5 (com o
            // Mascote), então só a seção sem escolha nenhuma precisa delas.
            if (onSkinSelected == null && onOrbSkinSelected == null) SkinSwatch(palette.terracotta)
            if (onSkinSelected == null && onOrbSkinSelected == null) SkinSwatch(palette.bg, border = true)
        }
        if (!skinsUnlocked) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, palette.terracotta.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .clickable { onUnlock() }
                    .testTag(unlockTag)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KfIcon(KfIcons.LockOpen, palette.terracotta, Modifier.size(15.dp))
                Text(
                    tr("Desbloquear skins"),
                    color = palette.terracotta,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    letterSpacing = 0.2.sp,
                )
            }
        }
    }
}

fun skinTag(id: String) = "teclado-skin-$id"

fun orbSkinTag(id: String) = "overlay-orb-skin-$id"

/**
 * Relógio parado das orbes de prévia: a forma não se mexe. Uma orbe animada mantém o Compose sempre ocupado
 * (os testes nunca ficam ociosos) e gasta bateria à toa numa amostra de 40dp.
 */
private val ORB_CHOICE_CLOCK: () -> Float = { 1234f }

/** O relógio parado da amostra do Glitch: o pico de uma rajada de falha em repouso. */
private val ORB_GLITCH_CHOICE_CLOCK: () -> Float = { glitchBurstMidMs(OrbState.Idle) }

/** Lado da orbe de prévia (maior que o cartão de 40dp: o cartão recorta o excesso, e o miolo cabe). */
private val ORB_CHOICE_SIDE = 60.dp

/**
 * A prévia do mascote: o relógio parado num instante de olhos abertos e orelhas em repouso (o de 1234 ms cairia no meio de uma
 * piscada), e menor que as outras — as orelhas e o corpo cabem inteiros no cartão de 40dp.
 */
private val ORB_MASCOT_CHOICE_CLOCK: () -> Float = { 1_500f }
private val ORB_MASCOT_CHOICE_SIDE = 44.dp

/** Céu e colina da paisagem Aero, de cima para baixo — o fundo das prévias do Aero (teclado e orbe). */
private fun aeroSkyColors(dark: Boolean): List<Color> = if (dark) {
    listOf(Color(0xFF030A22), Color(0xFF0A4A70), Color(0xFF07303C))
} else {
    listOf(Color(0xFF1C7BD6), Color(0xFFA5E0FB), Color(0xFF6CC75A))
}

/** Cor do teclado que dá para escolher: swatch clicável com o nome embaixo e anel quando é a atual. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.SkinChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
    swatch: @Composable () -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .testTag(tag)
                .then(
                    if (selected) {
                        Modifier.border(1.5.dp, palette.fg, RoundedCornerShape(10.dp))
                    } else {
                        Modifier.border(1.dp, palette.line, RoundedCornerShape(10.dp))
                    },
                ),
        ) { swatch() }
        Text(
            label,
            color = if (selected) palette.fg else palette.muted,
            fontFamily = SpaceGrotesk,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            fontSize = 9.5.sp,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SkinSwatch(
    color: Color,
    border: Boolean = false,
    selected: Boolean = false,
) {
    val palette = LocalKeyFlowPalette.current
    Box(
        Modifier
            .weight(1f)
            .height(40.dp)
            // Não-selecionados esmaecidos (efeito "bloqueado"); o selecionado fica opaco.
            .then(if (selected) Modifier else Modifier.alpha(0.4f))
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            // Anel de seleção (cor do texto, contrasta nos dois temas); senão, borda só p/ swatch claro.
            .then(
                when {
                    selected -> Modifier.border(1.5.dp, palette.fg, RoundedCornerShape(10.dp))
                    border -> Modifier.border(1.dp, palette.line, RoundedCornerShape(10.dp))
                    else -> Modifier
                },
            ),
    )
}

/** Ícone de stroke a partir de paths SVG (viewBox 24×24). Compartilhado pelos painéis de config. */
@Composable
internal fun StrokeIcon(paths: List<String>, tint: Color, modifier: Modifier, strokeWidth: Float = 1.6f) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        translate((size.width - 24f * s) / 2f, (size.height - 24f * s) / 2f) {
            scale(s, s, pivot = Offset.Zero) {
                paths.forEach { d ->
                    drawPath(
                        PathParser().parsePathString(d).toPath(),
                        tint,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
    }
}

/** Ícone preenchido a partir de um path SVG (viewBox 24×24). */
@Composable
internal fun FillIcon(path: String, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        translate((size.width - 24f * s) / 2f, (size.height - 24f * s) / 2f) {
            scale(s, s, pivot = Offset.Zero) {
                drawPath(PathParser().parsePathString(path).toPath(), tint)
            }
        }
    }
}


/**
 * Maior tamanho de fonte (em sp), de [maxSp] pra baixo, que faz todos os [labels] caberem em
 * [maxTextWidth]. Mede de verdade com [rememberTextMeasurer] em vez de estimar largura de caractere: a
 * primeira versão do teclado escalado do picker (Q8) chutava "largura do char ≈ 0.58 × fontSize" e cortava
 * "Home"/"PgUp"/"End" na tela do A32 — a conta ignorava tanto a métrica real da SpaceGrotesk quanto o
 * `fontScale` do sistema.
 */
@Composable
internal fun fittedFontSizeSp(
    labels: List<String>,
    maxTextWidth: Dp,
    maxSp: Float,
    minSp: Float,
    fontFamily: FontFamily,
): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Todos os rótulos disputam o limite: o de mais letras nem sempre é o mais largo ("Custom" passa de
    // "Credits" em pixels — em inglês ele saía cortado). São poucos por seletor, e o resultado fica guardado.
    val longest = labels.distinct()
    return remember(longest, maxTextWidth, maxSp, minSp, density.density, density.fontScale, fontFamily) {
        var sp = maxSp
        while (sp > minSp) {
            val fits = longest.all { label ->
                val width = measurer.measure(
                    AnnotatedString(label),
                    style = TextStyle(fontFamily = fontFamily, fontSize = sp.sp),
                ).size.width
                with(density) { width.toDp() } <= maxTextWidth
            }
            if (fits) break
            sp -= FONT_FIT_STEP_SP
        }
        sp.coerceAtLeast(minSp)
    }
}

private const val FONT_FIT_STEP_SP = 0.5f

/**
 * Linha de um cenário de cola automática: rótulo + descrição curta à esquerda, [PillToggle] à direita.
 * Quando [enabled] é falso a linha fica esmaecida (somente-leitura) e o toque chama [onDisabledClick]
 * em vez de alternar — mesmo padrão das abas/menus inativos.
 */
@Composable
internal fun ToggleRow(
    label: String,
    sub: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    enabled: Boolean = true,
    onDisabledClick: () -> Unit = {},
) {
    val palette = LocalKeyFlowPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier else Modifier.clickable { onDisabledClick() })
            .padding(vertical = 16.dp)
            .then(if (enabled) Modifier else Modifier.alpha(0.45f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 11.5.sp)
            Text(
                sub,
                color = palette.muted,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Light,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        PillToggle(checked = checked, onCheckedChange = onCheckedChange, tag = tag, enabled = enabled)
    }
}
