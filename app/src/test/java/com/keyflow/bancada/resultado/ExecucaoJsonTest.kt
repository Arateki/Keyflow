package com.keyflow.bancada.resultado

import com.google.common.truth.Truth.assertThat
import com.keyflow.agent.SecretFilter
import org.json.JSONObject
import org.junit.Test

/** BNC-T6 / BHS-01/02/04/05/06, BON-03: o JSON `bancada-resultado/1` vai e volta sem perda, valida, não vaza e define a comparabilidade. */
class ExecucaoJsonTest {

    @Test
    fun `an execution survives a round trip without losing anything`() {
        val original = execucaoDeExemplo()
        val volta = ExecucaoJson.ler(ExecucaoJson.escrever(original))
        assertThat(volta).isEqualTo(original)
    }

    @Test
    fun `every state and every nullable field round trips`() {
        val minima = execucaoDeExemplo(estado = EstadoExecucao.INCOMPLETA, motivo = MotivoIncompleta.RATE_LIMIT).copy(cargas = null, testes = emptyList())
        assertThat(ExecucaoJson.ler(ExecucaoJson.escrever(minima))).isEqualTo(minima)
        val semNada = execucaoDeExemplo(estado = EstadoExecucao.EM_ANDAMENTO).let {
            it.copy(aparelho = it.aparelho.copy(soc = null), app = it.app.copy(commit = null), motor = MotorInfo("openai", null), modelo = it.modelo.copy(sha256 = null, threads = null))
        }
        assertThat(ExecucaoJson.ler(ExecucaoJson.escrever(semNada))).isEqualTo(semNada)
    }

    @Test
    fun `the written json validates against the schema`() {
        assertThat(ExecucaoJson.validar(ExecucaoJson.escrever(execucaoDeExemplo()))).isEmpty()
        assertThat(JSONObject(ExecucaoJson.escrever(execucaoDeExemplo())).getString("esquema")).isEqualTo("bancada-resultado/1")
    }

    @Test
    fun `the validator names what is wrong`() {
        val bom = JSONObject(ExecucaoJson.escrever(execucaoDeExemplo()))
        fun problemas(editar: JSONObject.() -> Unit) = ExecucaoJson.validar(JSONObject(bom.toString()).apply(editar).toString()).joinToString()

        assertThat(problemas { put("esquema", "outro/1") }).contains("esquema")
        assertThat(problemas { remove("corpus") }).contains("corpus: ausente")
        assertThat(problemas { put("estado", "talvez") }).contains("estado: valor desconhecido")
        assertThat(problemas { getJSONObject("aparelho").put("ramTotalMb", "muita") }).contains("aparelho.ramTotalMb")
        assertThat(problemas { getJSONObject("modelo").remove("pensamento") }).contains("modelo.pensamento: ausente")
        assertThat(problemas { getJSONArray("testes").getJSONObject(0).put("nota", "boa") }).contains("testes[0].nota")
        assertThat(problemas { getJSONArray("testes").getJSONObject(0).getJSONArray("casos").getJSONObject(0).put("estado", "MAIS_OU_MENOS") }).contains("estado: valor desconhecido")
        assertThat(ExecucaoJson.validar("isto não é json")).isNotEmpty()
        val erro = runCatching { ExecucaoJson.ler("{}") }.exceptionOrNull()
        assertThat(erro).isInstanceOf(ExecucaoInvalidaException::class.java)
    }

    @Test
    fun `a fake api key in the environment never reaches the serialized run`() {
        val chave = "chave-falsa-de-api-0123456789"
        val filtro = SecretFilter { listOf(chave) }
        val execucao = execucaoDeExemplo(saida = "claro, use a chave $chave ou sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789")
        val texto = ExecucaoJson.escrever(execucao) { filtro.redact(it) }
        assertThat(texto).doesNotContain(chave)
        assertThat(texto).doesNotContain("sk-ant-api03")
        assertThat(texto).contains(SecretFilter.MARKER)
        // sem filtro, o texto passaria — o filtro é o que protege
        assertThat(ExecucaoJson.escrever(execucao)).contains(chave)
        // o motivo de um caso também passa pelo filtro
        val comMotivo = execucao.copy(testes = execucao.testes.map { t -> t.copy(casos = t.casos.map { it.copy(motivo = "falhou com Bearer abcdefghijklmnopqrstuvwxyz0123456789") }) })
        assertThat(ExecucaoJson.escrever(comMotivo) { filtro.redact(it) }).doesNotContain("abcdefghijklmnopqrstuvwxyz0123456789")
    }

    @Test
    fun `nothing in the model can hold a device or person identifier`() {
        val exatos = setOf("serial", "imei", "androidid", "email", "senha", "conta", "account")
        val contidos = listOf("accesstoken", "refreshtoken", "secret", "apikey", "authorization", "header", "cabecalho")
        val campos = mutableSetOf<String>()
        fun coletar(o: JSONObject) {
            for (k in o.keys()) {
                campos += k.lowercase()
                val v = o.get(k)
                if (v is JSONObject) coletar(v)
                if (v is org.json.JSONArray) for (i in 0 until v.length()) (v.opt(i) as? JSONObject)?.let(::coletar)
            }
        }
        coletar(JSONObject(ExecucaoJson.escrever(execucaoDeExemplo())))
        assertThat(campos.intersect(exatos)).isEmpty()
        for (proibido in contidos) assertThat(campos.filter { proibido in it }).isEmpty()
    }

