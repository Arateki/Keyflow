package com.keyflow.fluxos

import com.google.common.truth.Truth.assertThat
import com.keyflow.orbe.LugarDaOrbe
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** O JSON dos fluxos e dos gatilhos (FLX-45..47, FLX-57): esquema v1, tolerância a dado ruim e os limites. */
class CodecFluxosTest {

    private val semente = listOf(Fluxo("s-padrao", "Semente", padrao = "padrao", etapas = listOf(Etapa.Ditar(), Etapa.Falar())))

    private fun fluxo(id: String, vararg etapas: Etapa, nome: String = "Fluxo $id", padrao: String? = null) =
        Fluxo(id, nome, padrao, etapas.toList())

    private fun ida(fluxos: List<Fluxo>) = CodecFluxos.decodificarFluxos(CodecFluxos.codificarFluxos(fluxos))

    private fun fluxosJson(vararg flows: String) = """{"v":1,"flows":[${flows.joinToString(",")}]}"""

    private fun gatilhosJson(vararg triggers: String) = """{"v":1,"triggers":[${triggers.joinToString(",")}]}"""

    // ── round-trip ──────────────────────────────────────────────────────────

    @Test
    fun `round-trip com todas as etapas, os modelos e o padrao`() {
        val completo = Fluxo(
            id = "a1b2c3d4",
            nome = "Ouvir e responder",
            padrao = "ouvir-responder",
            etapas = listOf(
                Etapa.Ditar(ModeloRef("LOCAL", "parakeet-v3")),
                Etapa.Perguntar("Responda em poucas frases.", ModeloRef("OPENAI", "gpt-4o")),
                Etapa.Agente(ModeloRef("CUSTOM")),
                Etapa.Falar(ModeloRef("SYSTEM")),
                Etapa.Inserir,
            ),
        )
        val visual = fluxo("b", Etapa.Capturar, Etapa.Visao("Descreva a imagem.", ModeloRef("GOOGLE", "gemini")), Etapa.Inserir)
        val pelaAba = fluxo("c", Etapa.Ditar(), Etapa.Perguntar(), Etapa.Visao(), Etapa.Falar(), Etapa.Agente())
        assertThat(ida(listOf(completo, visual, pelaAba))).containsExactly(completo, visual, pelaAba).inOrder()
    }

    @Test
    fun `round-trip preserva acentos, aspas, barras e quebras de linha nas instrucoes e no nome`() {
        val texto = "Responda \"curto\", sem listas.\nUse acentuação: ção, ñ, 😀 e \\barra\\ tab\tfim"
        val f = fluxo("x", Etapa.Visao(texto), Etapa.Perguntar(texto), nome = "Olá “mundo” \"2\"")
        assertThat(ida(listOf(f))).containsExactly(f)
    }

