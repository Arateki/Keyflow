package com.keyflow.ui.config

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * 3×3), tocar numa grade abre uma sheet com nome, dimensões (linhas/colunas via [Stepper]) e a grade de
 * células — tocar numa célula (vazia ou preenchida) abre o [PhysicalKeyPicker]; manter pressionado numa
 * célula preenchida abre um menu inline (Limpar / Rótulo custom).
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
) {
    var editingId by remember { mutableStateOf<String?>(null) }
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
            GridEditSheet(
                grid = editing,
                cellMenu = cellMenu,
                labelEditing = labelEditing,
                onDismiss = { closeSheet() },
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

        PhysicalKeyPicker(
            visible = pickerCell != null,
            onSelect = { key ->
                val cell = pickerCell
                val current = grids.find { it.id == editingId }
                if (cell != null && current != null) {
                    val (row, col) = cell
                    val idx = row * current.cols + col
                    updateGrid(current.copy(cells = current.cells + (idx to GridCell(key))))
                }
            },
            onDismiss = { pickerCell = null },
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridEditSheet(
    grid: ShortcutGrid,
    cellMenu: Pair<Int, Int>?,
    labelEditing: Pair<Int, Int>?,
    onDismiss: () -> Unit,
    onUpdate: (ShortcutGrid) -> Unit,
    onCellTap: (Int, Int) -> Unit,
    onCellLongPress: (Int, Int) -> Unit,
    onClearCellMenu: () -> Unit,
    onOpenLabelEditor: () -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    val noRipple = remember { MutableInteractionSource() }

    Box(
        Modifier
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
                .testTag(GRIDS_EDITOR_SHEET_TAG),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Editar grade",
                    color = palette.fg,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Box(
                    Modifier
                        .size(26.dp)
                        .clickable(interactionSource = noRipple, indication = null) { onDismiss() }
                        .testTag(GRIDS_EDITOR_SHEET_CLOSE_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    StrokeIcon(listOf(CLOSE_PATH), palette.faint, Modifier.size(16.dp), strokeWidth = 1.8f)
                }
            }

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
                    range = 1..6,
                    onValueChange = { onUpdate(remapGrid(grid, newRows = it, newCols = grid.cols)) },
                    tag = GRIDS_EDITOR_ROWS_STEPPER_TAG,
                    modifier = Modifier.weight(1f),
                )
                Stepper(
                    label = "Colunas",
                    value = grid.cols,
                    range = 1..10,
                    onValueChange = { onUpdate(remapGrid(grid, newRows = grid.rows, newCols = it)) },
                    tag = GRIDS_EDITOR_COLS_STEPPER_TAG,
                    modifier = Modifier.weight(1f),
                )
            }

            Column(Modifier.padding(top = 14.dp)) {
                for (row in 0 until grid.rows) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (col in 0 until grid.cols) {
                            val cell = grid.cellAt(row, col)
                            Box(
                                Modifier
                                    .size(44.dp)
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
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

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
    }
}

private val TRASH_PATHS = listOf(
    "M5 7h14l-1.2 13.2a2 2 0 0 1-2 1.8H8.2a2 2 0 0 1-2-1.8L5 7Z",
    "M9 7V4.8A1.8 1.8 0 0 1 10.8 3h2.4A1.8 1.8 0 0 1 15 4.8V7",
    "M3 7h18",
)

private const val CLOSE_PATH = "M18 6 6 18M6 6l12 12"
