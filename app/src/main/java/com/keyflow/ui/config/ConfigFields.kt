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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
internal fun PillToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    enabled: Boolean = true,
) {
    val palette = LocalKeyFlowPalette.current
    val knobX by animateDpAsState(if (checked) 22.dp else 3.dp, label = "knobX")
    Box(
        Modifier
            .size(width = 44.dp, height = 25.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (checked) palette.teal else palette.line)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
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
        Text(
            label,
            color = palette.muted,
            fontFamily = SpaceMono,
            fontSize = 9.sp,
            modifier = Modifier.weight(1f),
        )
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
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, palette.line, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onClick() }
            .alpha(if (enabled) 1f else 0.4f)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
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
    // Sempre o valor mais recente para o commit no descarte (a troca de aba pode pular o focus-out).
    val currentText by rememberUpdatedState(text)
    val currentValue by rememberUpdatedState(value)

    fun commitIfChanged() {
        if (text != value) onCommit(text)
    }

    // Salva ao sair de cena (ex.: trocar de aba) se o valor mudou — rede de segurança do focus-out.
    DisposableEffect(Unit) {
        onDispose { if (currentText != currentValue) onCommit(currentText) }
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
            onValueChange = { new ->
                // Inserção em bloco (colar) salva na hora; digitação caractere a caractere só salva no
                // desfoque/Done/descarte.
                val pasted = new.length - text.length > 1
                text = new
                if (pasted) commitIfChanged()
            },
            singleLine = true,
            visualTransformation = if (keyboardType == KeyboardType.Password) {
                PasswordVisualTransformation('*')
            } else {
                VisualTransformation.None
            },
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
        Text(
            text = label.uppercase(),
            color = palette.faint,
            fontFamily = SpaceMono,
            fontSize = 8.5.sp,
        )
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(top = 5.dp, bottom = 7.dp)
                    .testTag(tag),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = display(selected),
                    color = palette.fg2,
                    fontFamily = SpaceMono,
                    fontSize = 12.sp,
                )
                StrokeIcon(CHEVRON_DOWN, palette.muted, Modifier.size(14.dp), strokeWidth = 1.8f)
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
                                fontFamily = SpaceMono,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp,
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
            Text("Skins", color = palette.fg, fontFamily = SpaceGrotesk, fontSize = 13.5.sp)
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
        // A cor em uso (teal/orbe) aparece SELECIONADA (anel + opaca); as demais ficam esmaecidas (locked).
        Row(
            Modifier.fillMaxWidth().padding(top = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SkinSwatch(palette.fg)
            SkinSwatch(palette.teal, selected = true)
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
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            // Anel de seleção (cor do texto, contrasta nos dois temas); senão, borda só p/ swatch claro.
            .then(
                when {
                    selected -> Modifier.border(2.dp, palette.fg, RoundedCornerShape(4.dp))
                    border -> Modifier.border(1.dp, palette.line, RoundedCornerShape(4.dp))
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
private val CHEVRON_DOWN = listOf("M6 9 12 15 18 9")
