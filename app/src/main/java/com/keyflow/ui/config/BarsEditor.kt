package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.ime.bars.BarGeometry
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.GridTriggerMode
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.core.shortcuts.ShortcutKey
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk

const val BARS_EDITOR_TAG = "bars-editor"
const val BARS_EDITOR_MODE_COLLAPSED_TAG = "bars-editor-mode-collapsed"
const val BARS_EDITOR_MODE_EXPANDED_TAG = "bars-editor-mode-expanded"
const val BARS_EDITOR_MIRROR_TOGGLE_TAG = "bars-editor-mirror-toggle"
const val BARS_EDITOR_ADD_BAR_TAG = "bars-editor-add-bar"
const val BARS_EDITOR_COUNT_WARNING_TAG = "bars-editor-count-warning"

const val BARS_EDITOR_CATALOG_TAG = "bars-editor-catalog"
const val BARS_EDITOR_CATALOG_MIC_TAG = "bars-editor-catalog-mic"
const val BARS_EDITOR_CATALOG_CONFIG_TAG = "bars-editor-catalog-config"
const val BARS_EDITOR_CATALOG_TOGGLE_EXPAND_TAG = "bars-editor-catalog-toggle-expand"
const val BARS_EDITOR_CATALOG_PASTE_TAG = "bars-editor-catalog-paste"
const val BARS_EDITOR_CATALOG_CANDIDATES_TAG = "bars-editor-catalog-candidates"
const val BARS_EDITOR_CATALOG_JOYSTICK_TAG = "bars-editor-catalog-joystick"
const val BARS_EDITOR_CATALOG_SINGLE_KEY_TAG = "bars-editor-catalog-single-key"
const val BARS_EDITOR_CATALOG_GRID_TAG = "bars-editor-catalog-grid"

const val BARS_EDITOR_EDIT_SHEET_TAG = "bars-editor-edit-sheet"
const val BARS_EDITOR_EDIT_REMOVE_TAG = "bars-editor-edit-remove"
const val BARS_EDITOR_EDIT_CHANGE_KEY_TAG = "bars-editor-edit-change-key"

const val BARS_EDITOR_GRID_FORM_TAG = "bars-editor-grid-form"
const val BARS_EDITOR_GRID_FORM_DROPDOWN_TAG = "bars-editor-grid-form-dropdown"
const val BARS_EDITOR_GRID_FORM_PREFIX_SET_TAG = "bars-editor-grid-form-prefix-set"
const val BARS_EDITOR_GRID_FORM_PREFIX_REMOVE_TAG = "bars-editor-grid-form-prefix-remove"
const val BARS_EDITOR_GRID_FORM_CONFIRM_TAG = "bars-editor-grid-form-confirm"
const val BARS_EDITOR_GRID_FORM_REMOVE_TAG = "bars-editor-grid-form-remove"

/** Tag de teste de um chip de botão na prévia (linha `barIndex`, posição `buttonIndex`). */
fun barChipTag(barIndex: Int, buttonIndex: Int) = "bars-editor-chip-$barIndex-$buttonIndex"

const val BARS_EDITOR_CATALOG_CLOSE_TAG = "bars-editor-catalog-close"
const val BARS_EDITOR_EDIT_CLOSE_TAG = "bars-editor-edit-close"
const val BARS_EDITOR_GRID_FORM_CLOSE_TAG = "bars-editor-grid-form-close"

/** Tag do "+" que abre o catálogo para a barra `barIndex`. */
fun barAddButtonTag(barIndex: Int) = "bars-editor-add-button-$barIndex"

/** Tag da lixeira que remove a barra inteira `barIndex`. */
fun barTrashTag(barIndex: Int) = "bars-editor-trash-$barIndex"

/** Tag do segmento de [GridTriggerMode] no formulário de gatilho de grade. */
fun gridModeTag(mode: GridTriggerMode) = "bars-editor-grid-form-mode-${mode.name.lowercase()}"

