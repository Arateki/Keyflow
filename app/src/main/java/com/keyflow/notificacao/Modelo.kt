package com.keyflow.notificacao

import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.i18n.trKey
import com.keyflow.orbe.AlvoDeSubstituicao
import com.keyflow.orbe.AvisoDeSubstituicao
import com.keyflow.orbe.Interacao
import com.keyflow.orbe.LugarDaOrbe
import com.keyflow.orbe.ResolvedorDaOrbeDoTeclado
import com.keyflow.orbe.SuporteDeInteracoes

/**
 * O catálogo de 1, 2 e 3 toques da orbe na notificação (NTF-10, D10: só ações nativas). Pausar/retomar e cancelar/desfazer
 * **não** são ações do catálogo: são os botões (design §3.1).
 */
sealed interface AcaoDaOrbe {
    data object Nenhuma : AcaoDaOrbe

    /** Tocar para começar / tocar para parar (D12). Nenhum toque é obrigado a tê-la (D16). */
    data object GravarEnviar : AcaoDaOrbe
    data object RetrairExpandirTeclado : AcaoDaOrbe
    data object LerTela : AcaoDaOrbe

    /** Reservada (NTF-39): lida e guardada, sem efeito antes de Fluxos. */
    data class RodarFluxo(val fluxoId: String) : AcaoDaOrbe

    /** JSON de versão mais nova: preservada ao regravar, tratada como [Nenhuma]. */
    data class Desconhecida(val id: String) : AcaoDaOrbe
}

/** A configuração própria da notificação (NTF-36..39). Não é campo de `Settings`. */
data class ConfigNotificacao(
    val mostrar: Boolean = true,
    val permitirComTelaBloqueada: Boolean = false,
    val copiarDaOrbePrincipal: Boolean = true,
    /** id de skin: `"sistema"` | `OrbStyle.id` | `"pack:<id>"`. */
    val skin: String = SKIN_PADRAO,
    val acao1Toque: AcaoDaOrbe = AcaoDaOrbe.GravarEnviar,
    val acao2Toques: AcaoDaOrbe = AcaoDaOrbe.RetrairExpandirTeclado,
    val acao3Toques: AcaoDaOrbe = AcaoDaOrbe.Nenhuma,
) {
    fun acaoParaToques(toques: Int): AcaoDaOrbe? = when (toques) {
        1 -> acao1Toque
        2 -> acao2Toques
        3 -> acao3Toques
        else -> null
    }

    fun comToque(toques: Int, acao: AcaoDaOrbe): ConfigNotificacao = when (toques) {
        1 -> copy(acao1Toque = acao)
        2 -> copy(acao2Toques = acao)
        3 -> copy(acao3Toques = acao)
        else -> this
    }

    companion object {
        const val SKIN_PADRAO = "atelie"
    }
}

/**
 * O que vale agora. Com "copiar", a skin e as ações de 1, 2 e 3 toques vêm da orbe principal (D16: Alternar copia
 * gravar/enviar no 1 toque; PTT copia `shortcut1Tap`, inclusive Nenhuma).
 */
data class ConfigEfetiva(
    val skin: String,
    val acao1: AcaoDaOrbe,
    val acao2: AcaoDaOrbe,
    val acao3: AcaoDaOrbe,
    val copiado: Boolean,
    /** [SuporteDeInteracoes.naoSuportadas] da notificação; [destinoNaNotificacao] diz onde cada uma foi parar. */
    val naoTem: List<Interacao>,
    /** Algum dos três toques é [AcaoDaOrbe.GravarEnviar]; o painel avisa se `false` (NTF-11, D16). */
    val iniciaGravacao: Boolean,
) {
    fun acaoParaToques(toques: Int): AcaoDaOrbe? = when (toques) {
        1 -> acao1
        2 -> acao2
        3 -> acao3
        else -> null
    }
}

/** Mapa `OrbShortcutAction` → [AcaoDaOrbe] (design §4). */
fun acaoDoAtalho(atalho: OrbShortcutAction): AcaoDaOrbe = when (atalho) {
    OrbShortcutAction.NONE -> AcaoDaOrbe.Nenhuma
    OrbShortcutAction.TOGGLE_KEYBOARD -> AcaoDaOrbe.RetrairExpandirTeclado
    OrbShortcutAction.SWITCH_IME -> AcaoDaOrbe.Nenhuma
    OrbShortcutAction.VISION_MODE -> AcaoDaOrbe.LerTela
}

