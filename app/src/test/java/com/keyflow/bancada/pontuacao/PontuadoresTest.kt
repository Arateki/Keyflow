package com.keyflow.bancada.pontuacao

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * BPT-02..07/11: cada pontuador com a tabela de casos de borda e a nota esperada escrita à mão (saída vazia, prefixo "Claro!", número
 * inventado, JSON com texto em volta, campo extra e faltante, cerca de código, recusa, duas chamadas…). A mesma saída pontuada 2× dá a mesma nota.
 */
class PontuadoresTest {

    private fun assertNota(p: Pontuacao, esperada: Double) = assertThat(p.nota).isWithin(1e-9).of(esperada)

    // ── pontuacao ────────────────────────────────────────────────────────────────────────

    private val ditado = caso(
        entrada = "oi pessoal tudo bem eu queria confirmar a reunião de amanhã às duas da tarde",
        esperado = "Oi, pessoal, tudo bem? Eu queria confirmar a reunião de amanhã às duas da tarde.",
    )

    @Test
    fun `pontuacao - identical to the expected is 1`() {
        val p = PontuadorPontuacao.pontuar(ditado, ditado.esperado as String)
        assertNota(p, 1.0)
        assertThat(p.marcas).isEmpty()
    }

    @Test
    fun `pontuacao - empty output is 0`() {
        val p = PontuadorPontuacao.pontuar(ditado, "  \n")
        assertNota(p, 0.0)
        assertThat(p.marcas).containsExactly(Marca.VAZIA)
    }

    @Test
    fun `pontuacao - the raw dictation keeps the words but has no punctuation nor capitals`() {
        // preservação 1; sem sinais (F1 0) e sem maiúsculas (F1 0): 0,5*1 + 0,25*0 + 0,25*0
        assertNota(PontuadorPontuacao.pontuar(ditado, ditado.entrada), 0.5)
    }

    @Test
    fun `pontuacao - a chatty prefix costs one inserted word, one sign and one capital`() {
        val p = PontuadorPontuacao.pontuar(ditado, "Claro! Oi, pessoal, tudo bem? Eu queria confirmar a reunião de amanhã às duas da tarde.")
        // preservação 14/15 (1 inserção em 15 palavras); sinais: 4 certos de 5 obtidos e 4 esperados -> F1 8/9; maiúsculas: 2 de 3 obtidas -> F1 4/5
        assertNota(p, 0.5 * (14.0 / 15) + 0.25 * (8.0 / 9) + 0.25 * 0.8)
        assertThat(p.marcas).containsExactly(Marca.PREFIXO_DE_CONVERSA)
    }

    @Test
    fun `pontuacao - a changed word hurts preservation but not the signs after the others`() {
        val p = PontuadorPontuacao.pontuar(ditado, "Oi, pessoal, tudo bem? Eu queria confirmar a reunião de hoje às duas da tarde.")
        // 1 substituição em 15 palavras
        assertThat(p.criterios[PontuadorPontuacao.PRESERVACAO]).isWithin(1e-9).of(14.0 / 15)
        assertThat(p.criterios[PontuadorPontuacao.PONTUACAO]).isEqualTo(1.0)
        assertThat(p.criterios[PontuadorPontuacao.MAIUSCULAS]).isEqualTo(1.0)
    }

    // ── reescrita ────────────────────────────────────────────────────────────────────────

    private val reescrita = caso(
        entrada = "Comando: encurte.\n\nTexto: A reunião é sexta-feira às 10h na sala 3 com o João e a equipe.",
        esperado = "Reunião sexta-feira às 10h, sala 3, com o João.",
        campos = arrayOf("fatosObrigatorios" to lista("sexta-feira", "10h", "sala 3"), "maxPalavras" to 12, "proibidos" to lista("blz")),
    )

    @Test
    fun `reescrita - a faithful short rewrite is 1`() {
        val p = PontuadorReescrita.pontuar(reescrita, "Reunião sexta-feira às 10h, sala 3, com o João.")
        assertNota(p, 1.0)
        assertThat(p.marcas).isEmpty()
    }

    @Test
    fun `reescrita - an invented number costs a fact and the no-invention criterion`() {
        val p = PontuadorReescrita.pontuar(reescrita, "Reunião sexta-feira às 11h, sala 3, com o João.")
        // fatos 2/3; limite 1; invenção: de {11h, 3, João} só 11h falha -> 2/3; idioma 1
        assertNota(p, 0.4 * (2.0 / 3) + 0.2 + 0.2 * (2.0 / 3) + 0.2)
        assertThat(p.marcas).containsExactly(Marca.INVENCAO)
    }