/** Rótulo/glifo curto de cada botão de barra na prévia WYSIWYG (CFG-02). */
private fun barButtonGlyph(button: BarButton): String = when (button) {
    BarButton.Mic -> "🎙"
    BarButton.ConfigBtn -> "⚙"
    BarButton.ToggleExpand -> "⌃"
    BarButton.Paste -> "colar"
    BarButton.Candidates -> "abc"
    BarButton.Joystick -> "🕹"
    is BarButton.SingleKey -> button.key.label
    is BarButton.GridTrigger -> button.label
}

/**
 * Reordena [buttons] movendo o item de [fromIndex] para [toIndex]. Extraída como função pura (fora do
 * gesto de ponteiro) para ser testável direto — o drag-to-reorder da UI só chama isto a cada limiar
 * cruzado; não há necessidade de reimplementar a lógica de swap dentro do `pointerInput`.
 */
fun reorderedButtons(buttons: List<BarButton>, fromIndex: Int, toIndex: Int): List<BarButton> {
    if (fromIndex == toIndex || fromIndex !in buttons.indices || toIndex !in buttons.indices) return buttons
    val mutable = buttons.toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex, item)
    return mutable
}

// Distância de arrasto (após o long-press) que precisa ser cruzada para trocar com o vizinho — valor
// fixo simples (aprox. metade da largura mínima de um chip), sem medir a largura real do vizinho.
/** Largura de UMA unidade de peso na prévia (o mic, peso 1,5, sai com 36dp). */
private val CHIP_UNIT_DP = 24.dp

/** Piso de largura do chip (alvo de toque), independente do peso. */
private val CHIP_MIN_DP = 36.dp

private val REORDER_THRESHOLD_DP = 24.dp

private sealed interface BarsEditorSheet {
    data class Catalog(val barIndex: Int) : BarsEditorSheet
    data class SimpleEdit(val barIndex: Int, val buttonIndex: Int, val button: BarButton) : BarsEditorSheet
    data class GridForm(
        val barIndex: Int,
        val buttonIndex: Int?,
        val gridId: String?,
        val prefix: ShortcutKey?,
        val mode: GridTriggerMode,
        val label: String,
    ) : BarsEditorSheet
}

private sealed interface KeyPickerPurpose {
    data class AddSingleKey(val barIndex: Int) : KeyPickerPurpose
    data class ReplaceSingleKey(val barIndex: Int, val buttonIndex: Int) : KeyPickerPurpose
    data object GridPrefix : KeyPickerPurpose
}

/**
 * Editor WYSIWYG das barras de atalhos (CFG-02/03/06): prévia fiel por modo (retraído/expandido, ou
 * única lista se espelhado), tap edita/remove cada botão, "+" abre o catálogo, arrasto reordena,
 * "+ barra"/lixeira gerenciam barras inteiras. Sem estado de persistência — tudo flui por
 * [onBarsConfigChange]; quem chama decide como salvar.
 */
