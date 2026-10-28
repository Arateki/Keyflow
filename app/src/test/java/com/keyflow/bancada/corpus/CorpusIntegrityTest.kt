package com.keyflow.bancada.corpus

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.keyflow.agent.SecretFilter
import com.keyflow.bancada.pontuacao.Pontuadores
import java.io.File
import org.json.JSONObject
import org.junit.Test

/**
 * BCP-01..07: o corpus é íntegro — hash do manifesto, licença permitida, ids únicos, esquema válido, orçamento de tamanho e nenhum segredo.
 * Roda no corpus de brinquedo (`src/test/resources/bancada/v0`, onde se prova que a conferência PEGA o defeito) e no corpus real (`v1`).
 */
class CorpusIntegrityTest {

    private val brinquedo = DiretorioFonte(File("src/test/resources/bancada/v0"))
    private val real = DiretorioFonte(File("src/main/assets/bancada/v1"))

    /** O brinquedo na memória, para adulterar um arquivo sem tocar no disco. */
    private fun brinquedoNaMemoria(): MutableMap<String, ByteArray> = brinquedo.listar().associateWith { brinquedo.ler(it)!! }.toMutableMap()

    private fun validar(arquivos: Map<String, ByteArray>, filtro: SecretFilter = SecretFilter(), orcamento: Long = CorpusValidador.ORCAMENTO_BYTES) =
        CorpusValidador.validar(MapaFonte(arquivos), filtro, orcamento)

    // ── a conferência pega o defeito ─────────────────────────────────────────────────────

    @Test
    fun `the toy corpus is intact`() {
        assertThat(CorpusValidador.validar(brinquedo)).isEmpty()
        val corpus = CorpusLoader.carregar(brinquedo)
        assertThat(corpus.versao).isEqualTo(0)
        assertThat(corpus.teste("pontuacao")!!.casos.map { it.id }).containsExactly("v0-pt-01", "v0-en-01").inOrder()
    }

    @Test
    fun `changing one byte of a file breaks the hash`() {
        val arquivos = brinquedoNaMemoria()
        val bytes = arquivos.getValue("pontuacao.json").copyOf()
        val i = bytes.indexOfFirst { it == 'O'.code.toByte() }
        bytes[i] = 'o'.code.toByte() // "Oi, tudo bem?" -> "oi, tudo bem?"
        arquivos["pontuacao.json"] = bytes
        assertThat(validar(arquivos).joinToString()).contains("SHA-256")
        val erro = runCatching { CorpusLoader.carregar(MapaFonte(arquivos)) }.exceptionOrNull()
        assertThat(erro).isInstanceOf(CorpusInvalidoException::class.java)
        assertThat(erro!!.message).contains("SHA-256")
    }

    private fun comLicenca(licenca: String): MutableMap<String, ByteArray> {
        val arquivos = brinquedoNaMemoria()
        val manifesto = JSONObject(arquivos.getValue("manifesto.json").toString(Charsets.UTF_8))
        manifesto.getJSONArray("arquivos").getJSONObject(0).put("licenca", licenca)
        arquivos["manifesto.json"] = manifesto.toString().toByteArray()
        return arquivos
    }

    @Test
    fun `a license outside the allowed list is refused`() {
        for (ruim in listOf("GPL-3.0", "AGPL-3.0", "LGPL-2.1", "CC-BY-NC-4.0", "proprietária", "")) {
            assertWithMessage(ruim).that(validar(comLicenca(ruim)).joinToString()).contains("licença")
        }
        assertThat(validar(comLicenca("CC0-1.0"))).isEmpty()
        assertThat(validar(comLicenca("Apache-2.0"))).isEmpty()
    }

    @Test
    fun `no allowed license is copyleft or non commercial`() {
        for (licenca in CorpusValidador.LICENCAS_PERMITIDAS) {
            assertThat(licenca.lowercase()).doesNotContain("gpl")
            assertThat(licenca.lowercase()).doesNotContain("-nc")
        }
    }

