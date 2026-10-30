package com.keyflow.orbe

import org.json.JSONObject

/**
 * Modo de gravação da orbe do teclado (KBO-18, KBO-19, KBO-27).
 *
 * - [ALTERNAR]: um toque (se atribuído a [AcaoDaOrbeDoTeclado.GravarEnviar]) inicia e outro envia; segurar entra no
 *   modo-arrasto sem mover a orbe.
 * - [SEGURAR_PARA_FALAR]: segurar grava e soltar fora de uma zona abre a janela de envio; nenhum toque precisa ter
 *   [AcaoDaOrbeDoTeclado.GravarEnviar].
 */
enum class ModoDaOrbe(val id: String) {
    ALTERNAR("toggle"),
    SEGURAR_PARA_FALAR("ptt"),
    ;

    companion object {
        fun deId(id: String?): ModoDaOrbe = when (id) {
            SEGURAR_PARA_FALAR.id -> SEGURAR_PARA_FALAR
            else -> ALTERNAR
        }
    }
}

/**
 * Catálogo de ações atribuíveis aos três toques da orbe do teclado (KBO-16, KBO-27, KBO-28, KBO-30).
 *
 * - [RodarFluxo] guarda `flow:<id>` reservado para a FLX-T29 (dispara como [Nenhuma] enquanto o gancho for nulo).
 * - [Desconhecida] preserva qualquer identificador futuro na regravação do JSON e dispara como [Nenhuma], sem
 *   reescrever o 1 toque para [GravarEnviar].
 */
sealed interface AcaoDaOrbeDoTeclado {
    val id: String

    data object GravarEnviar : AcaoDaOrbeDoTeclado {
        override val id: String = "record"
    }

    data object Nenhuma : AcaoDaOrbeDoTeclado {
        override val id: String = "none"
    }

    data object RetrairExpandir : AcaoDaOrbeDoTeclado {
        override val id: String = "toggle_keyboard"
    }

    data object TrocarTeclado : AcaoDaOrbeDoTeclado {
        override val id: String = "switch_ime"
    }

    data object LerTela : AcaoDaOrbeDoTeclado {
        override val id: String = "vision"
    }

    data object PausarRetomar : AcaoDaOrbeDoTeclado {
        override val id: String = "pause_resume"
    }

    data object CancelarDesfazer : AcaoDaOrbeDoTeclado {
        override val id: String = "cancel_undo"
    }

    data class RodarFluxo(val fluxoId: String) : AcaoDaOrbeDoTeclado {
        override val id: String = "$PREFIXO_FLUXO$fluxoId"
    }

    data class Desconhecida(override val id: String) : AcaoDaOrbeDoTeclado

    companion object {
        const val PREFIXO_FLUXO = "flow:"

        fun deId(id: String?, padrao: AcaoDaOrbeDoTeclado): AcaoDaOrbeDoTeclado {
            if (id.isNullOrBlank()) return padrao
            return when {
                id == GravarEnviar.id -> GravarEnviar
                id == Nenhuma.id -> Nenhuma
                id == RetrairExpandir.id -> RetrairExpandir
                id == TrocarTeclado.id -> TrocarTeclado
                id == LerTela.id -> LerTela
                id == PausarRetomar.id -> PausarRetomar
                id == CancelarDesfazer.id -> CancelarDesfazer
                id.startsWith(PREFIXO_FLUXO) && id.length > PREFIXO_FLUXO.length ->
                    RodarFluxo(id.removePrefix(PREFIXO_FLUXO))
                else -> Desconhecida(id)
            }
        }
    }
}

/**
 * Configuração persistida da orbe do teclado (KBO-03, KBO-14, KBO-17, KBO-20, KBO-27, KBO-28).
 *
 * Quando [copiarDaOrbePrincipal] está ligado, os campos próprios ([skin], [modo], [toque1], [toque2], [toque3])
 * permanecem guardados intactos no JSON para serem restaurados quando o interruptor for desligado (KBO-04, KBO-20).
 */