@Composable
fun BarsEditor(
    barsConfig: BarsConfig,
    onBarsConfigChange: (BarsConfig) -> Unit,
    grids: List<ShortcutGrid>,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    var viewingExpanded by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<BarsEditorSheet?>(null) }
    var keyPickerPurpose by remember { mutableStateOf<KeyPickerPurpose?>(null) }

    val activeList = when {
        barsConfig.mirrored -> barsConfig.collapsed
        viewingExpanded -> barsConfig.expanded
        else -> barsConfig.collapsed
    }

    fun updateActiveList(newList: List<BarDef>) {
        onBarsConfigChange(
            when {
                barsConfig.mirrored -> barsConfig.copy(collapsed = newList, expanded = newList)
                viewingExpanded -> barsConfig.copy(expanded = newList)
                else -> barsConfig.copy(collapsed = newList)
            },
        )
    }

    fun updateBarButtons(barIndex: Int, buttons: List<BarButton>) {
        updateActiveList(
            activeList.toMutableList().also { it[barIndex] = it[barIndex].copy(buttons = buttons) },
        )
    }

    fun addButton(barIndex: Int, button: BarButton) {
        updateBarButtons(barIndex, activeList[barIndex].buttons + button)
    }

    fun removeButtonAt(barIndex: Int, buttonIndex: Int) {
        updateBarButtons(barIndex, activeList[barIndex].buttons.toMutableList().also { it.removeAt(buttonIndex) })
    }

    fun replaceButtonAt(barIndex: Int, buttonIndex: Int, button: BarButton) {
        updateBarButtons(barIndex, activeList[barIndex].buttons.toMutableList().also { it[buttonIndex] = button })
    }

    Box(modifier.fillMaxWidth().testTag(BARS_EDITOR_TAG)) {
        Column(Modifier.fillMaxWidth()) {
            if (!barsConfig.mirrored) {
                Segmented(
                    options = listOf(false, true),
                    selected = viewingExpanded,
                    label = { if (it) "Expandido" else "Retraído" },
                    tag = { if (it) BARS_EDITOR_MODE_EXPANDED_TAG else BARS_EDITOR_MODE_COLLAPSED_TAG },
                    onSelect = { viewingExpanded = it },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Espelhar colapsado/expandido",
                    color = palette.fg,
                    fontFamily = SpaceGrotesk,
                    fontSize = 13.sp,
                )
                PillToggle(
                    checked = barsConfig.mirrored,
                    onCheckedChange = { turnOn ->
                        onBarsConfigChange(
                            if (turnOn) {
                                val copied = activeList
                                barsConfig.copy(mirrored = true, collapsed = copied, expanded = copied)
                            } else {
                                barsConfig.copy(mirrored = false)
                            },
                        )
                    },
                    tag = BARS_EDITOR_MIRROR_TOGGLE_TAG,
                )
            }

            activeList.forEachIndexed { barIndex, barDef ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        barDef.buttons.forEachIndexed { buttonIndex, button ->
                            BarButtonChip(
                                button = button,
                                barIndex = barIndex,
                                buttonIndex = buttonIndex,
                                totalButtons = barDef.buttons.size,
                                onClick = {
                                    sheet = if (button is BarButton.GridTrigger) {
                                        BarsEditorSheet.GridForm(
                                            barIndex = barIndex,
                                            buttonIndex = buttonIndex,
                                            gridId = button.gridId,
                                            prefix = button.prefix,
                                            mode = button.mode,
                                            label = button.label,
                                        )
                                    } else {
                                        BarsEditorSheet.SimpleEdit(barIndex, buttonIndex, button)
                                    }
                                },
                                onReorder = { from, to ->
                                    updateBarButtons(barIndex, reorderedButtons(activeList[barIndex].buttons, from, to))
                                },
                            )
                        }
                    }
                    ControlsDivider()
                    AddChip(barIndex = barIndex, onClick = { sheet = BarsEditorSheet.Catalog(barIndex) })
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                updateActiveList(activeList.toMutableList().also { it.removeAt(barIndex) })
                            }
                            .testTag(barTrashTag(barIndex)),
                        contentAlignment = Alignment.Center,
                    ) {
                        StrokeIcon(TRASH_PATHS, palette.faint, Modifier.size(16.dp), strokeWidth = 1.6f)
                    }
                }
            }

            if (activeList.size >= 3) {
                Text(
                    "3+ barras + horizontal: o visual e a usabilidade do teclado podem ficar inconsistentes (pouco espaço vertical)",
                    color = palette.muted,
                    fontFamily = SpaceGrotesk,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, palette.line, RoundedCornerShape(6.dp))
                        .testTag(BARS_EDITOR_COUNT_WARNING_TAG)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, palette.line, RoundedCornerShape(6.dp))
                    .clickable { updateActiveList(activeList + BarDef(emptyList())) }
                    .testTag(BARS_EDITOR_ADD_BAR_TAG)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    "+ barra",
                    color = palette.fg,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
        }

        when (val current = sheet) {
            is BarsEditorSheet.Catalog -> CatalogSheet(
                grids = grids,
                onDismiss = { sheet = null },
                onAddSimple = { button ->
                    addButton(current.barIndex, button)
                    sheet = null
                },
                onAddSingleKey = {
                    keyPickerPurpose = KeyPickerPurpose.AddSingleKey(current.barIndex)
                    sheet = null
                },
                onAddGrid = {
                    val firstGridId = grids.firstOrNull()?.id
                    sheet = BarsEditorSheet.GridForm(
                        barIndex = current.barIndex,
                        buttonIndex = null,
                        gridId = firstGridId,
                        prefix = null,
                        mode = GridTriggerMode.OPEN_ONLY,
                        label = "⌗",
                    )
                },
            )
            is BarsEditorSheet.SimpleEdit -> SimpleEditSheet(
                button = current.button,
                onRemove = {
                    removeButtonAt(current.barIndex, current.buttonIndex)
                    sheet = null
                },
                onChangeKey = {
                    keyPickerPurpose = KeyPickerPurpose.ReplaceSingleKey(current.barIndex, current.buttonIndex)
                },
                onDismiss = { sheet = null },
            )
            is BarsEditorSheet.GridForm -> GridFormSheet(
                form = current,
                grids = grids,
                onGridSelect = { id -> sheet = current.copy(gridId = id) },
                onModeSelect = { mode -> sheet = current.copy(mode = mode) },
                onSetPrefix = { keyPickerPurpose = KeyPickerPurpose.GridPrefix },
                onRemovePrefix = { sheet = current.copy(prefix = null) },
                onConfirm = confirm@{
                    val gridId = current.gridId ?: return@confirm
                    val button = BarButton.GridTrigger(gridId, current.prefix, current.mode, current.label)
                    if (current.buttonIndex != null) {
                        replaceButtonAt(current.barIndex, current.buttonIndex, button)
                    } else {
                        addButton(current.barIndex, button)
                    }
                    sheet = null
                },
                onRemove = current.buttonIndex?.let { buttonIndex ->
                    {
                        removeButtonAt(current.barIndex, buttonIndex)
                        sheet = null
                    }
                },
                onDismiss = { sheet = null },
            )
            null -> {}
        }

        PhysicalKeyPicker(
            visible = keyPickerPurpose != null,
            onSelect = { key ->
                when (val purpose = keyPickerPurpose) {
                    is KeyPickerPurpose.AddSingleKey -> addButton(purpose.barIndex, BarButton.SingleKey(key))
                    is KeyPickerPurpose.ReplaceSingleKey -> {
                        replaceButtonAt(purpose.barIndex, purpose.buttonIndex, BarButton.SingleKey(key))
                        sheet = null
                    }
                    is KeyPickerPurpose.GridPrefix -> {
                        val currentSheet = sheet
                        if (currentSheet is BarsEditorSheet.GridForm) sheet = currentSheet.copy(prefix = key)
                    }
                    null -> {}
                }
                keyPickerPurpose = null
            },
            onDismiss = { keyPickerPurpose = null },
        )
    }
}

