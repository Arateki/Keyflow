package com.keyflow.fluxos

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.FalaSource
import com.keyflow.core.ReasoningSource
import com.keyflow.core.TranscriptionSource
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.orbe.LugarDaOrbe
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * O fluxo guarda só **qual** modelo cada etapa usa; a credencial é a do cofre e da aba (FLX-46). Origem Custom significa "o
 * Custom que a aba tem": endpoint e header nunca vão para o fluxo.
 */
class FluxoSemSegredoTest {

    private val segredos = listOf(
        "SEGREDO-HEADER-1a2b3c", "SEGREDO-RACIOCINIO-4d5e6f", "SEGREDO-VISAO-7a8b9c", "SEGREDO-FALA-0d1e2f",
        "SEGREDO-SSH-SENHA-3a4b5c", "SEGREDO-SSH-CHAVE-6d7e8f",
    )

    /** Settings cheias de segredos e de endpoint, como as de quem usa a origem Custom em todas as abas. */
    private val settings = Settings(
        customEndpoint = "https://segredo.example/endpoint-1",
        customHeaderName = "X-Segredo-Header",
        customHeaderValue = segredos[0],
        customModel = "modelo-custom-transcricao",
        reasoningCustomHeaderValue = segredos[1],
        visionCustomHeaderValue = segredos[2],
        falaCustomHeaderValue = segredos[3],
        computerSshPassword = segredos[4],
        computerSshKey = segredos[5],
    )

    /** Um fluxo com todos os tipos de etapa, cada uma escolhendo a origem Custom (e uma nuvem) da própria aba. */
    private val completo = Fluxo(
        id = "a1b2c3d4",
        nome = "Completo",
        padrao = "ouvir-responder",
        etapas = listOf(
            Etapa.Ditar(ModeloRef(TranscriptionSource.CUSTOM.name, settings.customModel)),
            Etapa.Capturar,
            Etapa.Visao("Leia o texto.", ModeloRef(VisionSource.CUSTOM.name)),
            Etapa.Perguntar("Seja breve.", ModeloRef(ReasoningSource.CUSTOM.name)),
            Etapa.Agente(ModeloRef(ReasoningSource.ANTHROPIC.name, "claude-x")),
            Etapa.Falar(ModeloRef(FalaSource.CUSTOM.name)),
            Etapa.Inserir,
        ),
    )

    private val gatilhos = listOf(
        Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), "a1b2c3d4"),
        Gatilho(Superficie.Barra("b81f02"), "a1b2c3d4"),
    )

    private fun chaves(valor: Any?, saida: MutableSet<String> = linkedSetOf()): Set<String> {
        when (valor) {
            is JSONObject -> valor.keys().forEach { chave -> saida += chave; chaves(valor.opt(chave), saida) }
            is JSONArray -> (0 until valor.length()).forEach { chaves(valor.opt(it), saida) }
        }
        return saida
    }

    @Test
    fun `o JSON de um fluxo completo nao tem campo de segredo`() {
        val proibido = Regex("key|token|endpoint|header|secret|password|senha|auth", RegexOption.IGNORE_CASE)
        for (json in listOf(CodecFluxos.codificarFluxos(listOf(completo)), CodecFluxos.codificarGatilhos(gatilhos))) {
            val achados = chaves(JSONObject(json)).filter { proibido.containsMatchIn(it) }
            assertThat(achados).isEmpty()
        }
    }

    @Test
    fun `so existem no JSON os campos do esquema`() {
        val permitidos = setOf("v", "flows", "id", "name", "builtin", "steps", "type", "instruction", "system", "model", "source", "triggers", "surface", "flow")
        assertThat(permitidos.containsAll(chaves(JSONObject(CodecFluxos.codificarFluxos(listOf(completo)))))).isTrue()
        assertThat(permitidos.containsAll(chaves(JSONObject(CodecFluxos.codificarGatilhos(gatilhos))))).isTrue()
    }

    @Test
    fun `nenhum valor secreto das Settings aparece no JSON do fluxo`() {
        val json = CodecFluxos.codificarFluxos(listOf(completo)) + CodecFluxos.codificarGatilhos(gatilhos)
        for (segredo in segredos + settings.customEndpoint + settings.customHeaderName) {
            assertThat(json).doesNotContain(segredo)
        }
    }

    @Test
    fun `um campo de segredo escrito a mao no JSON e descartado ao ler e nao volta ao gravar`() {
        val sujo = """{"v":1,"flows":[{"id":"a","name":"X","apiKey":"SEGREDO-X","steps":[
            {"type":"perguntar","token":"SEGREDO-Y","endpoint":"https://x","headers":{"Authorization":"SEGREDO-Z"},
             "model":{"source":"CUSTOM","apiKey":"SEGREDO-W","url":"https://y"}}]}]}"""
        val lido = CodecFluxos.decodificarFluxos(sujo)!!
        val regravado = CodecFluxos.codificarFluxos(lido)
        assertThat(regravado).doesNotContain("SEGREDO")
        assertThat(regravado).doesNotContain("https")
        assertThat(lido.single().etapas).containsExactly(Etapa.Perguntar(modelo = ModeloRef("CUSTOM")))
    }
}
