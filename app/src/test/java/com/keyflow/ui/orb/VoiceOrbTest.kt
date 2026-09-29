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

    @Test
    fun `the water inside the Aero orb rises with the voice and stays inside the glass`() {
        val rest = aeroWaterFill(0.55f)
        val peak = aeroWaterFill(1f)
        assertThat(rest).isAtLeast(0.3f)
        assertThat(peak).isGreaterThan(rest)
        assertThat(peak).isAtMost(0.7f)
        // Fora da faixa do nível a água não esvazia nem transborda.
        assertThat(aeroWaterFill(0f)).isEqualTo(rest)
        assertThat(aeroWaterFill(2f)).isEqualTo(peak)
    }

    @Test
    fun `the water waves are calm at rest and taller while listening`() {
        for (state in OrbState.entries) {
            val (amp, _) = orbBaseParams(state)
            assertThat(aeroWaveHeight(amp, orbLevel(state, 0f))).isGreaterThan(0f)
        }
        val idle = orbBaseParams(OrbState.Idle).first
        val listening = orbBaseParams(OrbState.Listening).first
        assertThat(aeroWaveHeight(listening, 1f)).isGreaterThan(aeroWaveHeight(idle, 0.55f) * 2f)
    }

    @Test
    fun `the default orb is free and the Aero orb is paid`() {
        // Decisão do Yan (2026-09-29): o Aero é pago; liberado para teste até a monetização (UNL-04).
        assertThat(OrbStyle.ATELIE.paid).isFalse()
        assertThat(OrbStyle.AERO.paid).isTrue()
    }
}