    @Test
    fun `a repeated case id is refused`() {
        val arquivos = brinquedoNaMemoria()
        val teste = JSONObject(arquivos.getValue("pontuacao.json").toString(Charsets.UTF_8))
        teste.getJSONArray("casos").getJSONObject(1).put("id", "v0-pt-01")
        arquivos["pontuacao.json"] = teste.toString().toByteArray()
        recalcularHash(arquivos)
        assertThat(validar(arquivos).joinToString()).contains("id de caso repetido: v0-pt-01")
    }

    private fun recalcularHash(arquivos: MutableMap<String, ByteArray>) {
        val manifesto = JSONObject(arquivos.getValue("manifesto.json").toString(Charsets.UTF_8))
        val lista = manifesto.getJSONArray("arquivos")
        for (i in 0 until lista.length()) {
            val a = lista.getJSONObject(i)
            a.put("sha256", sha256Hex(arquivos.getValue(a.getString("caminho"))))
        }
        arquivos["manifesto.json"] = manifesto.toString().toByteArray()
    }

    @Test
    fun `ids are unique across the whole corpus, not only inside a test`() {
        val arquivos = brinquedoNaMemoria()
        val outro = JSONObject(arquivos.getValue("pontuacao.json").toString(Charsets.UTF_8)).put("teste", "outro")
        arquivos["outro.json"] = outro.toString().toByteArray()
        val manifesto = JSONObject(arquivos.getValue("manifesto.json").toString(Charsets.UTF_8))
        manifesto.getJSONArray("arquivos").put(JSONObject().put("caminho", "outro.json").put("sha256", "x").put("origem", "o").put("autor", "a").put("licenca", "MIT"))
        arquivos["manifesto.json"] = manifesto.toString().toByteArray()
        recalcularHash(arquivos)
        assertThat(validar(arquivos).joinToString()).contains("id de caso repetido")
    }

    @Test
    fun `a file outside the manifest, a missing file and a blown budget are refused`() {
        val extra = brinquedoNaMemoria().apply { put("escondido.json", "{}".toByteArray()) }
        assertThat(validar(extra).joinToString()).contains("escondido.json: arquivo fora do manifesto")
        val faltando = brinquedoNaMemoria().apply { remove("pontuacao.json") }
        assertThat(validar(faltando).joinToString()).contains("pontuacao.json: arquivo do manifesto ausente")
        assertThat(validar(brinquedoNaMemoria(), orcamento = 100).joinToString()).contains("orçamento")
    }

    @Test
    fun `a secret in a case or in the prompt is refused`() {
        val arquivos = brinquedoNaMemoria()
        val teste = JSONObject(arquivos.getValue("pontuacao.json").toString(Charsets.UTF_8))
        teste.getJSONArray("casos").getJSONObject(0).put("entrada", "minha chave é sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789") // chave fake
        arquivos["pontuacao.json"] = teste.toString().toByteArray()
        recalcularHash(arquivos)
        assertThat(validar(arquivos).joinToString()).contains("SecretFilter")
        val registrado = brinquedoNaMemoria()
        val comValor = JSONObject(registrado.getValue("pontuacao.json").toString(Charsets.UTF_8))
        comValor.getJSONObject("prompt").put("system", "use a senha segredo-super-longo-123 aqui")
        registrado["pontuacao.json"] = comValor.toString().toByteArray()
        recalcularHash(registrado)
        assertThat(validar(registrado, SecretFilter { listOf("segredo-super-longo-123") }).joinToString()).contains("SecretFilter")
    }

    @Test
    fun `a prompt without the entry placeholder, an unknown language and a non positive output cap are refused`() {
        val arquivos = brinquedoNaMemoria()
        val teste = JSONObject(arquivos.getValue("pontuacao.json").toString(Charsets.UTF_8))
        teste.getJSONObject("prompt").put("user", "sem o marcador")
        teste.getJSONArray("casos").getJSONObject(0).put("idioma", "fr").put("maxTokensSaida", 0)
        arquivos["pontuacao.json"] = teste.toString().toByteArray()
        recalcularHash(arquivos)
        val problemas = validar(arquivos).joinToString("\n")
        assertThat(problemas).contains("{entrada}")
        assertThat(problemas).contains("idioma desconhecido")
        assertThat(problemas).contains("maxTokensSaida")
    }

    // ── o corpus real ────────────────────────────────────────────────────────────────────

    private val corpus by lazy { CorpusLoader.carregar(real) }