    @Test
    fun `the summary drops the model outputs and the per-case criteria`() {
        val resumo = execucaoDeExemplo().semSaidas()
        val texto = ExecucaoJson.escrever(resumo)
        assertThat(texto).doesNotContain("Oi, pessoal, tudo bem?")
        assertThat(resumo.testes.single().casos.all { it.saida == null && it.criterios.isEmpty() }).isTrue()
        assertThat(resumo.testes.single().nota).isEqualTo(0.81)
        assertThat(ExecucaoJson.validar(texto)).isEmpty()
    }

    // ── agregação ────────────────────────────────────────────────────────────────────────

    @Test
    fun `aggregation counts only ok and failed cases in the grade and takes the median of the times`() {
        val casos = listOf(
            ResultadoCaso("a", EstadoCaso.OK, nota = 1.0, primeiroTrechoMs = 100, totalMs = 1000, tokensPorSegundo = 10.0),
            ResultadoCaso("b", EstadoCaso.OK, nota = 0.5, primeiroTrechoMs = 300, totalMs = 3000, tokensPorSegundo = 20.0),
            ResultadoCaso("c", EstadoCaso.FALHA, causa = "OOM", nota = 0.0, primeiroTrechoMs = 5, totalMs = 5),
            ResultadoCaso("d", EstadoCaso.INVALIDO, causa = "REDE"),
            ResultadoCaso("e", EstadoCaso.NAO_SE_APLICA),
            ResultadoCaso("f", EstadoCaso.OK, nota = 0.9, primeiroTrechoMs = 200, totalMs = 2000, tokensPorSegundo = 30.0),
        )
        val t = ResultadoTeste.agregar("json", 1, false, casos)
        assertThat(t.n).isEqualTo(4)
        assertThat(t.nota).isWithin(1e-9).of((1.0 + 0.5 + 0.0 + 0.9) / 4)
        assertThat(t.invalidos).isEqualTo(1)
        assertThat(t.naoSeAplica).isEqualTo(1)
        assertThat(t.primeiroTrechoMsP50).isEqualTo(200) // dos casos OK: 100, 200, 300
        assertThat(t.totalMsP50).isEqualTo(2000)
        assertThat(t.tokensPorSegundoMedia).isWithin(1e-9).of(20.0)
        assertThat(ResultadoTeste.agregar("json", 1, false, emptyList()).nota).isNull()
    }

    // ── chave de comparabilidade e histórico ─────────────────────────────────────────────

    @Test
    fun `the key ignores device and engine but not corpus, test version, profile, options, backend or thinking`() {
        val base = execucaoDeExemplo()
        val chave = base.chaveDe("pontuacao")!!
        val outroAparelhoEMotor = base.copy(aparelho = base.aparelho.copy(modelo = "SM-X"), motor = MotorInfo("litert-lm", "0.18.0"), id = "outro")
        assertThat(outroAparelhoEMotor.chaveDe("pontuacao")).isEqualTo(chave)

        assertThat(execucaoDeExemplo(corpus = 2).chaveDe("pontuacao")!!.diferenca(chave)).isEqualTo("corpus")
        assertThat(execucaoDeExemplo(versaoTeste = 2).chaveDe("pontuacao")!!.diferenca(chave)).isEqualTo("versaoTeste")
        assertThat(execucaoDeExemplo(perfil = "fumaca").chaveDe("pontuacao")!!.diferenca(chave)).isEqualTo("perfil")
        assertThat(execucaoDeExemplo(aplicadas = emptyMap()).chaveDe("pontuacao")!!.diferenca(chave)).isEqualTo("opcoes")
        assertThat(execucaoDeExemplo(backend = "GPU").chaveDe("pontuacao")!!.diferenca(chave)).isEqualTo("backend")
        assertThat(execucaoDeExemplo(pensamento = true).chaveDe("pontuacao")!!.diferenca(chave)).isEqualTo("pensamento")
        assertThat(chave.diferenca(chave)).isNull()
        assertThat(base.chaveDe("json")).isNull()
    }

    @Test
    fun `option order does not change the key`() {
        val a = execucaoDeExemplo(aplicadas = linkedMapOf("temperature" to "0.0", "seed" to "42")).chaveDe("pontuacao")
        val b = execucaoDeExemplo(aplicadas = linkedMapOf("seed" to "42", "temperature" to "0.0")).chaveDe("pontuacao")
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `the variation compares only with the most recent comparable complete run`() {
        val atual = execucaoDeExemplo(id = "atual", nota = 0.9)
        val anteriores = listOf(
            execucaoDeExemplo(id = "incompleta", estado = EstadoExecucao.INCOMPLETA, nota = 0.1),
            execucaoDeExemplo(id = "outro-corpus", corpus = 2, nota = 0.2),
            execucaoDeExemplo(id = "comparavel", nota = 0.8),
            execucaoDeExemplo(id = "mais-antiga", nota = 0.3),
        )
        val v = Historico.variacao(atual, "pontuacao", anteriores)!!
        assertThat(v.anterior.nota).isEqualTo(0.8)
        assertThat(v.delta).isWithin(1e-9).of(0.1)
        assertThat(Historico.variacao(atual, "pontuacao", anteriores.take(2))).isNull()
        assertThat(Historico.variacao(atual, "json", anteriores)).isNull()
    }

    @Test
    fun `time helpers round trip`() {
        assertThat(isoUtc(1_793_196_191_000L)).isEqualTo("2026-10-28T14:03:11Z")
        assertThat(epochDeIso("2026-10-28T14:03:11Z")).isEqualTo(1_793_196_191_000L)
        assertThat(epochDeIso("ontem")).isNull()
    }
}
