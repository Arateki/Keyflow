package com.keyflow.notificacao

import com.google.common.truth.Truth.assertThat
import com.keyflow.ui.orb.OrbStyle
import org.json.JSONObject
import org.junit.Test

/** NTF-T3: o codec tolerante da configuração da notificação (NTF-36..39). */
class CodecNotificacaoTest {

    @Test
    fun `chave ausente ou JSON malformado dao os padroes`() {
        val padrao = ConfigNotificacao()
        assertThat(CodecNotificacao.decodificar(null)).isEqualTo(padrao)
        assertThat(CodecNotificacao.decodificar("")).isEqualTo(padrao)
        assertThat(CodecNotificacao.decodificar("{")).isEqualTo(padrao)
        assertThat(CodecNotificacao.decodificar("[1,2]")).isEqualTo(padrao)
        assertThat(CodecNotificacao.decodificar("\"x\"")).isEqualTo(padrao)
        assertThat(CodecNotificacao.decodificar("{}")).isEqualTo(padrao)
    }

    @Test
    fun `o padrao do 1 toque e gravar e enviar e o JSON padrao bate com o design`() {
        val json = JSONObject(CodecNotificacao.codificar(ConfigNotificacao()))
        assertThat(json.getInt("v")).isEqualTo(1)
        assertThat(json.getBoolean("show")).isTrue()
        assertThat(json.getBoolean("lockscreen")).isFalse()
        assertThat(json.getBoolean("copy")).isTrue()
        assertThat(json.getString("skin")).isEqualTo("atelie")
        val taps = json.getJSONObject("taps")
        assertThat(taps.getString("1")).isEqualTo("record_toggle")
        assertThat(taps.getString("2")).isEqualTo("toggle_keyboard")
        assertThat(taps.getString("3")).isEqualTo("switch_ime")
    }

    @Test
    fun `taps 1 ausente usa record_toggle mas none e desconhecida explicitos permanecem`() {
        assertThat(CodecNotificacao.decodificar("""{"v":1,"taps":{"2":"vision"}}""").acao1Toque).isEqualTo(AcaoDaOrbe.GravarEnviar)
        assertThat(CodecNotificacao.decodificar("""{"v":1,"taps":{}}""").acao1Toque).isEqualTo(AcaoDaOrbe.GravarEnviar)
        assertThat(CodecNotificacao.decodificar("""{"v":1,"taps":{"1":"none"}}""").acao1Toque).isEqualTo(AcaoDaOrbe.Nenhuma)
        assertThat(CodecNotificacao.decodificar("""{"v":1,"taps":{"1":"novo"}}""").acao1Toque).isEqualTo(AcaoDaOrbe.Desconhecida("novo"))
        assertThat(CodecNotificacao.decodificar("""{"v":1,"taps":{"1":7}}""").acao1Toque).isEqualTo(AcaoDaOrbe.GravarEnviar)
    }

    @Test
    fun `zero ou varios record_toggle fazem round-trip`() {
        val zero = ConfigNotificacao(acao1Toque = AcaoDaOrbe.Nenhuma, acao2Toques = AcaoDaOrbe.LerTela, acao3Toques = AcaoDaOrbe.Nenhuma)
        assertThat(CodecNotificacao.decodificar(CodecNotificacao.codificar(zero))).isEqualTo(zero)
        val varios = ConfigNotificacao(acao1Toque = AcaoDaOrbe.GravarEnviar, acao2Toques = AcaoDaOrbe.GravarEnviar, acao3Toques = AcaoDaOrbe.GravarEnviar)
        assertThat(CodecNotificacao.decodificar(CodecNotificacao.codificar(varios))).isEqualTo(varios)
    }

    @Test
    fun `round-trip com 1 toque remapeado, skin de pacote, fluxo e acao desconhecida preservada`() {
        val c = ConfigNotificacao(
            mostrar = false, permitirComTelaBloqueada = true, copiarDaOrbePrincipal = false, skin = "pack:neon-1a2b3c4d",
            acao1Toque = AcaoDaOrbe.RetrairExpandirTeclado, acao2Toques = AcaoDaOrbe.RodarFluxo("f1"), acao3Toques = AcaoDaOrbe.Desconhecida("novo"),
        )
        val json = CodecNotificacao.codificar(c)
        assertThat(CodecNotificacao.decodificar(json)).isEqualTo(c)
        assertThat(JSONObject(json).getJSONObject("taps").getString("2")).isEqualTo("flow:f1")
        assertThat(JSONObject(json).getJSONObject("taps").getString("3")).isEqualTo("novo")
        assertThat(CodecNotificacao.decodificar("""{"taps":{"3":"flow:x"}}""").acao3Toques).isEqualTo(AcaoDaOrbe.RodarFluxo("x"))
    }

    @Test
    fun `campo desconhecido e toque fora de 1 a 3 sao ignorados`() {
        val c = CodecNotificacao.decodificar("""{"v":9,"futuro":{"a":1},"show":false,"taps":{"0":"vision","4":"vision","1":"vision"}}""")
        assertThat(c).isEqualTo(ConfigNotificacao(mostrar = false, acao1Toque = AcaoDaOrbe.LerTela))
        assertThat(JSONObject(CodecNotificacao.codificar(c)).getJSONObject("taps").keys().asSequence().toList()).containsExactly("1", "2", "3")
    }

    @Test
    fun `skin inexistente ou insegura vira atelie, sistema e as pagas permanecem`() {
        for (ruim in listOf("nao-existe", "pack:../x", "pack:", "", "PACK:x")) {
            assertThat(CodecNotificacao.decodificar("""{"skin":${JSONObject.quote(ruim)}}""").skin).isEqualTo("atelie")
        }
        for (boa in listOf("sistema", "aero", "glitch", "rosegold", "mascot", "pack:ok")) {
            assertThat(CodecNotificacao.decodificar("""{"skin":"$boa"}""").skin).isEqualTo(boa)
        }
        assertThat(CodecNotificacao.decodificar("""{"skin":3}""").skin).isEqualTo("atelie")
    }

    @Test
    fun `as skins conhecidas sao as do OrbStyle mais o sistema`() {
        val doOrbStyle = OrbStyle.entries.map { it.id }.filter { it != "pack" }.toSet()
        assertThat(CodecNotificacao.SKINS_CONHECIDAS).containsExactlyElementsIn(doOrbStyle + "sistema")
    }

    @Test
    fun `tipos errados caem no padrao do campo`() {
        val c = CodecNotificacao.decodificar("""{"show":"sim","lockscreen":1,"copy":null,"taps":"x"}""")
        assertThat(c).isEqualTo(ConfigNotificacao())
    }
}
