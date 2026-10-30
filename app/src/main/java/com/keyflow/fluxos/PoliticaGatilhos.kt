package com.keyflow.fluxos

import com.keyflow.i18n.tr
import com.keyflow.orbe.LugarDaOrbe

/** Por que [PoliticaGatilhos.ligar] não ligou. A tela mostra o motivo (as linhas de atalho ficam desligadas — FLX-38). */
enum class RecusaDeLigar {
    FLUXO_INEXISTENTE,
    FLUXO_INCOMPLETO,

    /** Já há [LIMITES.GATILHOS] gatilhos e a superfície é nova. */
    LIMITE_DE_GATILHOS,
}

/** O resultado de [PoliticaGatilhos.excluir]: a config sem o fluxo e [botoes], os `botaoId` que a tela remove da barra (FLX-10). */
data class Exclusao(val config: ConfigFluxos, val botoes: List<String>)

/**
 * Ligar, desligar, excluir e limpar gatilhos (FLX-35, FLX-37, FLX-38, FLX-50), puro: devolve uma config nova, quem chama grava.
 * Um atalho dispara no máximo um fluxo (D7): ligar numa superfície ocupada tira do outro e nunca deixa dois.
 */
object PoliticaGatilhos {

    /** Por que [fluxoId] não pode ser ligado a [superficie] agora; `null` = pode. */
    fun recusaDe(config: ConfigFluxos, fluxoId: String, superficie: Superficie): RecusaDeLigar? {
        val fluxo = config.fluxo(fluxoId) ?: return RecusaDeLigar.FLUXO_INEXISTENTE
        if (!ValidadorFluxo.validar(fluxo).valido) return RecusaDeLigar.FLUXO_INCOMPLETO
        if (config.gatilho(superficie) == null && config.gatilhos.size >= LIMITES.GATILHOS) return RecusaDeLigar.LIMITE_DE_GATILHOS
        return null
    }

    /**
     * Liga [superficie] a [fluxoId]: só se o fluxo existe e é válido (senão devolve [config] como veio — ver [recusaDe]); se a
     * superfície já tinha outro fluxo, esse gatilho sai (a tela já confirmou — FLX-37). A ação do `Settings` por baixo não é
     * tocada (o gatilho a sombreia — D12).
     */
    fun ligar(config: ConfigFluxos, fluxoId: String, superficie: Superficie): ConfigFluxos {
        if (recusaDe(config, fluxoId, superficie) != null) return config
        val outros = config.gatilhos.filter { it.superficie != superficie }
        // Fica no lugar do antigo, se havia, para a lista não embaralhar a cada troca.
        val posicao = config.gatilhos.indexOfFirst { it.superficie == superficie }
        val novo = Gatilho(superficie, fluxoId)
        val gatilhos = if (posicao >= 0) outros.toMutableList().apply { add(posicao, novo) } else outros + novo
        return config.copy(gatilhos = gatilhos)
    }

    /** Tira o gatilho de [superficie]; o gesto volta à ação do lugar. Superfície livre = nada a fazer. */
    fun desligar(config: ConfigFluxos, superficie: Superficie): ConfigFluxos =
        if (config.gatilho(superficie) == null) config else config.copy(gatilhos = config.gatilhos.filter { it.superficie != superficie })

    /**
     * Remove o fluxo e **todos** os gatilhos dele; devolve também os `botaoId` das superfícies de barra que ele tinha, para a
     * tela remover os botões do teclado (FLX-10, FLX-36).
     */
    fun excluir(config: ConfigFluxos, fluxoId: String): Exclusao {
        val dele = config.gatilhosDe(fluxoId)
        return Exclusao(
            config = config.copy(
                fluxos = config.fluxos.filter { it.id != fluxoId },
                gatilhos = config.gatilhos.filter { it.fluxoId != fluxoId },
            ),
            botoes = dele.mapNotNull { (it.superficie as? Superficie.Barra)?.botaoId },
        )
    }

    /**
     * Tira os gatilhos órfãos (FLX-50): de fluxo que não existe e, em [botoesDaBarra], de botão que não existe mais. `null` =
     * "não sei quais botões a barra tem": só confere os fluxos e deixa os gatilhos de barra como estão. Roda dentro de cada
     * gravação, nunca na leitura (que só observa).
     */
    fun limpar(config: ConfigFluxos, botoesDaBarra: Set<String>?): ConfigFluxos {
        val ids = config.fluxos.mapTo(HashSet()) { it.id }
        val gatilhos = config.gatilhos.filter { g ->
            g.fluxoId in ids && (botoesDaBarra == null || g.superficie !is Superficie.Barra || g.superficie.botaoId in botoesDaBarra)
        }
        return if (gatilhos.size == config.gatilhos.size) config else config.copy(gatilhos = gatilhos)
    }

    /**
     * O aviso que a tela mostra **antes de confirmar** uma ligação que substitui algo atribuído (FLX-35/37, D17): nomeia o que
     * será substituído e onde. `null` quando não há perda a confirmar — superfície livre, ou já ligada a este mesmo fluxo.
     *
     * O que vale hoje na [superficie]: o fluxo do gatilho existente ("Fluxo: <nome>", que sombreia a ação por baixo e por isso é o
     * que se perde), ou, sem gatilho, a ação própria do lugar ([PoliticaDoLugar.rotuloDaAcaoPropria]); na barra, só um fluxo. Vale
     * para qualquer ação, gravar inclusive: não há texto nem exceção própria dela.
     */
    fun avisoDeSubstituicao(config: ConfigFluxos, superficie: Superficie, fluxoNovoId: String, politica: PoliticaDoLugar): String? {
        val ligado = config.gatilho(superficie)
        val fluxoAtual = ligado?.let { config.fluxo(it.fluxoId) } // gatilho de fluxo que sumiu é limpo na gravação: vale a ação por baixo
        if (fluxoAtual != null && fluxoAtual.id == fluxoNovoId) return null
        val anterior = when {
            fluxoAtual != null -> tr("Fluxo: %s", PadroesFluxo.nomeExibido(fluxoAtual))
            superficie is Superficie.Orbe -> politica.rotuloDaAcaoPropria(superficie.toques)
            else -> null
        } ?: return null
        return when (superficie) {
            is Superficie.Barra -> tr("Isto substitui “%s” neste botão do teclado.", anterior)
            is Superficie.Orbe -> when (superficie.lugar) {
                LugarDaOrbe.FLUTUANTE -> tr("Isto substitui “%s” neste gesto da orbe flutuante.", anterior)
                LugarDaOrbe.TECLADO -> tr("Isto substitui “%s” neste gesto da orbe do teclado.", anterior)
                LugarDaOrbe.NOTIFICACAO -> tr("Isto substitui “%s” neste gesto da orbe da notificação.", anterior)
            }
        }
    }
}
