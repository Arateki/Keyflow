package com.keyflow.ui.config

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.ime.bars.BarGeometry
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
    fun gridFormExplainsEachModeAndShowsPrefixOnlyWhereItCounts() {
        val h = Harness(
            BarsConfig(mirrored = false, collapsed = listOf(BarDef(emptyList())), expanded = listOf(BarDef(emptyList()))),
            grids = listOf(grid1),
        )
        rule.setContent { h.Content() }

        rule.onNodeWithTag(barAddButtonTag(0)).performClick()
        rule.onNodeWithTag(BARS_EDITOR_CATALOG_GRID_TAG).performClick()

        // Uma explicação sob cada modo, todas da mesma altura (trocar de modo não mexe no layout).
        val hintHeights = GridTriggerMode.entries.map { mode ->
            rule.onNodeWithTag("${gridModeTag(mode)}-hint", useUnmergedTree = true).assertTextContains(gridModeHint(mode))
                .getUnclippedBoundsInRoot().let { it.bottom - it.top }
        }
        assertEquals(1, hintHeights.distinct().size)

        // "Só grade" (padrão) não usa prefixo: o seletor não aparece.
        rule.onAllNodesWithTag(BARS_EDITOR_GRID_FORM_PREFIX_SET_TAG).assertCountEquals(0)
        rule.onNodeWithTag(gridModeTag(GridTriggerMode.SEQUENCE)).performClick()
        rule.onNodeWithTag(BARS_EDITOR_GRID_FORM_PREFIX_SET_TAG).assertIsDisplayed()
        rule.onNodeWithTag(gridModeTag(GridTriggerMode.CHORD)).performClick()
        rule.onNodeWithTag(BARS_EDITOR_GRID_FORM_PREFIX_SET_TAG).assertIsDisplayed()
        rule.onNodeWithTag(gridModeTag(GridTriggerMode.OPEN_ONLY)).performClick()
        rule.onAllNodesWithTag(BARS_EDITOR_GRID_FORM_PREFIX_SET_TAG).assertCountEquals(0)
    }

    @Test
    fun switchingToOpenOnlyDoesNotKeepAHiddenPrefix() {
        val ctrlB = ShortcutKey(KeyEvent.KEYCODE_B, ctrl = true, label = "Ctrl+B")
        val h = Harness(
            BarsConfig(
                mirrored = false,
                collapsed = listOf(BarDef(listOf(BarButton.GridTrigger("g1", ctrlB, GridTriggerMode.SEQUENCE)))),
                expanded = listOf(BarDef(emptyList())),
            ),
            grids = listOf(grid1),
        )
        rule.setContent { h.Content() }

        rule.onNodeWithTag(barChipTag(0, 0)).performClick()
        rule.onNodeWithTag(BARS_EDITOR_GRID_FORM_PREFIX_REMOVE_TAG).assertIsDisplayed()
        rule.onNodeWithTag(gridModeTag(GridTriggerMode.OPEN_ONLY)).performClick()
        rule.onNodeWithTag(BARS_EDITOR_GRID_FORM_CONFIRM_TAG).performClick()

        rule.runOnIdle {
            val button = h.barsConfig.collapsed[0].buttons.single() as BarButton.GridTrigger
            assertEquals(GridTriggerMode.OPEN_ONLY, button.mode)
            assertEquals(null, button.prefix)
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

    @Test
    fun warnsWhichButtonsCanScrollTheBarAndWhichCannot() {
        // A barra só rola a partir de um botão de TOQUE: mic/alavanca/grade capturam o arrasto e a faixa
        // de sugestões tem rolagem interna. Quem monta a barra não tem como adivinhar isso olhando os
        // chips, e uma barra sem apoio nenhum deixa o excedente inalcançável (Yan, 2026-09-08).
        val semApoio = BarDef(
            listOf(
                BarButton.Mic, BarButton.Joystick, BarButton.Candidates,
                BarButton.GridTrigger(gridId = "g"), BarButton.Mic, BarButton.Joystick,
            ),
        )
        val h = Harness(BarsConfig(mirrored = true, collapsed = listOf(semApoio), expanded = emptyList()))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(BARS_EDITOR_SCROLL_WARNING_TAG)
            .assertTextContains("nenhum botão dela serve de apoio para rolar", substring = true)
    }

    @Test
    fun warningListsTheTouchButtonsThatDoScroll() {
        val comApoio = BarDef(
            listOf(
                BarButton.Mic, BarButton.Candidates, BarButton.Paste, BarButton.Joystick,
                BarButton.SingleKey(ShortcutKey(KeyEvent.KEYCODE_ESCAPE, label = "Esc")),
                BarButton.ToggleExpand,
            ),
        )
        val h = Harness(BarsConfig(mirrored = true, collapsed = listOf(comApoio), expanded = emptyList()))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(BARS_EDITOR_SCROLL_WARNING_TAG)
            .assertTextContains("o arrasto só pega em colar, Esc e colapsar/expandir", substring = true)
    }

    @Test
    fun theCandidatesSlotIsPreviewedWithTheAreaItReallyTakes() {
        // A prévia é WYSIWYG (CFG-02) e a faixa de sugestões ocupa ~3 botões no teclado. Desenhada como
        // um chip pequeno "abc", ela mentia sobre o resultado — quem montava a barra só descobria o
        // aperto ao usar (pedido do Yan, 2026-09-08: "essa área que ele ocupa deve representar a área
        // real pro usuário saber como vai ficar").
        val def = BarDef(listOf(BarButton.Mic, BarButton.Candidates, BarButton.ToggleExpand))
        val h = Harness(BarsConfig(mirrored = true, collapsed = listOf(def), expanded = emptyList()))
        rule.setContent { h.Content() }

        val mic = rule.onNodeWithTag(barChipTag(0, 0)).getUnclippedBoundsInRoot().width
        val slot = rule.onNodeWithTag(barChipTag(0, 1)).getUnclippedBoundsInRoot().width

        // O peso vira largura MÍNIMA, não fixa: com largura fixa o rótulo era truncado ("colar" virava
        // "c"), então o conteúdo pode empurrar um chip além da proporção. O que a prévia precisa
        // garantir é que a faixa reserve a área dela — 2,8 unidades desde que ficou 30% menor (Yan,
        // 2026-09-28) — e apareça bem mais larga que um botão comum, para quem monta a barra prever o aperto.
        assertTrue("a faixa ($slot) deveria ser bem mais larga que o mic ($mic)", slot > mic * 1.5f)
        rule.onNodeWithTag(barChipTag(0, 1)).assertWidthIsAtLeast(66.dp)
    }

    @Test
    fun addControlStaysReachableWithABarFullOfButtons() {
        // O "+" ficava DENTRO da faixa rolável dos chips: numa barra cheia ele saía da vista junto com
        // os botões e só voltava rolando até o fim. Agora vive na área de controles, fixo à direita.
        val cheia = BarDef(
            listOf(
                BarButton.Mic, BarButton.ConfigBtn, BarButton.Paste, BarButton.ToggleExpand,
                BarButton.Joystick, BarButton.Candidates,
                BarButton.SingleKey(ShortcutKey(KeyEvent.KEYCODE_ESCAPE, label = "Esc")),
                BarButton.SingleKey(ShortcutKey(KeyEvent.KEYCODE_ENTER, label = "\u23CE")),
                BarButton.SingleKey(ShortcutKey(KeyEvent.KEYCODE_TAB, label = "Tab")),
                BarButton.SingleKey(ShortcutKey(KeyEvent.KEYCODE_A, label = "a")),
            ),
        )
        val h = Harness(BarsConfig(mirrored = true, collapsed = listOf(cheia), expanded = emptyList()))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(barAddButtonTag(0)).assertIsDisplayed()

        // E continua abrindo o catálogo (que agora é um modal com ✕, não mais um sheet embutido).
        rule.onNodeWithTag(barAddButtonTag(0)).performClick()
        rule.onNodeWithTag(BARS_EDITOR_CATALOG_TAG).assertIsDisplayed()
        rule.onNodeWithTag(BARS_EDITOR_CATALOG_CLOSE_TAG).performClick()
        rule.onAllNodesWithTag(BARS_EDITOR_CATALOG_TAG).assertCountEquals(0)
    }
}
