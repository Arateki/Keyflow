package com.keyflow.ui.config

import android.view.KeyEvent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.shortcuts.ShortcutKey
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** SB-T8 (CFG-05): tocar uma tecla do picker produz o [ShortcutKey] certo, com/sem modificador. */
@RunWith(AndroidJUnit4::class)
class PhysicalKeyPickerTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun invisibleWhenNotVisible() {
        rule.setContent {
            KeyFlowTheme(dark = false) {
                PhysicalKeyPicker(visible = false, onSelect = {}, onDismiss = {})
            }
        }
        rule.onAllNodesWithTag(PHYSICAL_KEY_PICKER_TAG).assertCountEquals(0)
    }

    @Test
    fun tappingALetterWithoutModifiersProducesThePlainKey() {
        var selected: ShortcutKey? = null
        rule.setContent {
            KeyFlowTheme(dark = false) {
                PhysicalKeyPicker(visible = true, onSelect = { selected = it }, onDismiss = {})
            }
        }

        rule.onNodeWithTag(PHYSICAL_KEY_PICKER_TAG).assertIsDisplayed()
        rule.onNodeWithTag(physicalKeyTag(KeyEvent.KEYCODE_B)).performClick()

        rule.runOnIdle {
            assertEquals(KeyEvent.KEYCODE_B, selected?.keyCode)
            assertEquals("b", selected?.label)
            assertEquals(false, selected?.hasModifiers)
        }
    }

    @Test
    fun togglingCtrlThenTappingALetterProducesTheChordLabel() {
        var selected: ShortcutKey? = null
        rule.setContent {
            KeyFlowTheme(dark = false) {
                PhysicalKeyPicker(visible = true, onSelect = { selected = it }, onDismiss = {})
            }
        }

        rule.onNodeWithTag(PHYSICAL_KEY_CTRL_TAG).performClick()
        rule.onNodeWithTag(physicalKeyTag(KeyEvent.KEYCODE_B)).performClick()

        rule.runOnIdle {
            assertEquals("Ctrl+b", selected?.label)
            assertEquals(true, selected?.ctrl)
        }
    }

    @Test
    fun multipleModifiersComposeIntoTheLabelInOrder() {
        var selected: ShortcutKey? = null
        rule.setContent {
            KeyFlowTheme(dark = false) {
                PhysicalKeyPicker(visible = true, onSelect = { selected = it }, onDismiss = {})
            }
        }

        rule.onNodeWithTag(PHYSICAL_KEY_CTRL_TAG).performClick()
        rule.onNodeWithTag(PHYSICAL_KEY_SHIFT_TAG).performClick()
        rule.onNodeWithTag(physicalKeyTag(KeyEvent.KEYCODE_ESCAPE)).performClick()

        rule.runOnIdle {
            assertEquals("Ctrl+Shift+Esc", selected?.label)
        }
    }

    @Test
    fun selectingAKeyDismissesThePicker() {
        var dismissed = false
        rule.setContent {
            KeyFlowTheme(dark = false) {
                PhysicalKeyPicker(visible = true, onSelect = {}, onDismiss = { dismissed = true })
            }
        }

        rule.onNodeWithTag(physicalKeyTag(KeyEvent.KEYCODE_A)).performClick()

        rule.runOnIdle { assertEquals(true, dismissed) }
    }

    @Test
    fun tappingTheScrimDismissesWithoutSelecting() {
        var selected: ShortcutKey? = null
        var dismissed = false
        rule.setContent {
            KeyFlowTheme(dark = false) {
                PhysicalKeyPicker(
                    visible = true,
                    onSelect = { selected = it },
                    onDismiss = { dismissed = true },
                )
            }
        }

        rule.onNodeWithTag(PHYSICAL_KEY_PICKER_CLOSE_TAG).performClick()

        rule.runOnIdle {
            assertEquals(true, dismissed)
            assertNull(selected)
        }
    }

    @Test
    fun theWholeKeyboardFitsWithoutHorizontalScrolling() {
        rule.setContent {
            KeyFlowTheme(dark = false) {
                PhysicalKeyPicker(visible = true, onSelect = {}, onDismiss = {})
            }
        }

        // Q8: cada linha escala pra largura disponível, então as ÚLTIMAS teclas das linhas mais longas
        // (que antes ficavam fora da viewport, atrás do horizontalScroll de cada linha) estão visíveis.
        rule.onNodeWithTag(physicalKeyTag(KeyEvent.KEYCODE_F12)).assertIsDisplayed()
        rule.onNodeWithTag(physicalKeyTag(KeyEvent.KEYCODE_BACKSLASH)).assertIsDisplayed()
        rule.onNodeWithTag(physicalKeyTag(KeyEvent.KEYCODE_ENTER)).assertIsDisplayed()
        rule.onNodeWithTag(physicalKeyTag(KeyEvent.KEYCODE_FORWARD_DEL)).assertIsDisplayed()
    }
}
