package com.keyflow.notificacao

import com.keyflow.core.ShortcutOverflow
import com.keyflow.orbe.ResolucaoDeFluxo

/** O que o host da notificação executa depois de resolver uma rajada (design §5). */
sealed interface Comando {
    /** A semântica de `handleKeyboardMicTap` (inclui encerrar a Visão), onde estiver (D12, D16). */
    data object GravarEnviar : Comando

    /** `RetrairExpandirTeclado`, `TrocarTeclado` ou `LerTela`; o portão de Activity é do host. */
    data class Executar(val acao: AcaoDaOrbe) : Comando

    /** Reservado (NTF-39). */
    data class RodarFluxo(val fluxoId: String) : Comando

    /** Reservado: aviso, sem executar a ação por baixo. */
    data object FluxoIncompleto : Comando
    data object Nada : Comando
}

/** Gancho de Fluxos para a notificação: o gatilho de [toques] toques, se houver (NTF-39). Sem Fluxos, sempre `null`. */
typealias GanchoDeFluxoDaNotificacao = (toques: Int) -> ResolucaoDeFluxo?

/**
 * Resolve uma rajada assentada de [toques] toques em um [Comando] (NTF-09..11, design §5). Cada toque executa **a ação da sua
 * interação** (`efetiva.acao1/2/3`); nenhum toque é reescrito para [Comando.GravarEnviar] quando a configuração não o tem (D16).
 *
 * - `toques >= 4`: `CANCEL` → [Comando.Nada]; `NEAREST_DOWN` → o maior `n` em {3, 2} cuja ação (ou fluxo) não é inerte, e então como
 *   se fossem `n` toques; nenhum → [Comando.Nada] (o 1 toque não entra no overflow, como na orbe principal).
 * - `toques` em 1..3: primeiro o gancho de fluxo (na notificação também o 1 toque); senão a ação da interação, com os portões:
 *   `GravarEnviar` sempre age; `RetrairExpandirTeclado` só com [tecladoVisivel]; `TrocarTeclado`/`LerTela` viram [Comando.Executar];
 *   **com a [visaoAtiva] só `GravarEnviar` age**; `Desconhecida`/`RodarFluxo` sem Fluxos → [Comando.Nada].
 */
fun resolver(
    toques: Int,
    visaoAtiva: Boolean,
    tecladoVisivel: Boolean,
    efetiva: ConfigEfetiva,
    overflow: ShortcutOverflow,
    fluxos: GanchoDeFluxoDaNotificacao = { null },
): Comando {
    if (toques <= 0) return Comando.Nada
    val n = if (toques <= 3) {
        toques
    } else {
        when (overflow) {
            ShortcutOverflow.CANCEL -> return Comando.Nada
            ShortcutOverflow.NEAREST_DOWN ->
                listOf(3, 2).firstOrNull { temEfeito(efetiva.acaoParaToques(it), fluxos(it)) } ?: return Comando.Nada
        }
    }
    val acao = efetiva.acaoParaToques(n) ?: return Comando.Nada
    if (visaoAtiva) return if (acao == AcaoDaOrbe.GravarEnviar) Comando.GravarEnviar else Comando.Nada
    when (val f = fluxos(n)) {
        is ResolucaoDeFluxo.Disparar -> return Comando.RodarFluxo(f.fluxoId)
        is ResolucaoDeFluxo.Invalido -> return Comando.FluxoIncompleto
        null -> Unit
    }
    return when (acao) {
        AcaoDaOrbe.GravarEnviar -> Comando.GravarEnviar
        AcaoDaOrbe.Nenhuma -> Comando.Nada
        AcaoDaOrbe.RetrairExpandirTeclado -> if (tecladoVisivel) Comando.Executar(acao) else Comando.Nada
        AcaoDaOrbe.TrocarTeclado, AcaoDaOrbe.LerTela -> Comando.Executar(acao)
        is AcaoDaOrbe.RodarFluxo, is AcaoDaOrbe.Desconhecida -> Comando.Nada
    }
}

private fun temEfeito(acao: AcaoDaOrbe?, fluxo: ResolucaoDeFluxo?): Boolean =
    fluxo != null || when (acao) {
        null, AcaoDaOrbe.Nenhuma, is AcaoDaOrbe.RodarFluxo, is AcaoDaOrbe.Desconhecida -> false
        else -> true
    }
