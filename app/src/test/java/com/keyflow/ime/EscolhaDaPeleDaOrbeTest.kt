package com.keyflow.ime

import com.google.common.truth.Truth.assertThat
import com.keyflow.orbe.CodecOrbeTeclado
import com.keyflow.orbe.ConfigOrbeTeclado
import com.keyflow.ui.orb.OrbStyle
import org.junit.Test

/** KBO-22, KBO-23: a pele que a orbe do teclado veste. Decisão pura; o desenho é do `OrbeDoTecladoPeleTest`. */
class EscolhaDaPeleDaOrbeTest {

    @Test
    fun `sem o direito a skin paga veste o Atelie e a escolha continua gravada`() {
        for (id in listOf("aero", "glitch", "rosegold", "mascot")) {
            assertThat(EscolhaDaPeleDaOrbe.estilo(id, skinsUnlocked = false, temQuadrosDeOrbe = false)).isEqualTo(OrbStyle.ATELIE)
            val json = CodecOrbeTeclado.codificar(ConfigOrbeTeclado(copiarDaOrbePrincipal = false, skin = id))
            assertThat(CodecOrbeTeclado.decodificar(json).skin).isEqualTo(id)
        }
    }

    @Test
    fun `com o direito cada skin veste a propria pele`() {
        assertThat(EscolhaDaPeleDaOrbe.estilo("aero", true, false)).isEqualTo(OrbStyle.AERO)
        assertThat(EscolhaDaPeleDaOrbe.estilo("glitch", true, false)).isEqualTo(OrbStyle.GLITCH)
        assertThat(EscolhaDaPeleDaOrbe.estilo("rosegold", true, false)).isEqualTo(OrbStyle.ROSE_GOLD)
        assertThat(EscolhaDaPeleDaOrbe.estilo("mascot", true, false)).isEqualTo(OrbStyle.MASCOT)
        assertThat(EscolhaDaPeleDaOrbe.estilo("atelie", false, false)).isEqualTo(OrbStyle.ATELIE)
    }

    @Test
    fun `pacote nao e bloqueado e so cai no Atelie quando nao tem quadros de orbe`() {
        assertThat(EscolhaDaPeleDaOrbe.estilo("pack:meu", skinsUnlocked = false, temQuadrosDeOrbe = true)).isEqualTo(OrbStyle.PACK)
        assertThat(EscolhaDaPeleDaOrbe.estilo("pack:meu", skinsUnlocked = true, temQuadrosDeOrbe = false)).isEqualTo(OrbStyle.ATELIE)
        assertThat(EscolhaDaPeleDaOrbe.estilo("pack:meu", skinsUnlocked = false, temQuadrosDeOrbe = false)).isEqualTo(OrbStyle.ATELIE)
    }

    @Test
    fun `id desconhecido veste o Atelie`() {
        assertThat(EscolhaDaPeleDaOrbe.estilo("inexistente", true, true)).isEqualTo(OrbStyle.ATELIE)
    }
}
