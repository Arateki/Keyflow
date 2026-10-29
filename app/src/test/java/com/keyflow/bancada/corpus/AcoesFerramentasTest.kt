package com.keyflow.bancada.corpus

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.keyflow.agent.ParsedArgs
import com.keyflow.bancada.pontuacao.Canonico
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * BCP-05: o teste `acoes` deriva das ferramentas REAIS do agente. Toda ferramenta do `ToolRegistry` tem ≥ 1 caso; todo caso cita uma
 * ferramenta que existe e com argumentos que o validador DE VERDADE aceita; e a lista de ferramentas que vai no prompt é a que o app
 * monta hoje (nome, descrição e esquema). Se o registro mudar, o teste diz como regerar o corpus.
 */
class AcoesFerramentasTest {

    private val registro = RegistroDeProducao.criar()
    private val corpus = CorpusLoader.carregar(DiretorioFonte(File("src/main/assets/bancada/v1")))
    private val acoes = corpus.teste("acoes")!!

    /** As definições vivas, no formato do prompt: `[{name, description, parameters}]`. */
    private fun definicoesVivas(): JSONArray = JSONArray().also { lista ->
        for (tool in registro.all()) {
            lista.put(JSONObject().put("name", tool.name).put("description", tool.description).put("parameters", JSONObject(tool.inputSchema.toString())))
        }
    }

    private fun canonicoDe(lista: JSONArray): List<String> = (0 until lista.length()).map { Canonico.de(lista.getJSONObject(it)) }

    @Test
    fun `dumps the live tool definitions for the corpus generator`() {
        val destino = File("build/bancada/ferramentas-reais.json")
        destino.parentFile.mkdirs()
        destino.writeText(definicoesVivas().toString(2))
        assertThat(destino.length()).isGreaterThan(1000L)
    }

    @Test
    fun `every registered tool has at least one case`() {
        val citadas = acoes.casos.mapNotNull { (it.esperado as? JSONObject)?.getString("nome") }.toSet()
        val faltam = registro.all().map { it.name }.filter { it !in citadas }
        assertWithMessage("ferramentas sem caso no acoes: $faltam").that(faltam).isEmpty()
    }

    @Test
    fun `every case cites an existing tool with arguments the real validator accepts`() {
        for (caso in acoes.casos) {
            val esperado = caso.esperado as? JSONObject ?: continue
            val nome = esperado.getString("nome")
            val tool = registro[nome]
            assertWithMessage("${caso.id} cita a ferramenta $nome, que não existe").that(tool).isNotNull()
            val resultado = registro.parseArgs(tool!!, (esperado.optJSONObject("args") ?: JSONObject()).toString())
            assertWithMessage("${caso.id}: argumentos recusados pelo esquema real: ${(resultado as? ParsedArgs.Rejected)?.reason}")
                .that(resultado).isInstanceOf(ParsedArgs.Valid::class.java)
        }
    }

    @Test
    fun `at least four cases expect a refusal`() {
        val recusas = acoes.casos.count { it.esperado == null || it.esperado == JSONObject.NULL }
        assertThat(recusas).isAtLeast(4)
    }

    @Test
    fun `the tools in the prompt are the ones the app sends`() {
        val snapshot = JSONArray(acoes.ferramentas)
        assertWithMessage(
            "As ferramentas do acoes.json não são as que o app monta hoje. Regere com:\n" +
                "  python3 scripts/bancada-corpus.py --ferramentas app/build/bancada/ferramentas-reais.json\n" +
                "(rode este teste antes, ele grava o arquivo) e suba a `versao` do teste `acoes` (BCP-06).",
        ).that(canonicoDe(snapshot)).isEqualTo(canonicoDe(definicoesVivas()))
    }
}
