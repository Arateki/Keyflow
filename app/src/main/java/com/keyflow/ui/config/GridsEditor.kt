package com.keyflow.ui.config

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.shortcuts.GridCell
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

const val GRIDS_EDITOR_TAG = "grids-editor"
const val GRIDS_EDITOR_ADD_TAG = "grids-editor-add"
const val GRIDS_EDITOR_SHEET_TAG = "grids-editor-sheet"
const val GRIDS_EDITOR_SHEET_CLOSE_TAG = "grids-editor-sheet-close"
const val GRIDS_EDITOR_NAME_FIELD_TAG = "grids-editor-name-field"
const val GRIDS_EDITOR_ROWS_STEPPER_TAG = "grids-editor-rows-stepper"
const val GRIDS_EDITOR_COLS_STEPPER_TAG = "grids-editor-cols-stepper"
const val GRIDS_EDITOR_CELL_MENU_TAG = "grids-editor-cell-menu"
const val GRIDS_EDITOR_CELL_CLEAR_TAG = "grids-editor-cell-clear"
const val GRIDS_EDITOR_CELL_LABEL_TAG = "grids-editor-cell-label"

/** Volta do picker de tecla pro editor da grade, dentro do MESMO modal (Q8). */
const val GRIDS_EDITOR_PICKER_BACK_TAG = "grids-editor-picker-back"

/**
 * Teto de linhas/colunas de uma grade (pedido do Yan, 2026-07-06): uma grade 6×10 já configurada por ele
 * fez o espaçador de overscan da janela do IME (`KeyFlowKeyboardView.gridOverscanPx`) passar da altura da
 * tela em paisagem, sumindo o teclado inteiro. 9×9 é o maior tamanho que o encolhimento automático de
 * célula (`BarView`) ainda garante caber mesmo na orientação mais curta.
 */
const val GRID_MAX_DIMENSION = 9
const val GRIDS_EDITOR_CELL_LABEL_FIELD_TAG = "grids-editor-cell-label-field"

/** Tag da linha de uma grade na lista. */
fun gridRowTag(id: String): String = "grids-editor-row-$id"

/** Tag do ícone de excluir de uma grade (1º toque; 2º toque em [gridDeleteConfirmTag] se referenciada). */
fun gridDeleteTag(id: String): String = "grids-editor-delete-$id"

/** Tag da confirmação de exclusão (aparece só quando a grade está referenciada por um `GridTrigger`). */
fun gridDeleteConfirmTag(id: String): String = "grids-editor-delete-confirm-$id"

/** Tag do "cancelar" da confirmação de exclusão. */
fun gridDeleteCancelTag(id: String): String = "grids-editor-delete-cancel-$id"

/** Tag de uma célula da grade em edição. */
fun gridCellTag(row: Int, col: Int): String = "grids-editor-cell-$row-$col"

/**
 * Editor de grades de atalhos (CFG-04): lista de [ShortcutGrid] existentes ("+ grade" adiciona uma nova
 * 3×3), tocar numa grade abre um [ConfigModal] com nome, dimensões (linhas/colunas via [Stepper]) e a
 * grade de células — tocar numa célula (vazia ou preenchida) troca o conteúdo do MESMO modal pelo picker
 * de tecla ([PhysicalKeyPickerContent], com `←` pra voltar); manter pressionado numa célula preenchida
 * abre um menu inline (Limpar / Rótulo custom).
 *
 * Um modal só (Q8, pedido do Yan 2026-09-07): antes o picker era um segundo overlay full-screen empilhado
 * por cima do sheet de edição — dois scrims, dois cards.
 *
 * Excluir uma grade referenciada por um `GridTrigger` (ver [referencedGridIds]) exige um segundo toque de
 * confirmação — o binding órfão em si é tratado alhures (`BarView`); aqui só sinalizamos o aviso.
 *
 * Sem estado de persistência: quem chama decide onde `grids`/`onGridsChange` são guardados.
 */
