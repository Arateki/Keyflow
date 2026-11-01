package com.keyflow.notificacao

import com.keyflow.skins.SkinIds
import org.json.JSONObject

/**
 * Codec JSON (`"v": 1`) de [ConfigNotificacao] para a chave `notification_config_json` (NTF-36..39, design §4).
 *
 * Tolerante: chave ausente, JSON malformado ou de outro tipo → os padrões; campo desconhecido é ignorado; `skin` que não existe →
 * `atelie`; tocar fora de 1..3 é ignorado; `taps.1` ausente usa `record_toggle`, mas `none` ou um id desconhecido **explícitos**
 * permanecem (D16: nenhum toque é obrigado a gravar). Um id de ação desconhecido volta igual ao regravar.
 *
 * Usa `opt(chave) as? T` (e não `optString`) por causa do `org.json` do Android, que devolve `"null"` para `JSONObject.NULL`. Nunca lança.
 */
object CodecNotificacao {
    const val VERSAO_ESQUEMA = 1

    const val ID_NENHUMA = "none"
    const val ID_GRAVAR_ENVIAR = "record_toggle"
    const val ID_RETRAIR_EXPANDIR = "toggle_keyboard"
    const val ID_TROCAR_TECLADO = "switch_ime"
    const val ID_LER_TELA = "vision"
    const val PREFIXO_FLUXO = "flow:"

    /** As skins de orbe que existem (o teste confere com `OrbStyle`); `pack:<id>` válido também vale. */
    val SKINS_CONHECIDAS: Set<String> = setOf("sistema", "atelie", "aero", "glitch", "rosegold", "mascot")

    fun idDaAcao(acao: AcaoDaOrbe): String = when (acao) {
        AcaoDaOrbe.Nenhuma -> ID_NENHUMA
        AcaoDaOrbe.GravarEnviar -> ID_GRAVAR_ENVIAR
        AcaoDaOrbe.RetrairExpandirTeclado -> ID_RETRAIR_EXPANDIR
        AcaoDaOrbe.LerTela -> ID_LER_TELA
        is AcaoDaOrbe.RodarFluxo -> PREFIXO_FLUXO + acao.fluxoId
        is AcaoDaOrbe.Desconhecida -> acao.id
    }

    fun acaoDoId(id: String): AcaoDaOrbe = when {
        id == ID_NENHUMA -> AcaoDaOrbe.Nenhuma
        id == ID_GRAVAR_ENVIAR -> AcaoDaOrbe.GravarEnviar
        id == ID_RETRAIR_EXPANDIR -> AcaoDaOrbe.RetrairExpandirTeclado
        id == ID_TROCAR_TECLADO -> AcaoDaOrbe.Nenhuma // D14: migração do catálogo antigo, sem inventar gravação
        id == ID_LER_TELA -> AcaoDaOrbe.LerTela
        id.startsWith(PREFIXO_FLUXO) && id.length > PREFIXO_FLUXO.length -> AcaoDaOrbe.RodarFluxo(id.substring(PREFIXO_FLUXO.length))
        else -> AcaoDaOrbe.Desconhecida(id)
    }

    fun skinValida(skin: String): Boolean = skin in SKINS_CONHECIDAS || SkinIds.isPack(skin)

    fun codificar(config: ConfigNotificacao): String {
        val taps = JSONObject()
            .put("1", idDaAcao(config.acao1Toque))
            .put("2", idDaAcao(config.acao2Toques))
            .put("3", idDaAcao(config.acao3Toques))
        return JSONObject()
            .put("v", VERSAO_ESQUEMA)
            .put("show", config.mostrar)
            .put("lockscreen", config.permitirComTelaBloqueada)
            .put("copy", config.copiarDaOrbePrincipal)
            .put("skin", if (skinValida(config.skin)) config.skin else ConfigNotificacao.SKIN_PADRAO)
            .put("taps", taps)
            .toString()
    }

    fun decodificar(json: String?): ConfigNotificacao {
        val padrao = ConfigNotificacao()
        if (json.isNullOrBlank()) return padrao
        return try {
            val obj = JSONObject(json)
            val taps = obj.opt("taps") as? JSONObject
            fun toque(n: Int, padraoDoToque: AcaoDaOrbe): AcaoDaOrbe =
                (taps?.opt(n.toString()) as? String)?.takeIf { it.isNotEmpty() }?.let(::acaoDoId) ?: padraoDoToque
            ConfigNotificacao(
                mostrar = (obj.opt("show") as? Boolean) ?: padrao.mostrar,
                permitirComTelaBloqueada = (obj.opt("lockscreen") as? Boolean) ?: padrao.permitirComTelaBloqueada,
                copiarDaOrbePrincipal = (obj.opt("copy") as? Boolean) ?: padrao.copiarDaOrbePrincipal,
                skin = (obj.opt("skin") as? String)?.takeIf { skinValida(it) } ?: padrao.skin,
                acao1Toque = toque(1, padrao.acao1Toque),
                acao2Toques = toque(2, padrao.acao2Toques),
                acao3Toques = toque(3, padrao.acao3Toques),
            )
        } catch (_: Exception) {
            padrao
        }
    }
}