/** Onde foi parar uma interação que a notificação não tem (chave de `tr()`), ou `null` se ela a tem (NTF-12, design §3.1). */
fun destinoNaNotificacao(interacao: Interacao): String? = when (interacao) {
    Interacao.SEGURAR -> trKey("Na notificação a gravação é alternar: toque para começar e toque para enviar")
    Interacao.ARRASTAR_CIMA -> trKey("Botão Pausar/Retomar da notificação")
    Interacao.ARRASTAR_LADO -> trKey("Botão Descartar da notificação")
    Interacao.ARRASTAR_BAIXO -> trKey("Tocar no corpo da notificação abre o KeyFlow")
    Interacao.TOQUE_1, Interacao.TOQUE_2, Interacao.TOQUE_3 -> null
}

/** Nunca escreve nada: é o valor que vale agora (NTF-12). Sem "copiar" devolve os valores próprios, sem impor gravação. */
fun efetiva(config: ConfigNotificacao, settings: Settings): ConfigEfetiva {
    val naoTem = SuporteDeInteracoes.naoSuportadas(LugarDaOrbe.NOTIFICACAO)
    if (!config.copiarDaOrbePrincipal) {
        return ConfigEfetiva(
            skin = config.skin,
            acao1 = config.acao1Toque,
            acao2 = config.acao2Toques,
            acao3 = config.acao3Toques,
            copiado = false,
            naoTem = naoTem,
            iniciaGravacao = listOf(config.acao1Toque, config.acao2Toques, config.acao3Toques).any { it == AcaoDaOrbe.GravarEnviar },
        )
    }
    val acao1 = when (settings.overlayRecordingMode) {
        RecordingMode.TOGGLE -> AcaoDaOrbe.GravarEnviar
        RecordingMode.PUSH_TO_TALK -> acaoDoAtalho(settings.shortcut1Tap)
    }
    val acao2 = acaoDoAtalho(settings.shortcut2Tap)
    val acao3 = acaoDoAtalho(settings.shortcut3Tap)
    return ConfigEfetiva(
        skin = settings.orbSkin.ifBlank { ConfigNotificacao.SKIN_PADRAO },
        acao1 = acao1,
        acao2 = acao2,
        acao3 = acao3,
        copiado = true,
        naoTem = naoTem,
        iniciaGravacao = listOf(acao1, acao2, acao3).any { it == AcaoDaOrbe.GravarEnviar },
    )
}

/**
 * Desliga o "copiar" gravando como valores próprios o que estava sendo copiado: a tela não "zera" (design §4).
 * Mostrar e tela bloqueada ficam como estão.
 */
fun desligarCopia(config: ConfigNotificacao, settings: Settings): ConfigNotificacao {
    // Idempotente: com a cópia já desligada os valores próprios valem e nada se regrava (a orbe flutuante pode ter mudado desde então).
    if (!config.copiarDaOrbePrincipal) return config
    val e = efetiva(config, settings)
    return config.copy(
        copiarDaOrbePrincipal = false,
        skin = e.skin,
        acao1Toque = e.acao1,
        acao2Toques = e.acao2,
        acao3Toques = e.acao3,
    )
}

/**
 * Aviso sempre que uma edição sobrescreve uma ação já atribuída a um toque (decisão do Yan; aviso, não trava).
 * `Nenhuma` conta como vazia: trocar `Nenhuma` por algo não avisa.
 */
fun avisosDeSubstituicao(antes: ConfigNotificacao, depois: ConfigNotificacao): List<AvisoDeSubstituicao<AcaoDaOrbe>> =
    (1..3).mapNotNull { toques ->
        ResolvedorDaOrbeDoTeclado.avaliarSubstituicao(
            alvo = AlvoDeSubstituicao.ToqueDaOrbe(toques),
            acaoAnterior = antes.acaoParaToques(toques),
            acaoNova = depois.acaoParaToques(toques)!!,
            vazia = { it == AcaoDaOrbe.Nenhuma },
        )
    }
