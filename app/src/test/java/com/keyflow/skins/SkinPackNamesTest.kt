package com.keyflow.skins

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Vocabulário de nomes de arquivo do pacote (FORMAT.md §2): todas as formas aceitas e as rejeições. */
class SkinPackNamesTest {

    private fun frame(name: String) = SkinPackNames.classify(name) as PackFileKind.OrbFrame

    @Test
    fun `every orb state parses with the frame number`() {
        assertThat(SkinPackNames.classify("orb_idle_01.svg")).isEqualTo(PackFileKind.OrbFrame(OrbPackState.IDLE, 1))
        assertThat(SkinPackNames.classify("orb_listening_24.svg")).isEqualTo(PackFileKind.OrbFrame(OrbPackState.LISTENING, 24))
        assertThat(SkinPackNames.classify("orb_paused_01.svg")).isEqualTo(PackFileKind.OrbFrame(OrbPackState.PAUSED, 1))
        assertThat(SkinPackNames.classify("orb_transcribing_08.svg")).isEqualTo(PackFileKind.OrbFrame(OrbPackState.TRANSCRIBING, 8))
    }

    @Test
    fun `the frame accepts leading zeros and counts by number`() {
        assertThat(frame("orb_idle_1.svg").frame).isEqualTo(1)
        assertThat(frame("orb_idle_01.svg").frame).isEqualTo(1)
        assertThat(frame("orb_idle_0001.svg").frame).isEqualTo(1)
        assertThat(frame("orb_idle_0012.svg").frame).isEqualTo(12)
        assertThat(frame("orb_idle_123456789.svg").frame).isEqualTo(123456789)
    }

    @Test
    fun `frame zero or too many digits is invalid, not unknown`() {
        assertThat(SkinPackNames.classify("orb_idle_00.svg")).isEqualTo(PackFileKind.InvalidOrbFrame(tooLarge = false))
        assertThat(SkinPackNames.classify("orb_idle_0.svg")).isEqualTo(PackFileKind.InvalidOrbFrame(tooLarge = false))
        assertThat(SkinPackNames.classify("orb_idle_1234567890.svg")).isEqualTo(PackFileKind.InvalidOrbFrame(tooLarge = true))
    }

    @Test
    fun `the five keyboard parts have fixed names`() {
        assertThat(SkinPackNames.classify("keyboard_tray.svg")).isEqualTo(PackFileKind.KeyboardFace(KeyboardPart.TRAY))
        assertThat(SkinPackNames.classify("keyboard_key_normal.svg")).isEqualTo(PackFileKind.KeyboardFace(KeyboardPart.KEY_NORMAL))
        assertThat(SkinPackNames.classify("keyboard_key_pressed.svg")).isEqualTo(PackFileKind.KeyboardFace(KeyboardPart.KEY_PRESSED))
        assertThat(SkinPackNames.classify("keyboard_modifier_normal.svg")).isEqualTo(PackFileKind.KeyboardFace(KeyboardPart.MODIFIER_NORMAL))
        assertThat(SkinPackNames.classify("keyboard_modifier_pressed.svg")).isEqualTo(PackFileKind.KeyboardFace(KeyboardPart.MODIFIER_PRESSED))
    }

    @Test
    fun `manifest is recognised`() {
        assertThat(SkinPackNames.classify("manifest.json")).isEqualTo(PackFileKind.Manifest)
    }

    @Test
    fun `reserved names are not treated as unknown`() {
        for (name in listOf("orb_error_01.svg", "orb_cancelling_03.svg", "keyboard_fx_press_01.svg", "keyboard_fx_record.svg")) {
            assertThat(SkinPackNames.classify(name)).isEqualTo(PackFileKind.Reserved)
        }
    }

    @Test
    fun `anything else is unknown`() {
        for (name in listOf("readme.txt", "orb_idle_01.png", "orb_idle_01.svg.bak", "orb_idle_.svg", "orb_idle_-1.svg", "orb_idle_a1.svg", "orb_foo_01.svg", "keyboard_key.svg", "orb_idle_01 .svg", "orb_idle_٣.svg")) {
            assertThat(SkinPackNames.classify(name)).isEqualTo(PackFileKind.Unknown(lowercaseWouldMatch = false))
        }
    }

    @Test
    fun `a name that only differs in case is flagged so the report can say so`() {
        assertThat(SkinPackNames.classify("Orb_Idle_01.svg")).isEqualTo(PackFileKind.Unknown(lowercaseWouldMatch = true))
        assertThat(SkinPackNames.classify("Keyboard_Tray.SVG")).isEqualTo(PackFileKind.Unknown(lowercaseWouldMatch = true))
        assertThat(SkinPackNames.classify("MANIFEST.JSON")).isEqualTo(PackFileKind.Unknown(lowercaseWouldMatch = true))
    }

    @Test
    fun `installed frames get a canonical four digit name`() {
        assertThat(SkinPackNames.frameFileName(OrbPackState.LISTENING, 7)).isEqualTo("orb_listening_0007.svg")
        assertThat(SkinPackNames.frameFileName(OrbPackState.IDLE, 64)).isEqualTo("orb_idle_0064.svg")
        // E o nome canônico volta a ser o mesmo frame (a reimportação de um pacote já instalado não muda nada).
        assertThat(SkinPackNames.classify(SkinPackNames.frameFileName(OrbPackState.PAUSED, 12))).isEqualTo(PackFileKind.OrbFrame(OrbPackState.PAUSED, 12))
    }

    @Test
    fun `state keys are the same text used in the manifest`() {
        assertThat(OrbPackState.entries.map { it.key }).containsExactly("idle", "listening", "paused", "transcribing").inOrder()
        assertThat(OrbPackState.ofKey("listening")).isEqualTo(OrbPackState.LISTENING)
        assertThat(OrbPackState.ofKey("error")).isNull()
    }
}
