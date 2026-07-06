package com.keyflow.ui.config

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.shortcuts.GridCell
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.core.shortcuts.ShortcutKey
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** SB-T9 (CFG-04): editor de grades — dimensões, células via picker, rótulo custom, exclusão órfã. */
@RunWith(AndroidJUnit4::class)
class GridsEditorTest {

    @get:Rule
    val rule = createComposeRule()

    private inner class Harness(
        initial: List<ShortcutGrid>,
        private val referencedGridIds: Set<String> = emptySet(),
    ) {
        var grids by mutableStateOf(initial)

        @Composable
        fun Content() {
            KeyFlowTheme(dark = false) {
                GridsEditor(
                    grids = grids,
                    onGridsChange = { grids = it },
                    referencedGridIds = referencedGridIds,
                )
            }
        }
    }

    @Test
    fun addingGridAppendsDefaultThreeByThree() {
        val h = Harness(emptyList())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(GRIDS_EDITOR_ADD_TAG).performClick()

        rule.runOnIdle {
            assertEquals(1, h.grids.size)
            val added = h.grids.single()
            assertEquals(3, added.rows)
            assertEquals(3, added.cols)
            assertEquals("Grade 1", added.name)
            assertTrue(added.cells.isEmpty())
        }

        // Adicionar uma segunda deve incrementar o nome e não perder a primeira.
        rule.onNodeWithTag(GRIDS_EDITOR_ADD_TAG).performClick()
        rule.runOnIdle {
            assertEquals(2, h.grids.size)
            assertEquals("Grade 2", h.grids[1].name)
        }
    }

    @Test
    fun assigningKeyToACellViaThePickerUpdatesTheCellsMap() {
        val grid = ShortcutGrid(id = "g1", name = "Grade 1", rows = 3, cols = 3)
        val h = Harness(listOf(grid))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(gridRowTag("g1")).performClick()
        rule.onNodeWithTag(gridCellTag(0, 0)).performClick()
        rule.onNodeWithTag(physicalKeyTag(KeyEvent.KEYCODE_A)).performClick()

        rule.runOnIdle {
            val updated = h.grids.single { it.id == "g1" }
            assertEquals(GridCell(ShortcutKey(KeyEvent.KEYCODE_A, label = "a")), updated.cells[0])
        }
    }

    @Test
    fun shrinkingRowsDropsOutOfRangeCells() {
        // Célula sobrevivente em (0,0) = index 0; célula que deve cair em (2,0) = index 6 (cols=3).
        val grid = ShortcutGrid(
            id = "g1",
            name = "Grade 1",
            rows = 3,
            cols = 3,
            cells = mapOf(
                0 to GridCell(ShortcutKey(KeyEvent.KEYCODE_A, label = "a")),
                6 to GridCell(ShortcutKey(KeyEvent.KEYCODE_B, label = "b")),
            ),
        )
        val h = Harness(listOf(grid))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(gridRowTag("g1")).performClick()
        // 3 -> 2 -> 1 linha (dois toques no "-").
        rule.onNodeWithTag("$GRIDS_EDITOR_ROWS_STEPPER_TAG-dec").performClick()
        rule.onNodeWithTag("$GRIDS_EDITOR_ROWS_STEPPER_TAG-dec").performClick()

        rule.runOnIdle {
            val updated = h.grids.single { it.id == "g1" }
            assertEquals(1, updated.rows)
            assertEquals(GridCell(ShortcutKey(KeyEvent.KEYCODE_A, label = "a")), updated.cells[0])
            assertFalse("célula fora do novo range deveria ter sido descartada", updated.cells.containsKey(6))
            assertEquals(1, updated.cells.size)
        }
    }

    @Test
    fun longPressClearRemovesCellsKey() {
        val grid = ShortcutGrid(
            id = "g1",
            name = "Grade 1",
            rows = 3,
            cols = 3,
            cells = mapOf(0 to GridCell(ShortcutKey(KeyEvent.KEYCODE_A, label = "a"))),
        )
        val h = Harness(listOf(grid))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(gridRowTag("g1")).performClick()
        rule.onNodeWithTag(gridCellTag(0, 0)).performTouchInput { longClick() }
        rule.onNodeWithTag(GRIDS_EDITOR_CELL_CLEAR_TAG).performClick()

        rule.runOnIdle {
            val updated = h.grids.single { it.id == "g1" }
            assertNull(updated.cells[0])
            assertTrue(updated.cells.isEmpty())
        }
    }

    @Test
    fun deletingAnUnreferencedGridRemovesItImmediately() {
        val grid = ShortcutGrid(id = "g1", name = "Grade 1", rows = 3, cols = 3)
        val h = Harness(listOf(grid), referencedGridIds = emptySet())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(gridDeleteTag("g1")).performClick()

        rule.runOnIdle { assertTrue(h.grids.isEmpty()) }
    }

    @Test
    fun deletingAReferencedGridRequiresTwoTaps() {
        val grid = ShortcutGrid(id = "g1", name = "Grade 1", rows = 3, cols = 3)
        val h = Harness(listOf(grid), referencedGridIds = setOf("g1"))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(gridDeleteTag("g1")).performClick()
        rule.runOnIdle {
            // Primeiro toque não remove ainda — só sinaliza o aviso de binding órfão.
            assertEquals(1, h.grids.size)
        }
        rule.onAllNodesWithTag(gridDeleteTag("g1")).assertCountEquals(0)

        rule.onNodeWithTag(gridDeleteConfirmTag("g1")).performClick()

        rule.runOnIdle { assertTrue(h.grids.isEmpty()) }
    }
}