data class ConfigOrbeTeclado(
    val copiarDaOrbePrincipal: Boolean = true,
    val skin: String = SKIN_PADRAO,
    val modo: ModoDaOrbe = ModoDaOrbe.ALTERNAR,
    val toque1: AcaoDaOrbeDoTeclado = AcaoDaOrbeDoTeclado.GravarEnviar,
    val toque2: AcaoDaOrbeDoTeclado = AcaoDaOrbeDoTeclado.RetrairExpandir,
    val toque3: AcaoDaOrbeDoTeclado = AcaoDaOrbeDoTeclado.TrocarTeclado,
) {
    fun acaoParaToques(toques: Int): AcaoDaOrbeDoTeclado = when (toques) {
        1 -> toque1
        2 -> toque2
        3 -> toque3
        else -> AcaoDaOrbeDoTeclado.Nenhuma
    }

    fun comToque(toques: Int, acao: AcaoDaOrbeDoTeclado): ConfigOrbeTeclado = when (toques) {
        1 -> copy(toque1 = acao)
        2 -> copy(toque2 = acao)
        3 -> copy(toque3 = acao)
        else -> this
    }

    companion object {
        const val SKIN_PADRAO = "atelie"

        /**
         * Fábrica própria por modo (Edge Case da spec: no segurar-para-falar, a fábrica própria nasce com 1 toque
         * em [AcaoDaOrbeDoTeclado.Nenhuma]; no Alternar, nasce com [AcaoDaOrbeDoTeclado.GravarEnviar]).
         */
        fun padraoParaModo(modo: ModoDaOrbe): ConfigOrbeTeclado = when (modo) {
            ModoDaOrbe.ALTERNAR -> ConfigOrbeTeclado(
                modo = ModoDaOrbe.ALTERNAR,
                toque1 = AcaoDaOrbeDoTeclado.GravarEnviar,
            )
            ModoDaOrbe.SEGURAR_PARA_FALAR -> ConfigOrbeTeclado(
                modo = ModoDaOrbe.SEGURAR_PARA_FALAR,
                toque1 = AcaoDaOrbeDoTeclado.Nenhuma,
            )
        }
    }
}

/**
 * Codec JSON (`"v": 1`) de [ConfigOrbeTeclado] para a chave `keyboard_orb_config_json` (KBO-27, KBO-28).
 *
 * Usa `opt(chave) as? String` em vez de `optString` para evitar a divergência do `org.json` do Android, que devolve
 * `"null"` literal quando a chave tem `JSONObject.NULL`. Nunca lança exceção.
 */
object CodecOrbeTeclado {
    const val VERSAO_ESQUEMA = 1

    fun codificar(config: ConfigOrbeTeclado): String {
        val taps = JSONObject()
            .put("1", config.toque1.id)
            .put("2", config.toque2.id)
            .put("3", config.toque3.id)
        return JSONObject()
            .put("v", VERSAO_ESQUEMA)
            .put("copy", config.copiarDaOrbePrincipal)
            .put("skin", config.skin.ifBlank { ConfigOrbeTeclado.SKIN_PADRAO })
            .put("mode", config.modo.id)
            .put("taps", taps)
            .toString()
    }

    fun decodificar(json: String?): ConfigOrbeTeclado {
        val padrao = ConfigOrbeTeclado()
        if (json.isNullOrBlank()) return padrao
        return try {
            val obj = JSONObject(json)
            val copiar = (obj.opt("copy") as? Boolean) ?: padrao.copiarDaOrbePrincipal
            val skin = (obj.opt("skin") as? String)?.takeIf { it.isNotBlank() } ?: padrao.skin
            val modo = ModoDaOrbe.deId(obj.opt("mode") as? String)
            val taps = obj.optJSONObject("taps")
            val toque1 = AcaoDaOrbeDoTeclado.deId(taps?.opt("1") as? String, padrao.toque1)
            val toque2 = AcaoDaOrbeDoTeclado.deId(taps?.opt("2") as? String, padrao.toque2)
            val toque3 = AcaoDaOrbeDoTeclado.deId(taps?.opt("3") as? String, padrao.toque3)
            ConfigOrbeTeclado(
                copiarDaOrbePrincipal = copiar,
                skin = skin,
                modo = modo,
                toque1 = toque1,
                toque2 = toque2,
                toque3 = toque3,
            )
        } catch (_: Exception) {
            padrao
        }
    }
}
