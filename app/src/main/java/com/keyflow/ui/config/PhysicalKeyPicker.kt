package com.keyflow.ui.config

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
 * Picker de teclado físico compartilhado (CFG-05): teclado ANSI desenhado em Compose (linhas com
 * scroll horizontal — Esc/F1..F12, números, QWERTY, nav/setas) + toggles Ctrl/Alt/Shift/Meta. Tocar
 * uma tecla monta o [ShortcutKey] já com o rótulo certo ("Ctrl+B") via [PhysicalKeyMap.key] e fecha.
 * Os toggles são estado efêmero desta picker — resetam a cada abertura (`visible` vira `true`).
 */
@Composable
fun PhysicalKeyPicker(
    visible: Boolean,
    onSelect: (ShortcutKey) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val palette = LocalKeyFlowPalette.current
    val noRipple = remember { MutableInteractionSource() }
    var ctrl by remember(visible) { mutableStateOf(false) }
    var alt by remember(visible) { mutableStateOf(false) }
    var shift by remember(visible) { mutableStateOf(false) }
    var meta by remember(visible) { mutableStateOf(false) }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0x751C1813))
            .clickable(interactionSource = noRipple, indication = null) { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(palette.bg)
                .clickable(interactionSource = noRipple, indication = null) { /* consome o clique */ }
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .testTag(PHYSICAL_KEY_PICKER_TAG),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Escolher tecla física",
                    color = palette.fg,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Box(
                    Modifier
                        .size(26.dp)
                        .clickable(interactionSource = noRipple, indication = null) { onDismiss() }
                        .testTag(PHYSICAL_KEY_PICKER_CLOSE_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    StrokeIcon(listOf(CLOSE_PATH), palette.faint, Modifier.size(16.dp), strokeWidth = 1.8f)
                }
            }

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
                    KeyRow(row) { keyCode ->
                        onSelect(PhysicalKeyMap.key(keyCode, ctrl, alt, shift, meta))
                        onDismiss()
                    }
                }
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

@Composable
private fun KeyRow(row: List<Int>, onTap: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        row.forEach { keyCode -> KeyButton(keyCode, onTap) }
    }
}

@Composable
private fun KeyButton(keyCode: Int, onTap: (Int) -> Unit) {
    val palette = LocalKeyFlowPalette.current
    val noRipple = remember { MutableInteractionSource() }
    Box(
        Modifier
            .height(40.dp)
            .widthIn(min = 40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(palette.kbKey)
            .border(1.dp, palette.kbKeyBorder, RoundedCornerShape(6.dp))
            .clickable(interactionSource = noRipple, indication = null) { onTap(keyCode) }
            .testTag(physicalKeyTag(keyCode))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            PhysicalKeyMap.baseLabel(keyCode) ?: "?",
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

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

private const val CLOSE_PATH = "M18 6 6 18M6 6l12 12"