@Composable
fun GridsEditor(
    grids: List<ShortcutGrid>,
    onGridsChange: (List<ShortcutGrid>) -> Unit,
    referencedGridIds: Set<String>,
    modifier: Modifier = Modifier,
    // Abre já com o modal de uma grade — só pra galeria/goldens conseguirem capturar o modal sem dedo
    // (mesmo padrão de `ConfigScreen.initialTab`/`initialUnlockOpen`). Em produção fica null.
    initialEditingGridId: String? = null,
) {
    var editingId by remember { mutableStateOf(initialEditingGridId) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var cellMenu by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var labelEditing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pickerCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun updateGrid(updated: ShortcutGrid) {
        onGridsChange(grids.map { if (it.id == updated.id) updated else it })
    }

    fun closeSheet() {
        editingId = null
        cellMenu = null
        labelEditing = null
        pickerCell = null
    }

    Box(modifier.testTag(GRIDS_EDITOR_TAG)) {
        Column(Modifier.fillMaxWidth()) {
            grids.forEach { grid ->
                GridListRow(
                    grid = grid,
                    pendingDelete = pendingDeleteId == grid.id,
                    onTapRow = { editingId = grid.id },
                    onTapDelete = {
                        if (grid.id in referencedGridIds) {
                            pendingDeleteId = grid.id
                        } else {
                            onGridsChange(grids.filterNot { it.id == grid.id })
                        }
                    },
                    onConfirmDelete = {
                        onGridsChange(grids.filterNot { it.id == grid.id })
                        pendingDeleteId = null
                    },
                    onCancelDelete = { pendingDeleteId = null },
                )
            }
            AddGridRow {
                val newGrid = ShortcutGrid(
                    id = "grid-${System.currentTimeMillis()}-${grids.size}",
                    name = "Grade ${grids.size + 1}",
                    rows = 3,
                    cols = 3,
                )
                onGridsChange(grids + newGrid)
            }
        }

        val editing = grids.find { it.id == editingId }
        if (editing != null) {
            val picking = pickerCell
            ConfigModal(
                title = if (picking != null) "Escolher tecla física" else "Editar grade",
                onDismiss = { closeSheet() },
                closeTag = if (picking != null) PHYSICAL_KEY_PICKER_CLOSE_TAG else GRIDS_EDITOR_SHEET_CLOSE_TAG,
                cardTag = if (picking != null) PHYSICAL_KEY_PICKER_TAG else GRIDS_EDITOR_SHEET_TAG,
                onBack = if (picking != null) ({ pickerCell = null }) else null,
                backTag = GRIDS_EDITOR_PICKER_BACK_TAG,
            ) {
                if (picking != null) {
                    PhysicalKeyPickerContent(
                        onSelect = { key ->
                            val (row, col) = picking
                            val idx = row * editing.cols + col
                            updateGrid(editing.copy(cells = editing.cells + (idx to GridCell(key))))
                            pickerCell = null
                        },
                    )
                } else {
                    GridEditContent(
                        grid = editing,
                        cellMenu = cellMenu,
                        labelEditing = labelEditing,
                        onUpdate = ::updateGrid,
                        onCellTap = { row, col ->
                            pickerCell = row to col
                            cellMenu = null
                            labelEditing = null
                        },
                        onCellLongPress = { row, col ->
                            cellMenu = row to col
                            labelEditing = null
                        },
                        onClearCellMenu = {
                            cellMenu = null
                            labelEditing = null
                        },
                        onOpenLabelEditor = { labelEditing = cellMenu },
                    )
                }
            }
        }
    }
}

/** Remapeia [ShortcutGrid.cells] ao redimensionar (célula fora do novo range é descartada — sem crash). */
private fun remapGrid(grid: ShortcutGrid, newRows: Int, newCols: Int): ShortcutGrid {
    val oldCols = grid.cols
    val remapped = buildMap {
        grid.cells.forEach { (index, cell) ->
            val oldRow = index / oldCols
            val oldCol = index % oldCols
            if (oldRow < newRows && oldCol < newCols) {
                put(oldRow * newCols + oldCol, cell)
            }
        }
    }
    return grid.copy(rows = newRows, cols = newCols, cells = remapped)
}

@Composable
private fun GridListRow(
    grid: ShortcutGrid,
    pendingDelete: Boolean,
    onTapRow: () -> Unit,
    onTapDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    val noRipple = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.panel)
            .then(
                if (pendingDelete) Modifier
                else Modifier.clickable(interactionSource = noRipple, indication = null) { onTapRow() },
            )
            .testTag(gridRowTag(grid.id))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pendingDelete) {
            Text(
                "Grade em uso por um atalho — toque de novo pra remover mesmo assim",
                color = palette.terracotta,
                fontFamily = SpaceGrotesk,
                fontSize = 12.sp,
                modifier = Modifier
                    .weight(1f)
                    .clickable(interactionSource = noRipple, indication = null) { onConfirmDelete() }
                    .testTag(gridDeleteConfirmTag(grid.id)),
            )
            Text(
                "Cancelar",
                color = palette.muted,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(interactionSource = noRipple, indication = null) { onCancelDelete() }
                    .testTag(gridDeleteCancelTag(grid.id))
                    .padding(start = 10.dp),
            )
        } else {
            Column(Modifier.weight(1f)) {
                Text(grid.name, color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "${grid.rows}×${grid.cols}",
                    color = palette.faint,
                    fontFamily = SpaceMono,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box(
                Modifier
                    .size(26.dp)
                    .clickable(interactionSource = noRipple, indication = null) { onTapDelete() }
                    .testTag(gridDeleteTag(grid.id)),
                contentAlignment = Alignment.Center,
            ) {
                StrokeIcon(TRASH_PATHS, palette.faint, Modifier.size(15.dp), strokeWidth = 1.6f)
            }
        }
    }
}