@Composable
private fun BarButtonChip(
    button: BarButton,
    barIndex: Int,
    buttonIndex: Int,
    totalButtons: Int,
    onClick: () -> Unit,
    onReorder: (from: Int, to: Int) -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    val noRipple = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    var dragOffsetX by remember { mutableStateOf(0f) }
    val thresholdPx = with(density) { REORDER_THRESHOLD_DP.toPx() }

    Box(
        Modifier
            .height(36.dp)
            .widthIn(min = 36.dp)
            // Largura proporcional ao MESMO peso que a barra real usa (BarGeometry.weightFor): a prévia é
            // WYSIWYG, e a faixa de sugestões vale ~4 botões. Como chip pequeno ela mentia sobre o
            // resultado, e o aperto só aparecia ao usar o teclado (Yan, 2026-09-08).
            // ...com piso no alvo de toque: os pesos baixos (tecla avulsa, 0,8) ficariam pequenos demais.
            .width(maxOf(CHIP_MIN_DP, CHIP_UNIT_DP * BarGeometry.weightFor(button)))
            .clip(RoundedCornerShape(8.dp))
            .background(palette.kbKey)
            .border(1.dp, palette.kbKeyBorder, RoundedCornerShape(8.dp))
            .pointerInput(barIndex, buttonIndex, totalButtons) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragOffsetX = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffsetX += dragAmount.x
                        if (dragOffsetX > thresholdPx && buttonIndex < totalButtons - 1) {
                            onReorder(buttonIndex, buttonIndex + 1)
                            dragOffsetX = 0f
                        } else if (dragOffsetX < -thresholdPx && buttonIndex > 0) {
                            onReorder(buttonIndex, buttonIndex - 1)
                            dragOffsetX = 0f
                        }
                    },
                    onDragEnd = { dragOffsetX = 0f },
                    onDragCancel = { dragOffsetX = 0f },
                )
            }
            .clickable(interactionSource = noRipple, indication = null) { onClick() }
            .testTag(barChipTag(barIndex, buttonIndex))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            barButtonGlyph(button),
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

