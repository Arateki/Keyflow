package com.keyflow.ui.orb

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Lógica pura do orbe: formatação de tempo e equivalência Paused↔Listening (ORB-04, REC-05). */
class VoiceOrbTest {

    @Test
    fun `formats elapsed time as m colon ss`() {
        assertThat(formatOrbTime(0)).isEqualTo("0:00")
        assertThat(formatOrbTime(5)).isEqualTo("0:05")
        assertThat(formatOrbTime(65)).isEqualTo("1:05")
        assertThat(formatOrbTime(600)).isEqualTo("10:00")
    }

    @Test
    fun `negative seconds clamp to zero`() {
        assertThat(formatOrbTime(-3)).isEqualTo("0:00")
    }

    @Test
    fun `paused reuses listening draw params`() {
        assertThat(orbBaseParams(OrbState.Paused)).isEqualTo(orbBaseParams(OrbState.Listening))
        // E o nível animado também coincide para o mesmo t (visual de escuta).
        assertThat(orbLevel(OrbState.Paused, 1234f)).isEqualTo(orbLevel(OrbState.Listening, 1234f))
    }

    @Test
    fun `each non-paused state has distinct base params`() {
        val idle = orbBaseParams(OrbState.Idle)
        val listening = orbBaseParams(OrbState.Listening)
        val transcribing = orbBaseParams(OrbState.Transcribing)
        assertThat(setOf(idle, listening, transcribing)).hasSize(3)
    }

    @Test
    fun `the orb skin id picks the style and anything else is the Atelie orb`() {
        assertThat(orbStyleFor("aero")).isEqualTo(OrbStyle.AERO)
        assertThat(orbStyleFor("atelie")).isEqualTo(OrbStyle.ATELIE)
        assertThat(orbStyleFor("system")).isEqualTo(OrbStyle.ATELIE)
        assertThat(orbStyleFor("desconhecida")).isEqualTo(OrbStyle.ATELIE)
    }

    @Test
    fun `every orb style id round-trips through orbStyleFor`() {
        for (style in OrbStyle.entries) assertThat(orbStyleFor(style.id)).isEqualTo(style)
    }

    @Test
    fun `a new install keeps the Atelie orb even with the Aero keyboard`() {
        // As duas escolhas são independentes: o Aero no teclado não vira orbe de vidro (Yan, 2026-09-29).
        val settings = com.keyflow.data.Settings(keyboardSkin = "aero")
        assertThat(orbStyleFor(settings.orbSkin)).isEqualTo(OrbStyle.ATELIE)
    }
}
