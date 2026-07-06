package com.keyflow.ui.config

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.GridTriggerMode
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.core.shortcuts.ShortcutKey
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Testes do editor de barras (SB-T9, parte `BarsEditor`): catálogo de botões, edição/remoção,
 * drag-to-reorder (via função pura [reorderedButtons] + smoke instrumentado do gesto), "+ barra",
 * lixeira e o toggle de espelhamento copiando a lista em edição para os dois modos.
 */
@RunWith(AndroidJUnit4::class)
class BarsEditorTest {

    @get:Rule
    val rule = createComposeRule()

    private val grid1 = ShortcutGrid(id = "g1", name = "Grade 1", rows = 2, cols = 2)

    private inner class Harness(initial: BarsConfig, val grids: List<ShortcutGrid> = emptyList()) {
        var barsConfig by mutableStateOf(initial)

        @Composable
        fun Content() {
            KeyFlowTheme(dark = false) {
                BarsEditor(
                    barsConfig = barsConfig,
                    onBarsConfigChange = { barsConfig = it },
                    grids = grids,
                )
            }
        }
    }

    @Test
    fun addingMicButtonAppendsToActiveBar() {
        val h = Harness(BarsConfig(mirrored = false, collapsed = listOf(BarDef(emptyList())), expanded = listOf(BarDef(emptyList()))))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(barAddButtonTag(0)).performClick()
        rule.onNodeWithTag(BARS_EDITOR_CATALOG_MIC_TAG).performClick()

        rule.runOnIdle {
            assertEquals(listOf(BarButton.Mic), h.barsConfig.collapsed[0].buttons)
        }
    }

    @Test
    fun addingSingleKeyButtonAppendsWithPickedKey() {
        val h = Harness(BarsConfig(mirrored = false, collapsed = listOf(BarDef(emptyList())), expanded = listOf(BarDef(emptyList()))))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(barAddButtonTag(0)).performClick()
        rule.onNodeWithTag(BARS_EDITOR_CATALOG_SINGLE_KEY_TAG).performClick()
        rule.onNodeWithTag(physicalKeyTag(KeyEvent.KEYCODE_A)).performClick()

        rule.runOnIdle {
            val button = h.barsConfig.collapsed[0].buttons.single()
            assertTrue(button is BarButton.SingleKey)
            assertEquals(KeyEvent.KEYCODE_A, (button as BarButton.SingleKey).key.keyCode)
            assertEquals("a", button.key.label)
        }
    }

    @Test
    fun addingGridTriggerAppendsWithChosenGridId() {
        val h = Harness(
            BarsConfig(mirrored = false, collapsed = listOf(BarDef(emptyList())), expanded = listOf(BarDef(emptyList()))),
            grids = listOf(grid1),
        )
        rule.setContent { h.Content() }

        rule.onNodeWithTag(barAddButtonTag(0)).performClick()
        rule.onNodeWithTag(BARS_EDITOR_CATALOG_GRID_TAG).performClick()
        rule.onNodeWithTag(BARS_EDITOR_GRID_FORM_CONFIRM_TAG).performClick()

        rule.runOnIdle {
            val button = h.barsConfig.collapsed[0].buttons.single()
            assertTrue(button is BarButton.GridTrigger)
            assertEquals("g1", (button as BarButton.GridTrigger).gridId)
        }
    }

    @Test
    fun removingButtonViaEditSheetRemovesIt() {
        val h = Harness(
            BarsConfig(
                mirrored = false,
                collapsed = listOf(BarDef(listOf(BarButton.Mic))),
                expanded = listOf(BarDef(emptyList())),
            ),
        )
        rule.setContent { h.Content() }

        rule.onNodeWithTag(barChipTag(0, 0)).performClick()
        rule.onNodeWithTag(BARS_EDITOR_EDIT_REMOVE_TAG).performClick()

        rule.runOnIdle {
            assertEquals(emptyList<BarButton>(), h.barsConfig.collapsed[0].buttons)
        }
    }