    @Test
    fun `the real corpus is intact`() {
        assertWithMessage(CorpusValidador.validar(real).joinToString("\n")).that(CorpusValidador.validar(real)).isEmpty()
        assertThat(corpus.versao).isEqualTo(1)
    }

    @Test
    fun `the real corpus has the planned number of cases per test`() {
        val previsto = mapOf("pontuacao" to 12, "reescrita" to 10, "json" to 10, "acoes" to 20, "contexto" to 12)
        assertThat(corpus.testes.mapValues { it.value.casos.size }).containsExactlyEntriesIn(previsto)
        assertThat(corpus.testes.values.map { it.capacidade }.toSet()).containsExactly(Capacidade.TEXTO)
    }

    @Test
    fun `punctuation cases cover the three languages four each`() {
        val porIdioma = corpus.teste("pontuacao")!!.casos.groupingBy { it.idioma }.eachCount()
        assertThat(porIdioma).containsExactly("pt", 4, "en", 4, "es", 4)
    }

    @Test
    fun `the real corpus fits the 3 MB budget`() {
        val total = real.listar().sumOf { real.ler(it)!!.size.toLong() }
        assertThat(total).isLessThan(CorpusValidador.ORCAMENTO_BYTES)
    }

    @Test
    fun `every test has a scorer and the reference answer scores 1`() {
        for (teste in corpus.testes.values) {
            val pontuador = Pontuadores.de(teste.id) ?: error("o teste ${teste.id} não tem pontuador")
            for (caso in teste.casos) {
                val referencia = referencia(caso)
                assertWithMessage("${caso.id}: a resposta de referência não vale 1").that(pontuador.pontuar(caso, referencia).nota).isEqualTo(1.0)
                if (!(teste.id == "acoes" && (caso.esperado == null || caso.esperado == JSONObject.NULL))) {
                    assertWithMessage("${caso.id}: saída vazia deve valer 0").that(pontuador.pontuar(caso, "").nota).isEqualTo(0.0)
                }
            }
        }
    }

    /** A saída ideal de um caso: o que o modelo perfeito responderia. */
    private fun referencia(caso: Caso): String = when (val esperado = caso.esperado) {
        null, JSONObject.NULL -> """{"recusa":"fora do que o app faz"}"""
        else -> esperado.toString()
    }

    @Test
    fun `every distractor is planted in the document and the value appears exactly where expected`() {
        for (caso in corpus.teste("contexto")!!.casos) {
            val valor = caso.texto("valor")!!
            assertWithMessage("${caso.id}: o valor não está no documento").that(caso.entrada).contains(valor)
            for (distrator in caso.lista("distratores")) {
                assertWithMessage("${caso.id}: o distrator «$distrator» não está no documento").that(caso.entrada).contains(distrator)
                assertThat(distrator).isNotEqualTo(valor)
            }
            assertThat(caso.texto("posicao")).isIn(listOf("comeco", "meio", "fim"))
        }
    }

    @Test
    fun `context documents come in the planned sizes and positions`() {
        val casos = corpus.teste("contexto")!!.casos
        assertThat(casos.groupingBy { it.texto("posicao") }.eachCount()).containsExactly("comeco", 4, "meio", 4, "fim", 4)
        val tokens = casos.map { it.tokensEntrada }
        assertThat(tokens.count { it < 700 }).isEqualTo(6) // ~300 tokens: a ata e o e-mail
        assertThat(tokens.count { it > 1_200 }).isEqualTo(6) // ~1,5 a 2 mil (estimativa): o manual e o informe
    }

    @Test
    fun `the estimated input tokens match the rendered prompt`() {
        for (teste in corpus.testes.values) {
            for (caso in teste.casos) {
                val montado = teste.montar(caso)
                val esperado = Math.ceil(((montado.system?.length ?: 0) + montado.user.length) / 4.0).toInt()
                assertWithMessage("${caso.id}: tokensEntrada desatualizado").that(caso.tokensEntrada).isEqualTo(esperado)
            }
        }
    }

    @Test
    fun `no case is hard to tell apart from another by id`() {
        val ids = corpus.testes.values.flatMap { t -> t.casos.map { it.id } }
        assertThat(ids).containsNoDuplicates()
    }
}
