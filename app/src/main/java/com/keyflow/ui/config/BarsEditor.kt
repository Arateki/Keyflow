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
import com.keyflow.ime.bars.BarIcons
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.GridTriggerMode
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.core.shortcuts.ShortcutKey
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk

const val BARS_EDITOR_TAG = "bars-editor"
const val BARS_EDITOR_MODE_COLLAPSED_TAG = "bars-editor-mode-collapsed"
const val BARS_EDITOR_MODE_EXPANDED_TAG = "bars-editor-mode-expanded"
const val BARS_EDITOR_MIRROR_TOGGLE_TAG = "bars-editor-mirror-toggle"
const val BARS_EDITOR_ADD_BAR_TAG = "bars-editor-add-bar"
const val BARS_EDITOR_COUNT_WARNING_TAG = "bars-editor-count-warning"
const val BARS_EDITOR_SCROLL_WARNING_TAG = "bars-editor-scroll-warning"

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
const val BARS_EDITOR_CATALOG_HELP_TAG = "bars-editor-catalog-help"
const val BARS_EDITOR_CATALOG_HELP_ADD_TAG = "bars-editor-catalog-help-add"
const val BARS_EDITOR_CATALOG_HELP_BACK_TAG = "bars-editor-catalog-help-back"

/** "?" da linha do catálogo cuja linha tem a tag [rowTag]. */
fun catalogHelpTag(rowTag: String) = "$rowTag-help"
const val BARS_EDITOR_EDIT_CLOSE_TAG = "bars-editor-edit-close"
const val BARS_EDITOR_GRID_FORM_CLOSE_TAG = "bars-editor-grid-form-close"

/** Tag do "+" que abre o catálogo para a barra `barIndex`. */
fun barAddButtonTag(barIndex: Int) = "bars-editor-add-button-$barIndex"

/** Tag da lixeira que remove a barra inteira `barIndex`. */
fun barTrashTag(barIndex: Int) = "bars-editor-trash-$barIndex"

/** Tag do segmento de [GridTriggerMode] no formulário de gatilho de grade. */
fun gridModeTag(mode: GridTriggerMode) = "bars-editor-grid-form-mode-${mode.name.lowercase()}"

/** O que cada modo faz, em uma frase curta — aparece sob a opção no formulário de gatilho de grade. */
fun gridModeHint(mode: GridTriggerMode): String = when (mode) {
    GridTriggerMode.OPEN_ONLY -> "Envia só a tecla escolhida na grade."
    GridTriggerMode.SEQUENCE -> "Envia o prefixo e, depois, a tecla (como no tmux)."
    GridTriggerMode.CHORD -> "Envia prefixo e tecla juntos, como Ctrl+X."
}

/** Nome por extenso do botão, para as frases dos avisos (o glifo não se lê em texto corrido). */
private fun barButtonName(button: BarButton): String = when (button) {
    BarButton.Mic -> "microfone"
    BarButton.ConfigBtn -> "config"
    BarButton.ToggleExpand -> "colapsar/expandir"
    BarButton.Paste -> "colar"
    BarButton.Candidates -> "sugestões"
    BarButton.Joystick -> "alavanca"
    is BarButton.SingleKey -> button.key.label
    is BarButton.GridTrigger -> button.label.ifBlank { "grade" }
}

/**
 * Texto do aviso de rolagem, ou `null` quando não há o que avisar (barra curta o bastante para caber
 * sempre). A barra só rola a partir de um botão de TOQUE — ver [BarGeometry.scrollAnchors] —, e quem
 * monta a barra não tem como adivinhar isso olhando os chips (Yan, 2026-09-08).
 */
private fun scrollWarningFor(buttons: List<BarButton>): String? {
    if (buttons.size < SCROLL_WARNING_MIN_BUTTONS) return null
    val apoios = BarGeometry.scrollAnchors(buttons)
    val motivos = "o microfone, a alavanca e a grade usam o arrasto nos próprios gestos, e a faixa de " +
        "sugestões rola as palavras dela"
    return if (apoios.isEmpty()) {
        "Esta barra pode não caber na tela, e nenhum botão dela serve de apoio para rolar: $motivos. " +
            "Se ela estourar a largura, o que ficar de fora não terá como ser alcançado — inclua um " +
            "botão de toque (colar, Esc, Enter ou uma tecla avulsa)."
    } else {
        val nomes = apoios.map(::barButtonName).distinct()
        val lista = when (nomes.size) {
            1 -> nomes.first()
            else -> nomes.dropLast(1).joinToString(", ") + " e " + nomes.last()
        }
        "Esta barra pode não caber na tela. Se isso acontecer, ela rola na horizontal, mas o arrasto só " +
            "pega em $lista — $motivos."
    }
}

/**
 * Rótulo em texto do chip, para os botões que não têm ícone em [BarIcons] (teclas avulsas, a faixa de
 * sugestões). Os demais desenham o mesmo ícone da barra do teclado.
 */