@Composable
private fun AddGridRow(onClick: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    val noRipple = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, palette.line, RoundedCornerShape(8.dp))
            .clickable(interactionSource = noRipple, indication = null) { onClick() }
            .testTag(GRIDS_EDITOR_ADD_TAG)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text("+ grade", color = palette.teal, fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

/** Corpo do editor de grade dentro do [ConfigModal] — sem chrome (título/`✕` são do modal). */
@Composable
private fun GridEditContent(
    grid: ShortcutGrid,
    cellMenu: Pair<Int, Int>?,
    labelEditing: Pair<Int, Int>?,
    onUpdate: (ShortcutGrid) -> Unit,
    onCellTap: (Int, Int) -> Unit,
    onCellLongPress: (Int, Int) -> Unit,
    onClearCellMenu: () -> Unit,
    onOpenLabelEditor: () -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    val noRipple = remember { MutableInteractionSource() }

    AutoSaveField(
        label = "Nome",
        value = grid.name,
        onCommit = { onUpdate(grid.copy(name = it)) },
        tag = GRIDS_EDITOR_NAME_FIELD_TAG,
        modifier = Modifier.padding(top = 12.dp),
    )

    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Stepper(
            label = "Linhas",
            value = grid.rows,
            range = 1..GRID_MAX_DIMENSION,
            onValueChange = { onUpdate(remapGrid(grid, newRows = it, newCols = grid.cols)) },
            tag = GRIDS_EDITOR_ROWS_STEPPER_TAG,
            modifier = Modifier.weight(1f),
        )
        Stepper(
            label = "Colunas",
            value = grid.cols,
            range = 1..GRID_MAX_DIMENSION,
            onValueChange = { onUpdate(remapGrid(grid, newRows = grid.rows, newCols = it)) },
            tag = GRIDS_EDITOR_COLS_STEPPER_TAG,
            modifier = Modifier.weight(1f),
        )
    }

    GridCellsBoard(grid = grid, onCellTap = onCellTap, onCellLongPress = onCellLongPress)

    if (cellMenu != null) {
        val (menuRow, menuCol) = cellMenu
        val idx = menuRow * grid.cols + menuCol
        val cell = grid.cells[idx]
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .testTag(GRIDS_EDITOR_CELL_MENU_TAG),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (labelEditing == cellMenu && cell != null) {
                AutoSaveField(
                    label = "Rótulo",
                    value = cell.label ?: "",
                    onCommit = { newLabel ->
                        onUpdate(grid.copy(cells = grid.cells + (idx to cell.copy(label = newLabel.ifBlank { null }))))
                    },
                    tag = GRIDS_EDITOR_CELL_LABEL_FIELD_TAG,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    "Limpar",
                    color = palette.terracotta,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable(interactionSource = noRipple, indication = null) {
                            if (cell != null) onUpdate(grid.copy(cells = grid.cells - idx))
                            onClearCellMenu()
                        }
                        .testTag(GRIDS_EDITOR_CELL_CLEAR_TAG)
                        .padding(8.dp),
                )
                Text(
                    "Rótulo custom",
                    color = palette.muted,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable(interactionSource = noRipple, indication = null) { onOpenLabelEditor() }
                        .testTag(GRIDS_EDITOR_CELL_LABEL_TAG)
                        .padding(8.dp),
                )
            }
        }
    }
}

