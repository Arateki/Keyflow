package com.keyflow.ui.config

import com.google.common.truth.Truth.assertThat
import com.keyflow.data.Settings
import com.keyflow.skins.ImportReport
import com.keyflow.skins.InstallResult
import org.junit.Test

/**
 * O que os ajustes `keyboardSkin`/`orbSkin` viram quando um pacote é removido ou reimportado (SKP-STO-04, R1 da revisão da fase 1): a superfície que
 * usava o pacote volta ao padrão **no próprio ajuste**, não só na hora de desenhar.
 */
class SkinPackSelectionTest {

    private val pack = "pack:neon-1a2b3c4d"
    private val other = "pack:outra-99999999"

    @Test
    fun `removing the selected keyboard pack puts the keyboard back on System and leaves the orb alone`() {
        val after = settingsAfterRemoval(Settings(keyboardSkin = pack, orbSkin = "aero"), pack)
        assertThat(after.keyboardSkin).isEqualTo("system")
        assertThat(after.orbSkin).isEqualTo("aero")
    }

    @Test
    fun `removing the selected orb pack puts the orb back on Atelie and leaves the keyboard alone`() {
        val after = settingsAfterRemoval(Settings(keyboardSkin = "glitch", orbSkin = pack), pack)
        assertThat(after.keyboardSkin).isEqualTo("glitch")
        assertThat(after.orbSkin).isEqualTo("atelie")
    }

    @Test
    fun `a pack that dresses both surfaces resets both, whichever tab removed it`() {
        val after = settingsAfterRemoval(Settings(keyboardSkin = pack, orbSkin = pack), pack)
        assertThat(after.keyboardSkin).isEqualTo("system")
        assertThat(after.orbSkin).isEqualTo("atelie")
    }

    @Test
    fun `removing a pack that is not selected changes nothing`() {
        val before = Settings(keyboardSkin = other, orbSkin = "rosegold")
        assertThat(settingsAfterRemoval(before, pack)).isEqualTo(before)
    }

    // ── reimportar ───────────────────────────────────────────────────────────

    private fun installed(hasOrb: Boolean, hasKeyboard: Boolean, skinId: String = pack) = SkinImportOutcome(
        fileName = "x.zip",
        result = InstallResult(ImportReport(), packId = skinId.removePrefix("pack:")),
        packName = "Neon",
        hasOrb = hasOrb,
        hasKeyboard = hasKeyboard,
    )

    @Test
    fun `reimporting a selected pack that lost the keyboard resets only the keyboard`() {
        val after = settingsAfterInstall(Settings(keyboardSkin = pack, orbSkin = pack), installed(hasOrb = true, hasKeyboard = false))
        assertThat(after.keyboardSkin).isEqualTo("system")
        assertThat(after.orbSkin).isEqualTo(pack)
    }

    @Test
    fun `reimporting a selected pack that lost the orb resets only the orb`() {
        val after = settingsAfterInstall(Settings(keyboardSkin = pack, orbSkin = pack), installed(hasOrb = false, hasKeyboard = true))
        assertThat(after.keyboardSkin).isEqualTo(pack)
        assertThat(after.orbSkin).isEqualTo("atelie")
    }

    @Test
    fun `reimporting a pack that keeps its parts, or is not selected, changes nothing`() {
        val selected = Settings(keyboardSkin = pack, orbSkin = pack)
        assertThat(settingsAfterInstall(selected, installed(hasOrb = true, hasKeyboard = true))).isEqualTo(selected)
        val elsewhere = Settings(keyboardSkin = other, orbSkin = "aero")
        assertThat(settingsAfterInstall(elsewhere, installed(hasOrb = false, hasKeyboard = false))).isEqualTo(elsewhere)
    }

    @Test
    fun `a refused import never touches the settings`() {
        val refused = SkinImportOutcome("x.zip", InstallResult(ImportReport(errors = listOf("erro")), packId = null))
        val before = Settings(keyboardSkin = pack, orbSkin = pack)
        assertThat(settingsAfterInstall(before, refused)).isEqualTo(before)
    }

    // ── o cartão marcado ─────────────────────────────────────────────────────

    @Test
    fun `a pack is never mistaken for the System card, and the native ids still resolve`() {
        assertThat(nativeKeyboardSkin(pack)).isNull()
        assertThat(nativeKeyboardSkin("system")).isEqualTo(com.keyflow.ime.KeyboardSkin.SYSTEM)
        assertThat(nativeKeyboardSkin("glitch")).isEqualTo(com.keyflow.ime.KeyboardSkin.GLITCH)
        // Um id que não existe (ou um `pack:` inseguro) continua caindo no Sistema, como o teclado de verdade.
        assertThat(nativeKeyboardSkin("pack:../../x")).isEqualTo(com.keyflow.ime.KeyboardSkin.SYSTEM)
        assertThat(nativeKeyboardSkin(null)).isEqualTo(com.keyflow.ime.KeyboardSkin.SYSTEM)
    }
}
