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
}
