package com.keyflow.ui.config

import com.keyflow.i18n.tr
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.shortcuts.PhysicalKeyMap
import com.keyflow.core.shortcuts.ShortcutKey
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk

const val PHYSICAL_KEY_PICKER_TAG = "physical-key-picker"
const val PHYSICAL_KEY_PICKER_CLOSE_TAG = "physical-key-picker-close"
const val PHYSICAL_KEY_CTRL_TAG = "physical-key-ctrl"
const val PHYSICAL_KEY_ALT_TAG = "physical-key-alt"
const val PHYSICAL_KEY_SHIFT_TAG = "physical-key-shift"
const val PHYSICAL_KEY_META_TAG = "physical-key-meta"

/** Tag de teste de uma tecla do picker (keycode do `KeyEvent`, ex.: `physical-key-code-29` para "a"). */
fun physicalKeyTag(keyCode: Int): String = "physical-key-code-$keyCode"

/**
 * Picker de teclado físico compartilhado (CFG-05), como modal próprio: teclado ANSI desenhado em Compose
 * + toggles Ctrl/Alt/Shift/Meta. Tocar uma tecla monta o [ShortcutKey] já com o rótulo certo ("Ctrl+B")
 * via [PhysicalKeyMap.key] e fecha.
 *
 * Quem já está dentro de um [ConfigModal] (o editor de grade) usa [PhysicalKeyPickerContent] direto, sem
 * este wrapper — senão empilha um segundo scrim por cima do primeiro (Q8).
 */
@Composable
fun PhysicalKeyPicker(
    visible: Boolean,
    onSelect: (ShortcutKey) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    ConfigModal(
        title = tr("Escolher tecla física"),
        onDismiss = onDismiss,
        closeTag = PHYSICAL_KEY_PICKER_CLOSE_TAG,
        modifier = modifier,
        cardTag = PHYSICAL_KEY_PICKER_TAG,
    ) {
        PhysicalKeyPickerContent(
            onSelect = { key ->
                onSelect(key)
                onDismiss()
            },
        )
    }
}

/**
 * Corpo do picker sem chrome de modal — toggles + teclado ANSI inteiro, sem rolagem (Q8: cada linha
 * escala pra largura disponível; ver [KeyRow]). Os toggles são estado efêmero: morrem junto com a
 * composição, então reabrir o picker sempre começa sem modificadores.
 */
@Composable
fun PhysicalKeyPickerContent(
    onSelect: (ShortcutKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var meta by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModifierToggle("Ctrl", ctrl, PHYSICAL_KEY_CTRL_TAG) { ctrl = !ctrl }
            ModifierToggle("Alt", alt, PHYSICAL_KEY_ALT_TAG) { alt = !alt }
            ModifierToggle("Shift", shift, PHYSICAL_KEY_SHIFT_TAG) { shift = !shift }
            ModifierToggle("Meta", meta, PHYSICAL_KEY_META_TAG) { meta = !meta }
        }

        Column(Modifier.padding(top = 12.dp)) {
            KEY_ROWS.forEach { row ->
                KeyRow(row) { keyCode -> onSelect(PhysicalKeyMap.key(keyCode, ctrl, alt, shift, meta)) }
            }
        }
    }
}

@Composable
private fun ModifierToggle(label: String, checked: Boolean, tag: String, onToggle: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    val noRipple = remember { MutableInteractionSource() }
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (checked) Modifier.background(palette.fg)
                else Modifier.border(1.dp, palette.line, RoundedCornerShape(6.dp)),
            )
            .clickable(interactionSource = noRipple, indication = null) { onToggle() }
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (checked) palette.onFill else palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
    }
}

/**
 * Uma linha do teclado, escalada pra caber INTEIRA na largura disponível (Q8): a largura da tecla é a
 * largura da linha ÷ nº de teclas **desta** linha, então linhas curtas (zxcvbnm, navegação) ganham teclas
 * maiores que a linha longa do qwerty — pedido do Yan (2026-09-07) no lugar do `horizontalScroll` por
 * linha que existia antes, onde só dava pra ver um pedaço do teclado de cada vez.
 */
@Composable
private fun KeyRow(row: List<Int>, onTap: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        val gap = KEY_GAP_DP.dp
        val keyWidth = (maxWidth - gap * (row.size - 1)) / row.size
        val labels = row.map { PhysicalKeyMap.baseLabel(it) ?: "?" }
        // Uma fonte só pra linha inteira (a do rótulo mais comprido dela): teclas vizinhas com tamanhos
        // de texto diferentes ficavam visualmente sujas.
        val fontSize = fittedFontSizeSp(
            labels = labels,
            maxTextWidth = (keyWidth - KEY_TEXT_PADDING_DP.dp).coerceAtLeast(1.dp),
            maxSp = minOf(KEY_FONT_MAX_SP, keyWidth.value * KEY_FONT_RATIO),
            minSp = KEY_FONT_MIN_SP,
            fontFamily = SpaceGrotesk,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            row.forEachIndexed { i, keyCode -> KeyButton(keyCode, labels[i], keyWidth, fontSize, onTap) }
        }
    }
}

@Composable
private fun KeyButton(keyCode: Int, label: String, width: Dp, fontSize: Float, onTap: (Int) -> Unit) {
    val palette = LocalKeyFlowPalette.current
    val noRipple = remember { MutableInteractionSource() }
    Box(
        Modifier
            .width(width)
            .height(KEY_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(palette.kbKey)
            .border(1.dp, palette.kbKeyBorder, RoundedCornerShape(6.dp))
            .clickable(interactionSource = noRipple, indication = null) { onTap(keyCode) }
            .testTag(physicalKeyTag(keyCode)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontSize = fontSize.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private const val KEY_GAP_DP = 3f
private const val KEY_HEIGHT_DP = 34f
private const val KEY_FONT_RATIO = 0.42f
private const val KEY_TEXT_PADDING_DP = 4f
private const val KEY_FONT_MIN_SP = 7f
private const val KEY_FONT_MAX_SP = 13f

private fun letterKeyCode(c: Char): Int = KeyEvent.KEYCODE_A + (c - 'a')

private val KEY_ROWS: List<List<Int>> = listOf(
    listOf(KeyEvent.KEYCODE_ESCAPE) + (1..12).map { KeyEvent.KEYCODE_F1 + it - 1 },
    (1..9).map { KeyEvent.KEYCODE_1 + it - 1 } +
        listOf(KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_DEL),
    listOf(KeyEvent.KEYCODE_TAB) + "qwertyuiop".map(::letterKeyCode) +
        listOf(KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_BACKSLASH),
    "asdfghjkl".map(::letterKeyCode) +
        listOf(KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_ENTER),
    "zxcvbnm".map(::letterKeyCode) +
        listOf(KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH),
    listOf(
        KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_FORWARD_DEL,
    ),
)