    @Test
    fun `round-trip das superficies nos tres lugares da orbe e na barra`() {
        val gatilhos = listOf(
            Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), "a"),
            Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 3), "b"),
            Gatilho(Superficie.Orbe(LugarDaOrbe.TECLADO, 2), "a"),
            Gatilho(Superficie.Orbe(LugarDaOrbe.TECLADO, 3), "a"),
            Gatilho(Superficie.Orbe(LugarDaOrbe.NOTIFICACAO, 2), "c"),
            Gatilho(Superficie.Orbe(LugarDaOrbe.NOTIFICACAO, 3), "a"),
            Gatilho(Superficie.Barra("b81f02"), "a"),
        )
        assertThat(CodecFluxos.decodificarGatilhos(CodecFluxos.codificarGatilhos(gatilhos))).containsExactlyElementsIn(gatilhos).inOrder()
    }

    @Test
    fun `o esquema v1 usa os nomes da spec`() {
        val f = fluxo("a1b2c3d4", Etapa.Ditar(), Etapa.Perguntar("sys", ModeloRef("OPENAI", "gpt-4o")), Etapa.Falar(), nome = "Ouvir", padrao = "ouvir-responder")
        val raiz = JSONObject(CodecFluxos.codificarFluxos(listOf(f)))
        assertThat(raiz.getInt("v")).isEqualTo(1)
        val fluxo = raiz.getJSONArray("flows").getJSONObject(0)
        assertThat(fluxo.getString("id")).isEqualTo("a1b2c3d4")
        assertThat(fluxo.getString("name")).isEqualTo("Ouvir")
        assertThat(fluxo.getString("builtin")).isEqualTo("ouvir-responder")
        val passos = fluxo.getJSONArray("steps")
        assertThat((0 until passos.length()).map { passos.getJSONObject(it).getString("type") }).containsExactly("ditar", "perguntar", "falar").inOrder()
        val perguntar = passos.getJSONObject(1)
        assertThat(perguntar.getString("system")).isEqualTo("sys")
        assertThat(perguntar.getJSONObject("model").getString("source")).isEqualTo("OPENAI")
        assertThat(perguntar.getJSONObject("model").getString("model")).isEqualTo("gpt-4o")
        assertThat(passos.getJSONObject(0).has("model")).isFalse()

        val gat = JSONObject(CodecFluxos.codificarGatilhos(listOf(
            Gatilho(Superficie.Orbe(LugarDaOrbe.NOTIFICACAO, 3), "a1b2c3d4"),
            Gatilho(Superficie.Orbe(LugarDaOrbe.TECLADO, 2), "a1b2c3d4"),
            Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), "a1b2c3d4"),
            Gatilho(Superficie.Barra("b81f02"), "a1b2c3d4"),
        )))
        assertThat(gat.getInt("v")).isEqualTo(1)
        val lista = gat.getJSONArray("triggers")
        assertThat((0 until lista.length()).map { lista.getJSONObject(it).getString("surface") })
            .containsExactly("orb:notificacao:3", "orb:teclado:2", "orb:flutuante:2", "bar:b81f02").inOrder()
        assertThat(lista.getJSONObject(0).getString("flow")).isEqualTo("a1b2c3d4")
    }

    @Test
    fun `um modelo sem o id do modelo grava so a origem`() {
        val f = fluxo("a", Etapa.Falar(ModeloRef("SYSTEM")))
        val modelo = JSONObject(CodecFluxos.codificarFluxos(listOf(f))).getJSONArray("flows").getJSONObject(0)
            .getJSONArray("steps").getJSONObject(0).getJSONObject("model")
        assertThat(modelo.has("model")).isFalse()
        assertThat(ida(listOf(f))).containsExactly(f)
    }

    // ── ausente, vazio, malformado ──────────────────────────────────────────

    @Test
    fun `chave ausente ou em branco nao e uma lista e a config usa a semente`() {
        assertThat(CodecFluxos.decodificarFluxos(null)).isNull()
        assertThat(CodecFluxos.decodificarFluxos("")).isNull()
        assertThat(CodecFluxos.decodificarFluxos("   ")).isNull()
        assertThat(CodecFluxos.decodificarConfig(null, null) { semente }).isEqualTo(ConfigFluxos(semente, emptyList()))
    }

    @Test
    fun `lista presente e vazia e vazia de verdade, sem semente`() {
        assertThat(CodecFluxos.decodificarFluxos(CodecFluxos.codificarFluxos(emptyList()))).isEmpty()
        assertThat(CodecFluxos.decodificarConfig(fluxosJson(), null) { semente }).isEqualTo(ConfigFluxos(emptyList(), emptyList()))
    }

    @Test
    fun `JSON malformado cai na semente`() {
        for (ruim in listOf("{", "[]", "[1,2]", "nao e json", "null", "42", "\"texto\"", "{\"v\":1}", "{\"v\":1,\"flows\":{}}", "{\"v\":1,\"flows\":\"x\"}")) {
            assertThat(CodecFluxos.decodificarFluxos(ruim)).isNull()
            assertThat(CodecFluxos.decodificarConfig(ruim, null) { semente }.fluxos).isEqualTo(semente)
        }
    }

    @Test
    fun `gatilho solto sem fluxos gravados e ignorado`() {
        val solto = CodecFluxos.codificarGatilhos(listOf(Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), "s-padrao")))
        assertThat(CodecFluxos.decodificarConfig(null, solto) { semente }.gatilhos).isEmpty()
        assertThat(CodecFluxos.decodificarConfig("{", solto) { semente }.gatilhos).isEmpty()
    }

    @Test
    fun `com fluxos gravados os gatilhos sao lidos, ate os de fluxo inexistente`() {
        val flows = CodecFluxos.codificarFluxos(listOf(fluxo("a", Etapa.Ditar(), Etapa.Falar())))
        val orfao = Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), "sumiu")
        val ok = Gatilho(Superficie.Barra("b1"), "a")
        val config = CodecFluxos.decodificarConfig(flows, CodecFluxos.codificarGatilhos(listOf(orfao, ok))) { semente }
        // A limpeza de órfãos é da gravação (FLX-50), não da leitura.
        assertThat(config.gatilhos).containsExactly(orfao, ok).inOrder()
    }

    @Test
    fun `gatilhos ausentes ou malformados viram lista vazia`() {
        assertThat(CodecFluxos.decodificarGatilhos(null)).isEmpty()
        assertThat(CodecFluxos.decodificarGatilhos("")).isEmpty()
        assertThat(CodecFluxos.decodificarGatilhos("{")).isEmpty()
        assertThat(CodecFluxos.decodificarGatilhos("[]")).isEmpty()
        assertThat(CodecFluxos.decodificarGatilhos("{\"v\":1}")).isEmpty()
        assertThat(CodecFluxos.decodificarGatilhos("{\"v\":1,\"triggers\":3}")).isEmpty()
    }

    // ── tolerância ──────────────────────────────────────────────────────────

    @Test
    fun `fluxo malformado e descartado e os outros ficam`() {
        val json = fluxosJson(
            """{"id":"a","name":"Bom","steps":[{"type":"ditar"}]}""",
            """{"name":"Sem id","steps":[]}""",
            """{"id":"","name":"Id vazio","steps":[]}""",
            """{"id":"c","steps":[]}""",
            """{"id":"d","name":"   ","steps":[]}""",
            """{"id":5,"name":"Id numero","steps":[]}""",
            """{"id":"e","name":7,"steps":[]}""",
            """{"id":"f","name":"Passos errados","steps":"ditar"}""",
            """"so um texto"""",
            "42",
            "null",
            "[]",
            """{"id":"g","name":"Outro bom","steps":[{"type":"capturar"}]}""",
        )
        assertThat(CodecFluxos.decodificarFluxos(json)!!.map { it.id }).containsExactly("a", "g").inOrder()
    }

    @Test
    fun `fluxo sem lista de etapas fica vazio e incompleto, nao some`() {
        val lidos = CodecFluxos.decodificarFluxos(fluxosJson("""{"id":"a","name":"Novo fluxo"}""", """{"id":"b","name":"Nulo","steps":null}"""))!!
        assertThat(lidos.map { it.etapas }).containsExactly(emptyList<Etapa>(), emptyList<Etapa>())
    }

    @Test
    fun `etapa de tipo desconhecido vira Desconhecida e o fluxo continua`() {
        val json = fluxosJson("""{"id":"a","name":"Futuro","steps":[{"type":"ditar"},{"type":"grafo","x":1},{"type":"inserir"}]}""")
        val f = CodecFluxos.decodificarFluxos(json)!!.single()
        assertThat(f.etapas).containsExactly(Etapa.Ditar(), Etapa.Desconhecida("grafo"), Etapa.Inserir).inOrder()
        assertThat(ValidadorFluxo.validar(f).valido).isFalse()
        // regrava o que leu: a etapa desconhecida segue desconhecida até a pessoa removê-la
        assertThat(ida(listOf(f))).containsExactly(f)
    }

    @Test
    fun `um JSON de versao mais nova e lido em modo tolerante e regravado como v1 sem o que esta versao nao conhece`() {
        // FLX-47: ler uma versão futura nunca falha, mas regravar perde o que não conhecemos — a versão e o conteúdo da etapa
        // desconhecida. Quem abre o app mais novo depois lê o que esta versão gravou; é o preço de não travar.
        val futuro = """{"v":2,"novo":true,"flows":[{"id":"a","name":"X","extra":1,"steps":[
            {"type":"ditar"},{"type":"grafo","payload":{"nos":[1,2,3],"arestas":[[0,1]]}},{"type":"falar","novidade":true}]}]}"""
        val lido = CodecFluxos.decodificarFluxos(futuro)!!.single()
        assertThat(lido.etapas).containsExactly(Etapa.Ditar(), Etapa.Desconhecida("grafo"), Etapa.Falar()).inOrder()

        val regravado = CodecFluxos.codificarFluxos(listOf(lido))
        assertThat(JSONObject(regravado).getInt("v")).isEqualTo(1)
        for (perdido in listOf("payload", "arestas", "novidade", "extra", "novo")) assertThat(regravado).doesNotContain(perdido)
        val passos = JSONObject(regravado).getJSONArray("flows").getJSONObject(0).getJSONArray("steps")
        assertThat(passos.getJSONObject(1).keys().asSequence().toList()).containsExactly("type") // só o tipo sobrevive
        // e o que foi regravado é estável: lê de volta igual
        assertThat(CodecFluxos.decodificarFluxos(regravado)).containsExactly(lido)
    }

    @Test
    fun `etapa que nao e objeto ou sem tipo e descartada`() {
        val json = fluxosJson("""{"id":"a","name":"X","steps":[{"type":"ditar"},"texto",7,null,{"sem":"tipo"},{"type":""},{"type":5},{"type":"falar"}]}""")
        assertThat(CodecFluxos.decodificarFluxos(json)!!.single().etapas).containsExactly(Etapa.Ditar(), Etapa.Falar()).inOrder()
    }

    @Test
    fun `campos desconhecidos sao ignorados em todos os niveis`() {
        val json = """{"v":7,"novo":true,"flows":[{"id":"a","name":"X","cor":"azul","steps":[
            {"type":"perguntar","system":"s","extra":[1],"model":{"source":"LOCAL","model":"m","tamanho":3}}]}]}"""
        val f = CodecFluxos.decodificarFluxos(json)!!.single()
        assertThat(f.etapas).containsExactly(Etapa.Perguntar("s", ModeloRef("LOCAL", "m")))
    }

    @Test
    fun `modelo mal formado vira nulo e o fluxo continua`() {
        val json = fluxosJson(
            """{"id":"a","name":"X","steps":[
                {"type":"ditar","model":"OPENAI"},
                {"type":"falar","model":{"model":"so-o-id"}},
                {"type":"perguntar","model":{"source":"","model":"m"}},
                {"type":"agente","model":{"source":"LOCAL","model":""}},
                {"type":"visao","model":{"source":"LOCAL","model":null}},
                {"type":"visao","model":{"source":7}}]}""",
        )
        assertThat(CodecFluxos.decodificarFluxos(json)!!.single().etapas).containsExactly(
            Etapa.Ditar(), Etapa.Falar(), Etapa.Perguntar(), Etapa.Agente(ModeloRef("LOCAL")), Etapa.Visao(modelo = ModeloRef("LOCAL")), Etapa.Visao(),
        ).inOrder()
    }

    @Test
    fun `instrucao e sistema vazios viram nulos`() {
        val json = fluxosJson("""{"id":"a","name":"X","steps":[{"type":"visao","instruction":""},{"type":"perguntar","system":""}]}""")
        assertThat(CodecFluxos.decodificarFluxos(json)!!.single().etapas).containsExactly(Etapa.Visao(), Etapa.Perguntar()).inOrder()
    }

    @Test
    fun `ids repetidos viram um so, o primeiro`() {
        val json = fluxosJson(
            """{"id":"a","name":"Primeiro","steps":[]}""",
            """{"id":"b","name":"B","steps":[]}""",
            """{"id":"a","name":"Repetido","steps":[]}""",
        )
        assertThat(CodecFluxos.decodificarFluxos(json)!!.map { it.nome }).containsExactly("Primeiro", "B").inOrder()
    }

    // ── cortes ──────────────────────────────────────────────────────────────

    @Test
    fun `nome acima de 40, instrucao acima de 500 e etapas acima de 8 sao cortados`() {
        val longo = "x".repeat(900)
        val nove = (1..9).joinToString(",") { """{"type":"perguntar","system":"$longo"}""" }
        val json = fluxosJson("""{"id":"a","name":"${"n".repeat(60)}","steps":[{"type":"visao","instruction":"$longo"},$nove]}""")
        val f = CodecFluxos.decodificarFluxos(json)!!.single()
        assertThat(f.nome).hasLength(LIMITES.NOME)
        assertThat(f.etapas).hasSize(LIMITES.ETAPAS)
        assertThat((f.etapas[0] as Etapa.Visao).instrucao).hasLength(LIMITES.TEXTO_DA_ETAPA)
        assertThat((f.etapas[1] as Etapa.Perguntar).sistema).hasLength(LIMITES.TEXTO_DA_ETAPA)
    }

    @Test
    fun `o corte nao deixa meio emoji no fim do nome`() {
        val nome = "a".repeat(LIMITES.NOME - 1) + "😀😀" // o 40º caractere cairia no meio do primeiro emoji
        val lido = CodecFluxos.decodificarFluxos(fluxosJson("""{"id":"a","name":"$nome","steps":[]}"""))!!.single().nome
        assertThat(lido).isEqualTo("a".repeat(LIMITES.NOME - 1))
        assertThat(lido.last().isHighSurrogate()).isFalse()
    }

    @Test
    fun `mais de 20 fluxos ficam so os 20 primeiros`() {
        val json = fluxosJson(*(1..25).map { """{"id":"f$it","name":"F$it","steps":[]}""" }.toTypedArray())
        val ids = CodecFluxos.decodificarFluxos(json)!!.map { it.id }
        assertThat(ids).hasSize(LIMITES.FLUXOS)
        assertThat(ids.first()).isEqualTo("f1")
        assertThat(ids.last()).isEqualTo("f20")
    }

    @Test
    fun `mais de 64 gatilhos ficam so os 64 primeiros`() {
        val json = gatilhosJson(*(1..70).map { """{"surface":"bar:b$it","flow":"a"}""" }.toTypedArray())
        val lidos = CodecFluxos.decodificarGatilhos(json)
        assertThat(lidos).hasSize(LIMITES.GATILHOS)
        assertThat(lidos.last().superficie).isEqualTo(Superficie.Barra("b64"))
    }

    // ── gatilhos ────────────────────────────────────────────────────────────

    @Test
    fun `superficie ilegivel, lugar desconhecido e toques fora de 2 e 3 sao descartados`() {
        val json = gatilhosJson(
            """{"surface":"orb:flutuante:2","flow":"a"}""",
            """{"surface":"orb:flutuante:4","flow":"a"}""",
            """{"surface":"orb:flutuante:1","flow":"a"}""",
            """{"surface":"orb:flutuante:0","flow":"a"}""",
            """{"surface":"orb:flutuante:x","flow":"a"}""",
            """{"surface":"orb:marte:2","flow":"a"}""",
            """{"surface":"orb:FLUTUANTE:2","flow":"a"}""",
            """{"surface":"orb:2","flow":"a"}""",
            """{"surface":"orb:flutuante:2:9","flow":"a"}""",
            """{"surface":"notif:x","flow":"a"}""",
            """{"surface":"bar:","flow":"a"}""",
            """{"surface":"bar:   ","flow":"a"}""",
            """{"surface":"","flow":"a"}""",
            """{"surface":7,"flow":"a"}""",
            """{"flow":"a"}""",
            """{"surface":"orb:teclado:3"}""",
            """{"surface":"orb:teclado:3","flow":""}""",
            """{"surface":"orb:teclado:3","flow":9}""",
            """"orb:notificacao:3"""",
            "null",
            """{"surface":"orb:notificacao:3","flow":"b"}""",
        )
        assertThat(CodecFluxos.decodificarGatilhos(json)).containsExactly(
            Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), "a"),
            Gatilho(Superficie.Orbe(LugarDaOrbe.NOTIFICACAO, 3), "b"),
        ).inOrder()
    }

    @Test
    fun `superficie repetida mantem o primeiro gatilho`() {
        val json = gatilhosJson(
            """{"surface":"orb:flutuante:2","flow":"a"}""",
            """{"surface":"bar:x","flow":"b"}""",
            """{"surface":"orb:flutuante:2","flow":"c"}""",
            """{"surface":"orb:notificacao:2","flow":"d"}""",
        )
        assertThat(CodecFluxos.decodificarGatilhos(json).map { it.fluxoId }).containsExactly("a", "b", "d").inOrder()
    }

    @Test
    fun `o id do botao da barra pode ter dois pontos`() {
        val g = Gatilho(Superficie.Barra("a:b"), "x")
        assertThat(CodecFluxos.decodificarGatilhos(CodecFluxos.codificarGatilhos(listOf(g)))).containsExactly(g)
    }
}