/**
 * Separa os chips da barra (o que o usuário está montando) dos controles do editor (adicionar, remover)
 * — sem essa divisa o "+" lia como se fosse mais um botão da própria barra.
 */
@Composable
private fun ControlsDivider() {
    val palette = LocalKeyFlowPalette.current
    Box(
        Modifier
            .padding(horizontal = 8.dp)
            .width(1.dp)
            .height(24.dp)
            .background(palette.line2),
    )
}

/** Controle "adicionar botão" — fora da faixa rolável e em teal, como os demais "+" da tela. */
@Composable
private fun AddChip(barIndex: Int, onClick: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .testTag(barAddButtonTag(barIndex)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "+",
            color = palette.teal,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
        )
    }
}

@Composable
private fun CatalogSheet(
    grids: List<ShortcutGrid>,
    onDismiss: () -> Unit,
    onAddSimple: (BarButton) -> Unit,
    onAddSingleKey: () -> Unit,
    onAddGrid: () -> Unit,
) {
    ConfigModal(
        title = "Adicionar botão",
        onDismiss = onDismiss,
        closeTag = BARS_EDITOR_CATALOG_CLOSE_TAG,
        cardTag = BARS_EDITOR_CATALOG_TAG,
    ) {
            SheetActionRow("Microfone", BARS_EDITOR_CATALOG_MIC_TAG) { onAddSimple(BarButton.Mic) }
            SheetActionRow("Config", BARS_EDITOR_CATALOG_CONFIG_TAG) { onAddSimple(BarButton.ConfigBtn) }
            SheetActionRow("Colapsar/expandir", BARS_EDITOR_CATALOG_TOGGLE_EXPAND_TAG) { onAddSimple(BarButton.ToggleExpand) }
            SheetActionRow("Colar", BARS_EDITOR_CATALOG_PASTE_TAG) { onAddSimple(BarButton.Paste) }
            SheetActionRow("Candidatos", BARS_EDITOR_CATALOG_CANDIDATES_TAG) { onAddSimple(BarButton.Candidates) }
            SheetActionRow("Alavanca", BARS_EDITOR_CATALOG_JOYSTICK_TAG) { onAddSimple(BarButton.Joystick) }
            SheetActionRow("Tecla avulsa", BARS_EDITOR_CATALOG_SINGLE_KEY_TAG) { onAddSingleKey() }
            // Grade: escondida quando não há grades cadastradas (nada pra referenciar).
            if (grids.isNotEmpty()) {
                SheetActionRow("Grade", BARS_EDITOR_CATALOG_GRID_TAG) { onAddGrid() }
            }
    }
}

