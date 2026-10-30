package com.keyflow.computador

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecHostsTest {
    private val completo = Host(
        id = "a1b2c3d4", nome = "Servidor \"principal\" é çã", endereco = "dev.example.org", porta = 2222, usuario = "yan",
        autenticacao = AutenticacaoHost.Chave("k9f3a210"), lembrarSenha = true, agente = PoliticaAgente.PERMITIDO, imagens = PoliticaImagens.LIGADO,
    )

    @Test fun idaEVoltaComAcentosAspasETodosOsCampos() {
        val outros = listOf(
            completo,
            Host("b1b2c3d4", "senha", "[::1]", 22, "root", AutenticacaoHost.Senha, lembrarSenha = true, imagens = PoliticaImagens.DESLIGADO),
            Host("c1b2c3d4", "pergunta", "10.0.0.2", 22, "u", AutenticacaoHost.Perguntar),
        )
        val config = ConfigHosts(outros)
        assertEquals(config, CodecHosts.decodificar(CodecHosts.codificar(config)))
    }

    @Test fun formatoTemVersaoUm() = assertEquals(1, JSONObject(CodecHosts.codificar(ConfigHosts())).getInt("v"))

    @Test fun entradaNulaVaziaOuLixoViraListaVazia() {
        for (s in listOf(null, "", "  ", "{", "[]", "null", "{\"hosts\":3}", "\"x\"", "{\"v\":1}")) assertEquals(ConfigHosts(), CodecHosts.decodificar(s))
    }

    @Test fun itemRuimEDescartadoEOsOutrosFicam() {
        val bom = JSONObject(CodecHosts.codificar(ConfigHosts(listOf(completo)))).getJSONArray("hosts").getJSONObject(0)
        val json = """{"v":1,"hosts":[ 42, "x", {}, {"id":"zz","name":"a"}, ${bom}, {"id":"a1b2c3d5","name":"p","address":"x.y","port":0,"user":"u"} ]}"""
        assertEquals(listOf(completo), CodecHosts.decodificar(json).hosts)
    }

    @Test fun campoDesconhecidoEIgnorado() {
        val bom = JSONObject(CodecHosts.codificar(ConfigHosts(listOf(completo)))).getJSONArray("hosts").getJSONObject(0).put("futuro", mapOf("a" to 1))
        val raiz = JSONObject().put("v", 7).put("novo", true).put("hosts", org.json.JSONArray().put(bom))
        assertEquals(listOf(completo), CodecHosts.decodificar(raiz.toString()).hosts)
    }

    @Test fun idRepetidoViraUmENomeRepetidoTambem() {
        val a = JSONObject(CodecHosts.codificar(ConfigHosts(listOf(completo)))).getJSONArray("hosts").getJSONObject(0)
        val mesmoId = JSONObject(a.toString()).put("name", "outro")
        val mesmoNome = JSONObject(a.toString()).put("id", "99999999")
        val config = CodecHosts.decodificar("""{"v":1,"hosts":[$a,$mesmoId,$mesmoNome]}""")
        assertEquals(listOf(completo), config.hosts)
    }

    @Test fun cortaEmCinquentaHosts() {
        val muitos = (0 until 60).map { Host("%08x".format(it), "h$it", "x.org", 22, "u") }
        val bruto = org.json.JSONArray().also { arr -> for (h in muitos) arr.put(JSONObject(CodecHosts.codificar(ConfigHosts(listOf(h)))).getJSONArray("hosts").getJSONObject(0)) }
        assertEquals(50, CodecHosts.decodificar(JSONObject().put("v", 1).put("hosts", bruto).toString()).hosts.size)
    }

    @Test fun autenticacaoDesconhecidaViraPerguntar() {
        val o = JSONObject(CodecHosts.codificar(ConfigHosts(listOf(completo)))).getJSONArray("hosts").getJSONObject(0)
        o.put("auth", JSONObject().put("type", "kerberos"))
        assertEquals(AutenticacaoHost.Perguntar, CodecHosts.decodificar("""{"hosts":[$o]}""").hosts.single().autenticacao)
    }

    @Test fun arquivoEditadoNaoLigaOAgenteEmHostQueOAppNaoResolve() {
        val o = JSONObject(CodecHosts.codificar(ConfigHosts(listOf(completo)))).getJSONArray("hosts").getJSONObject(0)
        o.put("auth", JSONObject().put("type", "ask")).put("agent", true)
        assertFalse(CodecHosts.decodificar("""{"hosts":[$o]}""").hosts.single().agentePodeUsar)
        o.put("auth", JSONObject().put("type", "password")).put("rememberPassword", false).put("agent", true)
        assertFalse(CodecHosts.decodificar("""{"hosts":[$o]}""").hosts.single().agentePodeUsar)
        o.put("rememberPassword", true)
        assertTrue(CodecHosts.decodificar("""{"hosts":[$o]}""").hosts.single().agentePodeUsar)
    }

    @Test fun padroesSaoSeguros() {
        val h = Host("a1b2c3d4", "n", "x.org", usuario = "u")
        assertEquals(PoliticaAgente.PROIBIDO, h.agente)
        assertEquals(PoliticaImagens.PERGUNTAR, h.imagens)
        assertFalse(h.lembrarSenha)
        assertEquals(22, h.porta)
    }

    @Test fun jsonMuitoAninhadoNaoLanca() {
        assertEquals(ConfigHosts(), CodecHosts.decodificar("[".repeat(100_000)))
    }
}