    @Test
    fun `reescrita - an invented proper name is caught`() {
        val p = PontuadorReescrita.pontuar(reescrita, "Reunião sexta-feira às 10h, sala 3, com o Carlos.")
        assertThat(p.criterios[PontuadorReescrita.SEM_INVENCAO]).isWithin(1e-9).of(2.0 / 3)
        assertThat(p.marcas).contains(Marca.INVENCAO)
    }

    @Test
    fun `reescrita - too long or a forbidden term breaks the limit criterion`() {
        val longo = PontuadorReescrita.pontuar(reescrita, "Reunião sexta-feira às 10h, sala 3, com o João e com toda a equipe de planejamento do projeto.")
        assertThat(longo.criterios[PontuadorReescrita.LIMITE]).isWithin(1e-9).of(0.5) // tamanho falha, termo proibido não
        assertThat(longo.marcas).contains(Marca.FORA_DO_LIMITE)
        val giria = PontuadorReescrita.pontuar(reescrita, "Reunião sexta-feira às 10h, sala 3, com o João, blz.")
        assertThat(giria.criterios[PontuadorReescrita.LIMITE]).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `reescrita - the wrong language costs the language criterion`() {
        val p = PontuadorReescrita.pontuar(reescrita, "The meeting is on friday at 10h, room 3, with João and the team.")
        assertThat(p.criterios[PontuadorReescrita.IDIOMA]).isEqualTo(0.0)
        assertThat(p.marcas).contains(Marca.IDIOMA_ERRADO)
    }

    @Test
    fun `reescrita - a chatty prefix keeps its sentence-start capitals and is not an invention`() {
        val p = PontuadorReescrita.pontuar(reescrita, "Claro! Reunião sexta-feira às 10h, sala 3, com o João.")
        assertThat(p.criterios[PontuadorReescrita.SEM_INVENCAO]).isEqualTo(1.0)
    }

    @Test
    fun `reescrita - empty is 0`() {
        val p = PontuadorReescrita.pontuar(reescrita, "")
        assertNota(p, 0.0)
        assertThat(p.marcas).containsExactly(Marca.VAZIA)
    }

    // ── json ─────────────────────────────────────────────────────────────────────────────

    private val extracao = caso(
        esperado = obj("""{"nome":"Ana","idade":30,"ativo":true,"entrega":"2026-10-29"}"""),
        campos = arrayOf("esquema" to obj("""{"nome":"string","idade":"integer","ativo":"boolean","entrega":"date"}""")),
    )
    private val perfeito = """{"nome": "Ana", "idade": 30, "ativo": true, "entrega": "2026-10-29"}"""

    @Test
    fun `json - the exact object is 1`() {
        val p = PontuadorJson.pontuar(extracao, perfeito)
        assertNota(p, 1.0)
        assertThat(p.marcas).isEmpty()
    }

    @Test
    fun `json - a code fence is accepted and marked`() {
        val p = PontuadorJson.pontuar(extracao, "```json\n$perfeito\n```")
        assertNota(p, 1.0)
        assertThat(p.marcas).containsExactly(Marca.CERCA_DE_CODIGO)
    }

    @Test
    fun `json - text around the object halves the validity criterion`() {
        val p = PontuadorJson.pontuar(extracao, "Claro! Aqui está: $perfeito")
        assertNota(p, 0.2 * 0.5 + 0.2 + 0.6)
        assertThat(p.marcas).containsExactly(Marca.TEXTO_EM_VOLTA)
    }

    @Test
    fun `json - a missing field weighs on schema and fields`() {
        val p = PontuadorJson.pontuar(extracao, """{"nome": "Ana", "ativo": true, "entrega": "2026-10-29"}""")
        assertNota(p, 0.2 + 0.2 * 0.75 + 0.6 * 0.75)
    }

    @Test
    fun `json - an extra field costs nothing`() {
        assertNota(PontuadorJson.pontuar(extracao, perfeito.dropLast(1) + """, "extra": "x"}"""), 1.0)
    }

    @Test
    fun `json - a number as text fails the schema but the normalized value still counts`() {
        val p = PontuadorJson.pontuar(extracao, """{"nome": "Ana", "idade": "30", "ativo": true, "entrega": "2026-10-29"}""")
        assertNota(p, 0.2 + 0.2 * 0.75 + 0.6)
    }

    @Test
    fun `json - dates are normalized and an impossible date is not a date`() {
        assertNota(PontuadorJson.pontuar(extracao, """{"nome": "Ana", "idade": 30, "ativo": true, "entrega": "29/10/2026"}"""), 1.0)
        val impossivel = PontuadorJson.pontuar(extracao, """{"nome": "Ana", "idade": 30, "ativo": true, "entrega": "31/02/2026"}""")
        assertNota(impossivel, 0.2 + 0.2 * 0.75 + 0.6 * 0.75)
    }

    @Test
    fun `json - unreadable, array, and empty outputs are 0`() {
        assertThat(PontuadorJson.pontuar(extracao, """{nome: Ana""").marcas).containsExactly(Marca.JSON_ILEGIVEL)
        assertNota(PontuadorJson.pontuar(extracao, """{nome: Ana"""), 0.0)
        assertNota(PontuadorJson.pontuar(extracao, """[{"nome": "Ana"}]"""), 0.0)
        assertNota(PontuadorJson.pontuar(extracao, ""), 0.0)
    }

    @Test
    fun `json - null must be answered, not omitted`() {
        val c = caso(esperado = obj("""{"nome":"Rafael","telefone":null}"""), campos = arrayOf("esquema" to obj("""{"nome":"string","telefone":"string?"}""")))
        assertNota(PontuadorJson.pontuar(c, """{"nome": "Rafael", "telefone": null}"""), 1.0)
        assertNota(PontuadorJson.pontuar(c, """{"nome": "Rafael"}"""), 0.2 + 0.2 * 0.5 + 0.6 * 0.5)
        assertNota(PontuadorJson.pontuar(c, """{"nome": "Rafael", "telefone": "123"}"""), 0.2 + 0.2 + 0.6 * 0.5)
    }

    // ── acoes ────────────────────────────────────────────────────────────────────────────

    private val acao = caso(esperado = obj("""{"nome":"settings_set","args":{"key":"themeDark","value":"true"}}"""))
    private val recusa = caso(esperado = null)

    @Test
    fun `acoes - the exact call is 1, even with other key order, spaces and a fence`() {
        assertNota(PontuadorAcoes.pontuar(acao, """{"nome":"settings_set","args":{"key":"themeDark","value":"true"}}"""), 1.0)
        assertNota(PontuadorAcoes.pontuar(acao, """{ "args": { "value": "true", "key": "themeDark" }, "nome": "settings_set" }"""), 1.0)
        val cercado = PontuadorAcoes.pontuar(acao, "```json\n{\"nome\":\"settings_set\",\"args\":{\"key\":\"themeDark\",\"value\":\"true\"}}\n```")
        assertNota(cercado, 1.0)
        assertThat(cercado.marcas).containsExactly(Marca.CERCA_DE_CODIGO)
    }

    @Test
    fun `acoes - the right tool with a swapped argument keeps only the tool part`() {
        val p = PontuadorAcoes.pontuar(acao, """{"nome":"settings_set","args":{"key":"themeDark","value":"false"}}""")
        assertNota(p, 0.4)
        assertThat(p.marcas).containsExactly(Marca.ARGUMENTOS_ERRADOS)
    }

    @Test
    fun `acoes - the wrong tool is 0 even if the arguments look right`() {
        val p = PontuadorAcoes.pontuar(acao, """{"nome":"settings_get","args":{"key":"themeDark","value":"true"}}""")
        assertNota(p, 0.0)
        assertThat(p.marcas).containsExactly(Marca.FERRAMENTA_ERRADA)
    }

    @Test
    fun `acoes - numbers are canonical, 1 and 1 point 0 are the same argument`() {
        val c = caso(esperado = obj("""{"nome":"t","args":{"n":1}}"""))
        assertNota(PontuadorAcoes.pontuar(c, """{"nome":"t","args":{"n":1.0}}"""), 1.0)
        assertNota(PontuadorAcoes.pontuar(c, """{"nome":"t","args":{"n":"1"}}"""), 0.4)
    }

    @Test
    fun `acoes - two calls are 0`() {
        val duas = """{"nome":"settings_set","args":{"key":"themeDark","value":"true"}} {"nome":"app_status","args":{}}"""
        assertThat(PontuadorAcoes.pontuar(acao, duas).marcas).contains(Marca.MULTIPLAS_CHAMADAS)
        assertNota(PontuadorAcoes.pontuar(acao, duas), 0.0)
        assertNota(PontuadorAcoes.pontuar(acao, """[{"nome":"settings_set","args":{"key":"themeDark","value":"true"}},{"nome":"app_status","args":{}}]"""), 0.0)
    }

    @Test
    fun `acoes - refusing a case that has a tool is 0, unreadable is 0, empty is 0`() {
        assertThat(PontuadorAcoes.pontuar(acao, """{"recusa":"não posso"}""").marcas).containsExactly(Marca.RECUSA_INDEVIDA)
        assertNota(PontuadorAcoes.pontuar(acao, "Vou ativar o tema escuro para você."), 0.0)
        assertThat(PontuadorAcoes.pontuar(acao, "Vou ativar o tema escuro.").marcas).containsExactly(Marca.JSON_ILEGIVEL)
        assertNota(PontuadorAcoes.pontuar(acao, ""), 0.0)
    }

    @Test
    fun `acoes - a case without a tool expects a refusal`() {
        val p = PontuadorAcoes.pontuar(recusa, """{"recusa": "não há ferramenta para a previsão do tempo"}""")
        assertNota(p, 1.0)
        assertThat(p.marcas).containsExactly(Marca.RECUSA_CORRETA)
        assertNota(PontuadorAcoes.pontuar(recusa, ""), 1.0)
        assertNota(PontuadorAcoes.pontuar(recusa, "```json\n{\"recusa\": \"x\"}\n```"), 1.0)
    }

    @Test
    fun `acoes - a spurious call on a refusal case is 0`() {
        val p = PontuadorAcoes.pontuar(recusa, """{"nome":"app_status","args":{}}""")
        assertNota(p, 0.0)
        assertThat(p.marcas).containsExactly(Marca.CHAMADA_ESPURIA)
        assertNota(PontuadorAcoes.pontuar(recusa, "Não posso fazer isso."), 0.0) // prosa não é recusa: o prompt pede JSON
    }

    // ── contexto ─────────────────────────────────────────────────────────────────────────

    private val contexto = caso(esperado = "25 MB", campos = arrayOf("valor" to "25 MB", "distratores" to lista("52 MB")))

    @Test
    fun `contexto - the exact value is 1, alone or in a sentence`() {
        assertNota(PontuadorContexto.pontuar(contexto, "25 MB"), 1.0)
        assertNota(PontuadorContexto.pontuar(contexto, "O limite é de 25 MB por nota."), 1.0)
        assertNota(PontuadorContexto.pontuar(contexto, "25 mb"), 1.0)
    }

    @Test
    fun `contexto - a wrong value, a longer number, a planted distractor and empty are 0`() {
        val errado = PontuadorContexto.pontuar(contexto, "52 MB")
        assertNota(errado, 0.0)
        assertThat(errado.marcas).containsExactly(Marca.VALOR_AUSENTE, Marca.DISTRATOR_PRESENTE)
        assertNota(PontuadorContexto.pontuar(contexto, "125 MB"), 0.0)
        val ambos = PontuadorContexto.pontuar(contexto, "25 MB (antes eram 52 MB)")
        assertNota(ambos, 0.0)
        assertThat(ambos.marcas).containsExactly(Marca.DISTRATOR_PRESENTE)
        assertNota(PontuadorContexto.pontuar(contexto, ""), 0.0)
    }

    // ── determinismo ─────────────────────────────────────────────────────────────────────

    @Test
    fun `scoring the same output twice gives the same score`() {
        val saidas = listOf("Oi, pessoal.", "Claro! Oi pessoal", "", """{"a":1}""")
        for (p in listOf(PontuadorPontuacao to ditado, PontuadorReescrita to reescrita, PontuadorJson to extracao, PontuadorAcoes to acao, PontuadorContexto to contexto)) {
            for (s in saidas) assertThat(p.first.pontuar(p.second, s)).isEqualTo(p.first.pontuar(p.second, s))
        }
    }

    @Test
    fun `the registry knows the five tests and nothing else`() {
        assertThat(Pontuadores.testes).containsExactly("pontuacao", "reescrita", "json", "acoes", "contexto")
        assertThat(Pontuadores.de("rapido")).isNull()
    }

    @Test
    fun `acoes - invalid json syntax is not a valid call`() {
        val p = PontuadorAcoes.pontuar(caso(esperado = obj("""{"nome":"skin_list","args":{}}""")), "{nome:'skin_list'}")
        assertNota(p, 0.0)
        assertThat(p.marcas).contains(Marca.JSON_ILEGIVEL)
    }

    @Test
    fun `acoes - null args are invalid arguments, not an empty object`() {
        val c = caso(esperado = obj("""{"nome":"skin_list","args":{}}"""))
        assertNota(PontuadorAcoes.pontuar(c, """{"nome":"skin_list","args":null}"""), 0.4)
        assertNota(PontuadorAcoes.pontuar(c, """{"nome":"skin_list","args":"x"}"""), 0.4)
        assertNota(PontuadorAcoes.pontuar(c, """{"nome":"skin_list"}"""), 1.0)
        assertNota(PontuadorAcoes.pontuar(c, """{"nome":"skin_list","args":{}}"""), 1.0)
    }

    @Test
    fun `acoes - a second call hidden past the read limit does not earn a full mark`() {
        val c = caso(esperado = obj("""{"nome":"skin_list","args":{}}"""))
        val p = PontuadorAcoes.pontuar(c, """{"nome":"skin_list","args":{}}""" + " ".repeat(20_000) + """{"nome":"skin_apply","args":{}}""")
        assertNota(p, 0.0)
        assertThat(p.marcas).contains(Marca.MULTIPLAS_CHAMADAS)
    }
}