@Composable
private fun SimpleEditSheet(
    button: BarButton,
    onRemove: () -> Unit,
    onChangeKey: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfigModal(
        title = barButtonGlyph(button),
        onDismiss = onDismiss,
        closeTag = BARS_EDITOR_EDIT_CLOSE_TAG,
        cardTag = BARS_EDITOR_EDIT_SHEET_TAG,
    ) {
            if (button is BarButton.SingleKey) {
                SheetActionRow("Trocar tecla", BARS_EDITOR_EDIT_CHANGE_KEY_TAG) { onChangeKey() }
            }
            SheetActionRow("Remover", BARS_EDITOR_EDIT_REMOVE_TAG) { onRemove() }
    }
}

@Composable
private fun GridFormSheet(
    form: BarsEditorSheet.GridForm,
    grids: List<ShortcutGrid>,
    onGridSelect: (String) -> Unit,
    onModeSelect: (GridTriggerMode) -> Unit,
    onSetPrefix: () -> Unit,
    onRemovePrefix: () -> Unit,
    onConfirm: () -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    ConfigModal(
        title = "Gatilho de grade",
        onDismiss = onDismiss,
        closeTag = BARS_EDITOR_GRID_FORM_CLOSE_TAG,
        cardTag = BARS_EDITOR_GRID_FORM_TAG,
    ) {

            if (grids.isNotEmpty()) {
                Dropdown(
                    label = "Grade alvo",
                    options = grids.map { it.id },
                    selected = form.gridId ?: grids.first().id,
                    onSelect = onGridSelect,
                    tag = BARS_EDITOR_GRID_FORM_DROPDOWN_TAG,
                    modifier = Modifier.padding(top = 10.dp),
                    display = { id -> grids.find { it.id == id }?.name ?: id },
                )
            }

            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(
                    if (form.prefix != null) "Prefixo: ${form.prefix.label}" else "Sem prefixo",
                    color = palette.muted,
                    fontFamily = SpaceGrotesk,
                    fontSize = 12.sp,
                )
                if (form.prefix != null) {
                    SheetActionRow("Remover prefixo", BARS_EDITOR_GRID_FORM_PREFIX_REMOVE_TAG) { onRemovePrefix() }
                } else {
                    SheetActionRow("Definir prefixo", BARS_EDITOR_GRID_FORM_PREFIX_SET_TAG) { onSetPrefix() }
                }
            }

            Segmented(
                options = listOf(GridTriggerMode.OPEN_ONLY, GridTriggerMode.SEQUENCE, GridTriggerMode.CHORD),
                selected = form.mode,
                label = { mode ->
                    when (mode) {
                        GridTriggerMode.OPEN_ONLY -> "Só grade"
                        GridTriggerMode.SEQUENCE -> "Sequência"
                        GridTriggerMode.CHORD -> "Chord"
                    }
                },
                tag = ::gridModeTag,
                onSelect = onModeSelect,
                modifier = Modifier.padding(top = 12.dp),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.fg)
                    .clickable { onConfirm() }
                    .testTag(BARS_EDITOR_GRID_FORM_CONFIRM_TAG)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Confirmar",
                    color = palette.onFill,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            }

            if (onRemove != null) {
                SheetActionRow("Remover", BARS_EDITOR_GRID_FORM_REMOVE_TAG, onRemove)
            }
    }
}

/** Linha de ação genérica dos sheets deste arquivo (catálogo/edição/formulário de grade). */
@Composable
private fun SheetActionRow(label: String, tag: String, onClick: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    val noRipple = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, palette.line, RoundedCornerShape(6.dp))
            .clickable(interactionSource = noRipple, indication = null) { onClick() }
            .testTag(tag)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

private val TRASH_PATHS = listOf(
    "M4 7h16",
    "M9 7V4h6v3",
    "M6 7l1 14h10l1-14",
    "M10 11v6",
    "M14 11v6",
)
