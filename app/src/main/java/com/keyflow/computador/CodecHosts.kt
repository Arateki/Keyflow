package com.keyflow.computador

import org.json.JSONArray
import org.json.JSONObject

/**
 * Codec da lista de hosts (`computer_hosts_json`, `{"v":1,"hosts":[…]}`). Tolerante como o `ShortcutCodec`: **nunca lança**,
 * item malformado ou fora das regras é descartado, campo desconhecido é ignorado, id repetido vira um (vale o primeiro) e a
 * lista é cortada em 50. Não existe campo de segredo no formato; um campo `password`/`key`/`passphrase` que apareça é ignorado.
 */
object CodecHosts {
    const val VERSAO = 1
    private val formatoId = Regex("^[0-9a-f]{8}$")

    fun codificar(config: ConfigHosts): String {
        val hosts = JSONArray()
        for (h in config.hosts) hosts.put(codificar(h))
        return JSONObject().put("v", VERSAO).put("hosts", hosts).toString()
    }

    private fun codificar(h: Host): JSONObject {
        val auth = JSONObject()
        when (val a = h.autenticacao) {
            is AutenticacaoHost.Chave -> auth.put("type", "key").put("keyId", a.keyId)
            AutenticacaoHost.Senha -> auth.put("type", "password")
            AutenticacaoHost.Perguntar -> auth.put("type", "ask")
        }
        return JSONObject()
            .put("id", h.id).put("name", h.nome).put("address", h.endereco).put("port", h.porta).put("user", h.usuario)
            .put("auth", auth).put("rememberPassword", h.lembrarSenha).put("agent", h.agentePodeUsar)
            .put("images", when (h.imagens) { PoliticaImagens.DESLIGADO -> "off"; PoliticaImagens.PERGUNTAR -> "ask"; PoliticaImagens.LIGADO -> "on" })
    }

    fun decodificar(json: String?): ConfigHosts {
        if (json.isNullOrBlank()) return ConfigHosts()
        return try {
            val raiz = JSONObject(json)
            val itens = raiz.optJSONArray("hosts") ?: return ConfigHosts()
            val aceitos = mutableListOf<Host>()
            for (i in 0 until itens.length()) {
                if (aceitos.size >= LimitesHost.MAX_HOSTS) break
                val host = decodificarHost(itens.optJSONObject(i)) ?: continue
                if (aceitos.any { it.id == host.id }) continue
                // Nome repetido: o segundo item é descartado, porque o nome é a identidade que a pessoa vê.
                if (aceitos.any { it.nome.trim().equals(host.nome.trim(), ignoreCase = true) }) continue
                aceitos += host
            }
            ConfigHosts(aceitos)
        } catch (e: Exception) {
            ConfigHosts()
        } catch (e: StackOverflowError) {
            ConfigHosts()
        }
    }

    private fun decodificarHost(o: JSONObject?): Host? {
        o ?: return null
        val id = (o.opt("id") as? String) ?: return null
        if (!formatoId.matches(id)) return null
        val nome = (o.opt("name") as? String) ?: return null
        val endereco = (o.opt("address") as? String) ?: return null
        val usuario = (o.opt("user") as? String) ?: return null
        val porta = (o.opt("port") as? Number)?.let { n -> n.toInt().takeIf { it.toDouble() == n.toDouble() } } ?: return null
        val auth = decodificarAuth(o.optJSONObject("auth"))
        val imagens = when (o.opt("images")) {
            "off" -> PoliticaImagens.DESLIGADO
            "on" -> PoliticaImagens.LIGADO
            else -> PoliticaImagens.PERGUNTAR
        }
        var host = Host(
            id = id, nome = nome, endereco = endereco, porta = porta, usuario = usuario, autenticacao = auth,
            lembrarSenha = (o.opt("rememberPassword") as? Boolean) ?: false,
            agente = if ((o.opt("agent") as? Boolean) == true) PoliticaAgente.PERMITIDO else PoliticaAgente.PROIBIDO,
            imagens = imagens,
        )
        if (ValidadorHost.validar(host).any { it.campo != CampoHost.LIMITE }) return null
        // Aqui só há metadados: a disponibilidade real é conferida no cofre/registro antes de gravar ou marcar no modal.
        if (host.agentePodeUsar && RegraAgente.podeMarcar(host, credencialDisponivel = host.lembrarSenha || host.autenticacao is AutenticacaoHost.Chave) !is PodeMarcar.Sim) host = host.copy(agente = PoliticaAgente.PROIBIDO)
        return host
    }

    private fun decodificarAuth(o: JSONObject?): AutenticacaoHost {
        o ?: return AutenticacaoHost.Perguntar
        return when (o.opt("type")) {
            "key" -> (o.opt("keyId") as? String)?.takeIf { it.isNotEmpty() && it.length <= 64 }?.let { AutenticacaoHost.Chave(it) } ?: AutenticacaoHost.Perguntar
            "password" -> AutenticacaoHost.Senha
            else -> AutenticacaoHost.Perguntar
        }
    }
}
