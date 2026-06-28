package com.keyflow.ui.config

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.RecordingMode
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

/** Rótulo curto de [RecordingMode] para o [Segmented] (Teclado/Overlay). */
internal fun recordingModeLabel(mode: RecordingMode): String = when (mode) {
    RecordingMode.TOGGLE -> "Alternar"
    RecordingMode.PUSH_TO_TALK -> "Push-to-talk"
}

/**
 * Controle segmentado no estilo Ateliê (trilho `panel`, célula ativa preenchida `fg`/`onFill`).
 * Genérico — reusado por origem da transcrição (Voz), modo de gravação (Teclado/Overlay), posição e
 * tamanho do overlay.
 */
@Composable
internal fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    tag: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(palette.panel)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val isActive = option == selected
            Text(
                text = label(option),
                color = if (isActive) palette.onFill else palette.muted,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(3.dp))
                    .then(if (isActive) Modifier.background(palette.fg) else Modifier)
                    .clickable(enabled = !isActive) { onSelect(option) }
                    .padding(vertical = 9.dp)
                    .testTag(tag(option)),
            )
        }
    }
}

/** Pílula de toggle no estilo Ateliê (track preenchido quando ligado; knob `onFill`). */
@Composable
internal fun PillToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, tag: String) {
    val palette = LocalKeyFlowPalette.current
    val knobX by animateDpAsState(if (checked) 22.dp else 3.dp, label = "knobX")
    Box(
        Modifier
            .size(width = 44.dp, height = 25.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (checked) palette.teal else palette.line)
            .clickable { onCheckedChange(!checked) }
            .testTag(tag),
    ) {
        Box(
            Modifier
                .offset(x = knobX, y = 3.dp)
                .size(19.dp)
                .clip(CircleShape)
                .background(palette.onFill),
        )
    }
}

/**
 * Campo de texto com auto-save (CFG-05): [label] em mono + valor com underline; persiste via [onCommit]
 * **no desfoque (se mudou)** e também no ImeAction Done. O estado externo [value] reseta o texto após o
 * round-trip de persistência.
 */
@Composable
internal fun AutoSaveField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val palette = LocalKeyFlowPalette.current
    val focusManager = LocalFocusManager.current
    var text by remember(value) { mutableStateOf(value) }
    var wasFocused by remember { mutableStateOf(false) }

    fun commitIfChanged() {
        if (text != value) onCommit(text)
    }

    Column(modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            color = palette.faint,
            fontFamily = SpaceMono,
            fontSize = 8.5.sp,
        )
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                color = palette.fg2,
                fontFamily = SpaceMono,
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
                .padding(top = 5.dp, bottom = 7.dp)
                .testTag(tag)
                .onFocusChanged { state ->
                    if (wasFocused && !state.isFocused) commitIfChanged()
                    wasFocused = state.isFocused
                },
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
    }
}

/** Seção "Skins" bloqueada (SKIN-01): chip BLOQUEADO, swatches esmaecidos e CTA "Desbloquear skins". */
@Composable
internal fun SkinsSection(onUnlock: () -> Unit, unlockTag: String, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Skins".uppercase(), color = palette.faint, fontFamily = SpaceMono, fontSize = 8.5.sp)
            Row(
                Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.panel)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StrokeIcon(LOCK_PATHS, palette.faint, Modifier.size(9.dp), strokeWidth = 2f)
                Text("BLOQUEADO", color = palette.faint, fontFamily = SpaceMono, fontSize = 8.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 11.dp).alpha(0.4f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SkinSwatch(palette.fg)
            SkinSwatch(palette.teal)
            SkinSwatch(palette.terracotta)
            SkinSwatch(palette.bg, border = true)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(3.dp))
                .border(1.5.dp, palette.terracotta, RoundedCornerShape(3.dp))
                .clickable { onUnlock() }
                .testTag(unlockTag)
                .padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StrokeIcon(LOCK_OPEN_PATHS, palette.terracotta, Modifier.size(13.dp), strokeWidth = 1.8f)
            Text(
                "  Desbloquear skins",
                color = palette.terracotta,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SkinSwatch(color: Color, border: Boolean = false) {
    val palette = LocalKeyFlowPalette.current
    Box(
        Modifier
            .weight(1f)
            .height(40.dp)
            .clip(RoundedCornerShape(4.dp))
            .then(if (border) Modifier.border(1.dp, palette.line, RoundedCornerShape(4.dp)) else Modifier)
            .background(color),
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
                    drawPath(PathParser().parsePathString(d).toPath(), tint, style = Stroke(width = strokeWidth))
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

private val LOCK_PATHS = listOf("M3 11 h18 v11 h-18 z", "M7 11V7a5 5 0 0 1 10 0v4")
private val LOCK_OPEN_PATHS = listOf("M3 11 h18 v11 h-18 z", "M7 11V7a5 5 0 0 1 9.9-1")
