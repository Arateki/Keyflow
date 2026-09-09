package com.keyflow.ui.tour

import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.ui.config.pauseHintFor
import com.keyflow.ui.config.recordingHintParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCR-01..06: o catálogo é função pura das Settings — o tour mostra os gestos que valem para a
 * configuração DAQUELA pessoa, não um conjunto fixo.
 */
class GestureScriptCatalogTest {

    private fun settings(
        mode: RecordingMode = RecordingMode.TOGGLE,
        tap1: OrbShortcutAction = OrbShortcutAction.NONE,
        tap2: OrbShortcutAction = OrbShortcutAction.NONE,
        tap3: OrbShortcutAction = OrbShortcutAction.NONE,
    ) = Settings(
        overlayRecordingMode = mode,
        shortcut1Tap = tap1,
        shortcut2Tap = tap2,
        shortcut3Tap = tap3,
    )

    private fun ids(scripts: List<GestureScript>) = scripts.map { it.id }

    // ── SCR-02: básicos por modo ──────────────────────────────────────────────

    @Test
    fun toggleModeShowsTapToRecordAndHoldToPause() {
        val ids = ids(buildOrbScripts(settings(mode = RecordingMode.TOGGLE)))

        assertTrue(ids.contains("orb-toggle-basics"))
        assertTrue(ids.contains("orb-toggle-pause"))
        assertFalse("PTT não vale neste modo", ids.any { it.startsWith("orb-ptt-") })
    }

    @Test
    fun pushToTalkModeShowsHoldToTalkAndQuickTapToPause() {
        val ids = ids(buildOrbScripts(settings(mode = RecordingMode.PUSH_TO_TALK)))

        assertTrue(ids.contains("orb-ptt-basics"))
        assertTrue(ids.contains("orb-ptt-pause"))
        assertFalse("TOGGLE não vale neste modo", ids.any { it.startsWith("orb-toggle-") })
    }

    // ── SCR-04: cancelar/desfazer e reposicionar valem nos dois modos ─────────

    @Test
    fun cancelUndoAndRepositionExistInBothModes() {
        for (mode in RecordingMode.entries) {
            val ids = ids(buildOrbScripts(settings(mode = mode)))
            assertTrue("$mode sem cancelar/desfazer", ids.contains("orb-cancel-undo"))
            assertTrue("$mode sem reposicionar", ids.contains("orb-reposition"))
        }
    }

    // ── SCR-03: atalhos de rajada refletem a config real ─────────────────────

    @Test
    fun shortcutSetToNoneIsOmitted() {
        val ids = ids(
            buildOrbScripts(
                settings(tap2 = OrbShortcutAction.NONE, tap3 = OrbShortcutAction.SWITCH_IME)
            )
        )

        assertFalse(ids.contains("orb-shortcut-2tap"))
        assertTrue(ids.contains("orb-shortcut-3tap"))
    }

    @Test
    fun oneTapShortcutOnlyExistsInPushToTalk() {
        val toggle = ids(
            buildOrbScripts(
                settings(mode = RecordingMode.TOGGLE, tap1 = OrbShortcutAction.SWITCH_IME)
            )
        )
        val ptt = ids(
            buildOrbScripts(
                settings(mode = RecordingMode.PUSH_TO_TALK, tap1 = OrbShortcutAction.SWITCH_IME)
            )
        )

        assertFalse("no TOGGLE o toque é da gravação, não sobra pra atalho", toggle.contains("orb-shortcut-1tap"))
        assertTrue(ptt.contains("orb-shortcut-1tap"))
    }

    @Test
    fun shortcutTitleUsesTheConfiguredActionLabel() {
        val scripts = buildOrbScripts(settings(tap3 = OrbShortcutAction.TOGGLE_KEYBOARD))

        val script = scripts.first { it.id == "orb-shortcut-3tap" }
        assertTrue(
            "título deveria trazer o rótulo real da ação, veio: ${script.title}",
            script.title.contains("Retrair / expandir teclado"),
        )
    }

    @Test
    fun shortcutStepUsesTheConfiguredTapCount() {
        val scripts = buildOrbScripts(settings(tap3 = OrbShortcutAction.SWITCH_IME))

        val step = scripts.first { it.id == "orb-shortcut-3tap" }.steps
            .first { it.action == FingerAction.MULTI_TAP }
        assertEquals(3, step.tapCount)
    }

    // ── SCR-06: ordem pedagógica estável ─────────────────────────────────────

    @Test
    fun orderGoesFromBasicsToPauseToCancelToRepositionToShortcuts() {
        val ids = ids(
            buildOrbScripts(
                settings(tap2 = OrbShortcutAction.TOGGLE_KEYBOARD, tap3 = OrbShortcutAction.SWITCH_IME)
            )
        )

        assertEquals(
            listOf(
                "orb-toggle-basics",
                "orb-toggle-pause",
                "orb-cancel-undo",
                "orb-reposition",
                "orb-shortcut-2tap",
                "orb-shortcut-3tap",
            ),
            ids,
        )
    }

    @Test
    fun sameSettingsAlwaysProduceTheSameScripts() {
        val s = settings(mode = RecordingMode.PUSH_TO_TALK, tap1 = OrbShortcutAction.SWITCH_IME)

        assertEquals(buildOrbScripts(s), buildOrbScripts(s))
    }

    // ── SCR-05: teclado ──────────────────────────────────────────────────────

    @Test
    fun keyboardScriptsCoverCollapseAndTheMic() {
        val ids = ids(buildKeyboardScripts(settings()))

        assertEquals(listOf("kbd-collapse-expand", "kbd-mic-tap", "kbd-mic-hold"), ids)
    }

    @Test
    fun keyboardMicHoldOpensTheActionSelector() {
        val hold = buildKeyboardScripts(settings()).first { it.id == "kbd-mic-hold" }

        assertTrue(
            "segurar o mic deveria mostrar o seletor de ações",
            hold.steps.any { it.reaction.overlayHint == TourOverlayHint.MIC_ACTION_SELECTOR },
        )
    }

    // ── Fonte textual única ──────────────────────────────────────────────────

    @Test
    fun descriptionsComeFromTheSameHintTextAsTheConfigScreen() {
        for (mode in RecordingMode.entries) {
            val basics = buildOrbScripts(settings(mode = mode)).first { it.id.endsWith("-basics") }
            val parts = recordingHintParts(mode)

            assertTrue(
                "$mode: a descrição dos básicos deveria sair de recordingHintParts",
                parts.any { basics.description.contains(it) },
            )
        }
    }

    @Test
    fun theConfigHintTextIsUnchangedByTheSplit() {
        // A aba Modo mostra esta frase e ela tem golden. Partir o texto em pedaços para o tour não
        // pode mudar um caractere do que a config renderiza.
        assertEquals(
            "Toque para gravar ou parar · segure para pausar e retomar.",
            pauseHintFor(RecordingMode.TOGGLE),
        )
        assertEquals(
            "Segure para gravar · solte para enviar · toque rápido após soltar para pausar.",
            pauseHintFor(RecordingMode.PUSH_TO_TALK),
        )
    }

    // ── SCR-01: dado puro, serializável ──────────────────────────────────────

    @Test
    fun scriptsAreValueDataWithNoHiddenIdentity() {
        val script = buildOrbScripts(settings()).first()

        // Semântica de data class ponta a ponta (id/steps/reactions): é o que permite roteiros de
        // features futuras entrarem como dados, e comparação por valor nos testes do player.
        assertEquals(script, script.copy())
        assertEquals(script.steps, script.steps.map { it.copy() })
    }
}
