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
    fun `a pack skin id picks the pack style and an unsafe one does not`() {
        assertThat(orbStyleFor("pack:neon-noturno-3fa91c2b")).isEqualTo(OrbStyle.PACK)
        // Um `pack:` que não é um id seguro de pasta é lixo: cai no Ateliê, não no estilo PACK.
        assertThat(orbStyleFor("pack:../../x")).isEqualTo(OrbStyle.ATELIE)
        assertThat(orbStyleFor("pack:")).isEqualTo(OrbStyle.ATELIE)
        // O id do próprio estilo ("pack", sem dois-pontos) continua dando a volta completa.
        assertThat(orbStyleFor(OrbStyle.PACK.id)).isEqualTo(OrbStyle.PACK)
    }

    @Test
    fun `the pack orb style is never paid and the built in styles keep their ids and prices`() {
        // D4: a skin do usuário nunca fica atrás de `skinsUnlocked`. E nenhuma skin de hoje mudou de id nem de preço.
        assertThat(OrbStyle.PACK.paid).isFalse()
        assertThat(OrbStyle.entries.filter { it != OrbStyle.PACK }.map { it.id to it.paid }).containsExactly(
            "atelie" to false, "aero" to true, "glitch" to true, "rosegold" to true,
        ).inOrder()
    }

    @Test
    fun `the app orb states map one to one to the pack states`() {
        assertThat(OrbState.entries.map { it.toPackState().key }).containsExactly("idle", "listening", "paused", "transcribing").inOrder()
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

    @Test
    fun `glitch is a paid orb style that orbStyleFor finds by id`() {
        assertThat(orbStyleFor("glitch")).isEqualTo(OrbStyle.GLITCH)
        assertThat(OrbStyle.GLITCH.paid).isTrue()
    }

    @Test
    fun `every state has glitch bursts that peak at 1 and calm down between them`() {
        for (state in OrbState.entries) {
            // O instante do pico (2º ciclo) é o máximo da rajada.
            assertThat(glitchIntensity(glitchBurstMidMs(state), state)).isWithin(0.001f).of(1f)
            // Fora da rajada e das micro-rajadas há calma: o início do ciclo (t = período) não tem falha.
            assertThat(glitchIntensity(glitchPeriodMs(state) * 2f + 5f, state)).isAtMost(0.45f)
            // Sempre entre 0 e 1.
            for (t in 0..6000 step 37) assertThat(glitchIntensity(t.toFloat(), state)).isIn(com.google.common.collect.Range.closed(0f, 1f))
        }
    }

    @Test
    fun `glitch is calmer at rest and busier while transcribing`() {
        assertThat(glitchPeriodMs(OrbState.Idle)).isGreaterThan(glitchPeriodMs(OrbState.Listening))
        assertThat(glitchPeriodMs(OrbState.Listening)).isGreaterThan(glitchPeriodMs(OrbState.Transcribing))
        assertThat(glitchPeriodMs(OrbState.Paused)).isEqualTo(glitchPeriodMs(OrbState.Listening))
    }

    @Test
    fun `glitch is deterministic in time so a frozen clock gives a fixed frame`() {
        for (state in OrbState.entries) {
            assertThat(glitchIntensity(1234f, state)).isEqualTo(glitchIntensity(1234f, state))
        }
    }

    @Test
    fun `rose gold is a paid orb style that orbStyleFor finds by id`() {
        assertThat(orbStyleFor("rosegold")).isEqualTo(OrbStyle.ROSE_GOLD)
        assertThat(OrbStyle.ROSE_GOLD.paid).isTrue()
        // Todo id de estilo volta ao próprio estilo (e um id desconhecido é o Ateliê).
        for (style in OrbStyle.entries) assertThat(orbStyleFor(style.id)).isEqualTo(style)
        assertThat(orbStyleFor("nao-existe")).isEqualTo(OrbStyle.ATELIE)
    }

    @Test
    fun `mascot is a paid orb style that orbStyleFor finds by id`() {
        assertThat(orbStyleFor("mascot")).isEqualTo(OrbStyle.MASCOT)
        assertThat(OrbStyle.MASCOT.paid).isTrue()
        assertThat(OrbStyle.MASCOT.id).isEqualTo("mascot")
        // Todo id de estilo continua voltando ao próprio estilo, o novo incluído.
        for (style in OrbStyle.entries) assertThat(orbStyleFor(style.id)).isEqualTo(style)
    }
}