    @Test
    fun reorderedButtonsPureFunctionSwapsOrder() {
        // Lógica pura do drag (extraída p/ testabilidade — ver doc do gesto de arrastar no BarsEditor).
        val key1 = ShortcutKey(KeyEvent.KEYCODE_A, label = "a")
        val key2 = ShortcutKey(KeyEvent.KEYCODE_B, label = "b")
        val buttons = listOf(BarButton.SingleKey(key1), BarButton.SingleKey(key2))

        val result = reorderedButtons(buttons, fromIndex = 0, toIndex = 1)

        assertEquals(listOf(BarButton.SingleKey(key2), BarButton.SingleKey(key1)), result)
    }

    @Test
    fun dragGestureOnChipDoesNotCrashTheEditor() {
        // Smoke: o modificador de drag está anexado e o gesto (long-press + arrasto) não derruba a
        // composição. A troca de ordem em si é coberta pela função pura acima (mais confiável que medir
        // px de threshold num teste instrumentado).
        val h = Harness(
            BarsConfig(
                mirrored = false,
                collapsed = listOf(
                    BarDef(
                        listOf(
                            BarButton.SingleKey(ShortcutKey(KeyEvent.KEYCODE_A, label = "a")),
                            BarButton.SingleKey(ShortcutKey(KeyEvent.KEYCODE_B, label = "b")),
                        ),
                    ),
                ),
                expanded = listOf(BarDef(emptyList())),
            ),
        )
        rule.setContent { h.Content() }

        rule.onNodeWithTag(barChipTag(0, 0)).performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(Offset(120f, 0f))
            up()
        }

        rule.onNodeWithTag(BARS_EDITOR_TAG).assertExists()
        // A lista continua com os 2 botões (ordem trocada ou não — só garantimos que nada sumiu).
        rule.runOnIdle {
            assertEquals(2, h.barsConfig.collapsed[0].buttons.size)
        }
    }

    @Test
    fun addBarAppendsNewEmptyBarDef() {
        val h = Harness(BarsConfig(mirrored = false, collapsed = listOf(BarDef(emptyList())), expanded = listOf(BarDef(emptyList()))))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(BARS_EDITOR_ADD_BAR_TAG).performClick()

        rule.runOnIdle {
            assertEquals(2, h.barsConfig.collapsed.size)
            assertEquals(emptyList<BarButton>(), h.barsConfig.collapsed[1].buttons)
        }
    }

    @Test
    fun trashRemovesWholeBar() {
        val h = Harness(
            BarsConfig(
                mirrored = false,
                collapsed = listOf(BarDef(listOf(BarButton.Mic)), BarDef(listOf(BarButton.ConfigBtn))),
                expanded = listOf(BarDef(emptyList())),
            ),
        )
        rule.setContent { h.Content() }

        rule.onNodeWithTag(barTrashTag(0)).performClick()

        rule.runOnIdle {
            assertEquals(1, h.barsConfig.collapsed.size)
            assertEquals(listOf(BarButton.ConfigBtn), h.barsConfig.collapsed[0].buttons)
        }
    }

    @Test
    fun togglingMirrorOnWhileViewingExpandedCopiesThatListIntoBoth() {
        val h = Harness(
            BarsConfig(
                mirrored = false,
                collapsed = listOf(BarDef(listOf(BarButton.Mic))),
                expanded = listOf(BarDef(listOf(BarButton.ConfigBtn))),
            ),
        )
        rule.setContent { h.Content() }

        rule.onNodeWithTag(BARS_EDITOR_MODE_EXPANDED_TAG).performClick()
        rule.onNodeWithTag(BARS_EDITOR_MIRROR_TOGGLE_TAG).performClick()

        rule.runOnIdle {
            assertTrue(h.barsConfig.mirrored)
            assertEquals(listOf(BarDef(listOf(BarButton.ConfigBtn))), h.barsConfig.collapsed)
            assertEquals(listOf(BarDef(listOf(BarButton.ConfigBtn))), h.barsConfig.expanded)
        }
    }

    @Test
    fun gridTriggerFormHiddenWhenNoGridsAvailable() {
        val h = Harness(BarsConfig(mirrored = false, collapsed = listOf(BarDef(emptyList())), expanded = listOf(BarDef(emptyList()))))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(barAddButtonTag(0)).performClick()

        rule.onAllNodesWithTag(BARS_EDITOR_CATALOG_GRID_TAG).assertCountEquals(0)
    }
}