/**
 * As células da grade em edição, escaladas pra caber na largura do modal (mesma ideia do teclado do
 * picker, Q8): [CELL_MAX_DP] é o tamanho ideal — grades pequenas ficam idênticas ao que eram —, e a
 * partir de ~6 colunas a célula encolhe em vez de exigir a rolagem por linha que existia antes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCellsBoard(
    grid: ShortcutGrid,
    onCellTap: (Int, Int) -> Unit,
    onCellLongPress: (Int, Int) -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        val gap = CELL_GAP_DP.dp
        val cellSize = minOf(CELL_MAX_DP.dp, (maxWidth - gap * (grid.cols - 1)) / grid.cols)
        val labels = (0 until grid.rows).flatMap { r ->
            (0 until grid.cols).map { c -> grid.cellAt(r, c)?.displayLabel ?: "—" }
        }
        val fontSize = fittedFontSizeSp(
            labels = labels,
            maxTextWidth = (cellSize - CELL_TEXT_PADDING_DP.dp).coerceAtLeast(1.dp),
            maxSp = minOf(CELL_FONT_MAX_SP, cellSize.value * CELL_FONT_RATIO),
            minSp = CELL_FONT_MIN_SP,
            fontFamily = SpaceMono,
        )
        Column {
            for (row in 0 until grid.rows) {
                Row(
                    Modifier.padding(vertical = CELL_GAP_DP.dp / 2),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    for (col in 0 until grid.cols) {
                        val cell = grid.cellAt(row, col)
                        Box(
                            Modifier
                                .width(cellSize)
                                .height(cellSize)
                                .clip(RoundedCornerShape(6.dp))
                                .background(palette.kbKey)
                                .border(1.dp, palette.kbKeyBorder, RoundedCornerShape(6.dp))
                                .combinedClickable(
                                    onClick = { onCellTap(row, col) },
                                    onLongClick = { if (cell != null) onCellLongPress(row, col) },
                                )
                                .testTag(gridCellTag(row, col)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                cell?.displayLabel ?: "—",
                                color = palette.fg,
                                fontFamily = SpaceMono,
                                fontSize = fontSize.sp,
                                maxLines = 1,
                                softWrap = false,
                                // Rótulo custom pode ser longo demais pra qualquer fonte legível
                                // (o piso é 7sp) — elipse em vez de cortar no meio de um caractere.
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val CELL_MAX_DP = 44f
private const val CELL_GAP_DP = 6f
private const val CELL_TEXT_PADDING_DP = 4f
private const val CELL_FONT_RATIO = 0.32f
private const val CELL_FONT_MIN_SP = 7f
private const val CELL_FONT_MAX_SP = 11f

private val TRASH_PATHS = listOf(
    "M5 7h14l-1.2 13.2a2 2 0 0 1-2 1.8H8.2a2 2 0 0 1-2-1.8L5 7Z",
    "M9 7V4.8A1.8 1.8 0 0 1 10.8 3h2.4A1.8 1.8 0 0 1 15 4.8V7",
    "M3 7h18",
)
