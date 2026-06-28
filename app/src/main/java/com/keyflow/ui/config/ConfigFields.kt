package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceMono

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