private fun barButtonGlyph(button: BarButton): String = when (button) {
    BarButton.Candidates -> "abc"
    is BarButton.SingleKey -> button.key.label
    else -> barButtonName(button)
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
/** A partir de quantos botões vale avisar sobre rolagem (abaixo disso a barra cabe com folga). */
private const val SCROLL_WARNING_MIN_BUTTONS = 6

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
    // Galeria: já abre no formulário de gatilho de grade, com este gatilho como botão novo da barra 0.
    initialGridForm: BarButton.GridTrigger? = null,
) {
    val palette = LocalKeyFlowPalette.current
    var viewingExpanded by remember { mutableStateOf(false) }
    var sheet by remember {
        mutableStateOf<BarsEditorSheet?>(
            initialGridForm?.let {
                BarsEditorSheet.GridForm(barIndex = 0, buttonIndex = null, it.gridId, it.prefix, it.mode, it.label)
            },
        )
    }
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
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingTitle("Espelhar colapsado/expandido", Modifier.weight(1f))
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
                    Modifier.fillMaxWidth().padding(top = 14.dp),
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
                                expandedMode = viewingExpanded && !barsConfig.mirrored,
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
                        KfIcon(KfIcons.Trash, palette.faint, Modifier.size(18.dp))
                    }
                }

                scrollWarningFor(barDef.buttons)?.let { aviso ->
                    InfoNote(aviso, BARS_EDITOR_SCROLL_WARNING_TAG, Modifier.padding(top = 10.dp))
                }
            }

            if (activeList.size >= 3) {
                InfoNote(
                    "3+ barras + horizontal: o visual e a usabilidade do teclado podem ficar inconsistentes (pouco espaço vertical)",
                    BARS_EDITOR_COUNT_WARNING_TAG,
                    Modifier.padding(top = 12.dp),
                )
            }

            SecondaryButton(
                "barra",
                tag = BARS_EDITOR_ADD_BAR_TAG,
                onClick = { updateActiveList(activeList + BarDef(emptyList())) },
                modifier = Modifier.padding(top = 14.dp),
                icon = KfIcons.Plus,
            )
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
                    // "Só grade" não usa prefixo: não guarda um que ficou escondido no formulário.
                    val prefix = current.prefix.takeIf { current.mode != GridTriggerMode.OPEN_ONLY }
                    val button = BarButton.GridTrigger(gridId, prefix, current.mode, current.label)
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
    expandedMode: Boolean,
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
    val isMic = button == BarButton.Mic
    // Mesmo ícone que a BarView desenha (BarIcons) — a prévia do Modo Teclado é o teclado real.
    val icon = BarIcons.iconFor(button, expandedMode)

    Box(
        Modifier
            .height(36.dp)
            // Largura proporcional ao MESMO peso que a barra real usa (BarGeometry.weightFor): a prévia é
            // WYSIWYG, e a faixa de sugestões vale ~4 botões. Como chip pequeno ela mentia sobre o
            // resultado, e o aperto só aparecia ao usar o teclado (Yan, 2026-09-08).
            // ...como MÍNIMO, não largura fixa: com `width` o rótulo era truncado ("colar" virava "c",
            // "esc" virava "e") — regressão pega pelo golden config-teclado, 2026-09-08. O piso do alvo
            // de toque continua valendo para os pesos baixos.
            .widthIn(min = maxOf(CHIP_MIN_DP, CHIP_UNIT_DP * BarGeometry.weightFor(button)))
            .clip(RoundedCornerShape(10.dp))
            // O mic sai na cor dele, como na barra de verdade — é a âncora da fileira.
            .background(if (isMic) palette.teal else palette.kbKey)
            .border(1.dp, if (isMic) palette.teal else palette.kbKeyBorder, RoundedCornerShape(10.dp))
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
        if (icon != null) {
            KfIcon(icon, tint = if (isMic) MIC_INK else palette.fg, modifier = Modifier.size(20.dp))
        } else {
            Text(
                barButtonGlyph(button),
                color = palette.fg,
                fontFamily = SpaceGrotesk,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
    }
}

/** Tinta do mic sobre o teal — o branco que a BarView usa (`MicVisuals`), igual nos dois temas. */
private val MIC_INK = Color.White

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
        KfIcon(KfIcons.Plus, palette.teal, Modifier.size(20.dp), strokeWidth = 1.9f)
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
    val options = buildList {
        add(CatalogOption("Microfone", BARS_EDITOR_CATALOG_MIC_TAG, "Grava sua voz e cola a transcrição. Toque para gravar e parar; segure para pausar, retomar ou cancelar.") { onAddSimple(BarButton.Mic) })
        add(CatalogOption("Config", BARS_EDITOR_CATALOG_CONFIG_TAG, "Abre as configurações do KeyFlow.") { onAddSimple(BarButton.ConfigBtn) })
        add(CatalogOption("Colapsar/expandir", BARS_EDITOR_CATALOG_TOGGLE_EXPAND_TAG, "Recolhe o teclado até a barra, ou traz as teclas de volta.") { onAddSimple(BarButton.ToggleExpand) })
        add(CatalogOption("Colar", BARS_EDITOR_CATALOG_PASTE_TAG, "Cola o que está na área de transferência.") { onAddSimple(BarButton.Paste) })
        add(CatalogOption("Candidatos", BARS_EDITOR_CATALOG_CANDIDATES_TAG, "A faixa de sugestões do corretor: enquanto você digita, mostra palavras para tocar e completar. A área fica reservada mesmo vazia.") { onAddSimple(BarButton.Candidates) })
        add(CatalogOption("Alavanca", BARS_EDITOR_CATALOG_JOYSTICK_TAG, "Arraste a partir dela para mover o cursor, como as setas do teclado; quanto mais longe o dedo, mais rápido.") { onAddSimple(BarButton.Joystick) })
        add(CatalogOption("Tecla avulsa", BARS_EDITOR_CATALOG_SINGLE_KEY_TAG, "Um botão que envia a tecla ou o atalho que você escolher, como Esc, Tab ou Ctrl+C.") { onAddSingleKey() })
        // Grade: escondida quando não há grades cadastradas (nada pra referenciar).
        if (grids.isNotEmpty()) {
            add(CatalogOption("Grade", BARS_EDITOR_CATALOG_GRID_TAG, "Segure e arraste para abrir uma grade de atalhos; solte sobre uma célula para enviar o atalho dela.") { onAddGrid() })
        }
    }
    // "?" de uma linha: o mesmo modal troca para a explicação (seta ← volta ao catálogo) — padrão do
    // ConfigModal para não empilhar um segundo overlay (Yan, 2026-09-28).
    var helpFor by remember { mutableStateOf<String?>(null) }
    val help = options.firstOrNull { it.tag == helpFor }
    if (help != null) {
        ConfigModal(
            title = help.label,
            onDismiss = onDismiss,
            closeTag = BARS_EDITOR_CATALOG_CLOSE_TAG,
            cardTag = BARS_EDITOR_CATALOG_HELP_TAG,
            onBack = { helpFor = null },
            backTag = BARS_EDITOR_CATALOG_HELP_BACK_TAG,
        ) {
            // O texto é o assunto do modal: um degrau acima da legenda (SettingSub) para ler com folga.
            Text(
                help.help,
                color = LocalKeyFlowPalette.current.fg2,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Light,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            PrimaryButton(
                "Adicionar",
                tag = BARS_EDITOR_CATALOG_HELP_ADD_TAG,
                onClick = help.onAdd,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
        return
    }
    ConfigModal(
        title = "Adicionar botão",
        onDismiss = onDismiss,
        closeTag = BARS_EDITOR_CATALOG_CLOSE_TAG,
        cardTag = BARS_EDITOR_CATALOG_TAG,
    ) {
        options.forEach { option ->
            SheetActionRow(option.label, option.tag, onHelp = { helpFor = option.tag }, onClick = option.onAdd)
        }
    }
}

/** Uma linha do catálogo "Adicionar botão": o nome, o que ela faz (o "?") e como adiciona. */
private class CatalogOption(val label: String, val tag: String, val help: String, val onAdd: () -> Unit)

@Composable
private fun SimpleEditSheet(
    button: BarButton,
    onRemove: () -> Unit,
    onChangeKey: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfigModal(
        title = barButtonName(button).replaceFirstChar { it.uppercase() },
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
                hint = ::gridModeHint,
            )

            // O prefixo só existe para Sequência e Chord; em "Só grade" ele não entra em nada, então some
            // (fica abaixo das opções para elas não pularem quando ele aparece).
            if (form.mode != GridTriggerMode.OPEN_ONLY) {
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
            }

            PrimaryButton(
                "Confirmar",
                tag = BARS_EDITOR_GRID_FORM_CONFIRM_TAG,
                onClick = onConfirm,
                modifier = Modifier.padding(top = 16.dp),
            )

            if (onRemove != null) {
                SheetActionRow("Remover", BARS_EDITOR_GRID_FORM_REMOVE_TAG, onClick = onRemove)
            }
    }
}

/** Linha de ação genérica dos sheets deste arquivo (catálogo/edição/formulário de grade). */
@Composable
private fun SheetActionRow(label: String, tag: String, onHelp: (() -> Unit)? = null, onClick: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    val noRipple = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, palette.line, RoundedCornerShape(14.dp))
            .clickable(interactionSource = noRipple, indication = null) { onClick() }
            .testTag(tag)
            .padding(start = 16.dp, end = 12.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (onHelp != null) {
            // Alvo próprio: tocar no "?" explica; o resto da linha adiciona.
            HelpButton("O que é $label", catalogHelpTag(tag), onHelp, Modifier.padding(end = 6.dp))
        }
        KfIcon(KfIcons.ChevronRight, palette.faint, Modifier.size(16.dp))
    }
}
