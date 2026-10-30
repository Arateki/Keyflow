package com.keyflow.orbe

import com.keyflow.core.ButtonState
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.RecordingMode
import com.keyflow.core.ShortcutOverflow
import com.keyflow.data.Settings

/**
 * Onde uma ação já atribuída foi substituída por outra (KBO-15, D9).
 */
sealed interface AlvoDeSubstituicao {
    data class ToqueDaOrbe(val toques: Int) : AlvoDeSubstituicao
    data class GestoDaOrbe(val interacao: Interacao) : AlvoDeSubstituicao
    data class BotaoDoTeclado(val botaoId: String) : AlvoDeSubstituicao
}

/**
 * Aviso emitido sempre que uma configuração substitui uma ação já atribuída a um toque, gesto ou botão (KBO-15, D9).
 */
data class AvisoDeSubstituicao<out T>(
    val alvo: AlvoDeSubstituicao,
    val acaoSubstituida: T,
    val acaoNova: T,
)

/**
 * Resultado puro da edição de [ConfigOrbeTeclado]: a nova configuração (sem nunca forçar nem recolocar
 * [AcaoDaOrbeDoTeclado.GravarEnviar]) e a lista de avisos das ações que saíram (KBO-15, D9).
 */
data class ResultadoEdicaoOrbe(
    val config: ConfigOrbeTeclado,
    val avisos: List<AvisoDeSubstituicao<AcaoDaOrbeDoTeclado>>,
)

/**
 * Resultado devolvido pelo gancho de Fluxos para 2 e 3 toques na orbe (KBO-30, KBO-31).
 *
 * Definido no pacote `com.keyflow.orbe` para não importar `com.keyflow.fluxos.*` nem criar ciclo com a FLX-T29.
 */
sealed interface ResolucaoDeFluxo {
    data class Disparar(val fluxoId: String) : ResolucaoDeFluxo
    data class Invalido(
        val fluxoId: String,
        val mensagem: String = MENSAGEM_FLUXO_INCOMPLETO,
    ) : ResolucaoDeFluxo

    companion object {
        const val MENSAGEM_FLUXO_INCOMPLETO = "Fluxo incompleto: abra a aba Fluxos"
    }
}

typealias GanchoDeFluxo = (lugar: LugarDaOrbe, toques: Int) -> ResolucaoDeFluxo?

/**
 * Comando puro resultante da resolução de uma interação na orbe do teclado (KBO-14..18, KBO-25, KBO-30, design §4).
 */
sealed interface ComandoDaOrbeDoTeclado {
    data object Nenhum : ComandoDaOrbeDoTeclado
    data object IniciarGravacao : ComandoDaOrbeDoTeclado
    data object EnviarGravacao : ComandoDaOrbeDoTeclado
    data object ConcluirCancelamento : ComandoDaOrbeDoTeclado
    data object ColarTextoPendente : ComandoDaOrbeDoTeclado
    data object DispensarErro : ComandoDaOrbeDoTeclado
    data object PausarGravacao : ComandoDaOrbeDoTeclado
    data object RetomarGravacao : ComandoDaOrbeDoTeclado
    data object CancelarGravacao : ComandoDaOrbeDoTeclado
    data object DesfazerCancelamento : ComandoDaOrbeDoTeclado
    data object RetrairExpandirTeclado : ComandoDaOrbeDoTeclado
    data object TrocarTeclado : ComandoDaOrbeDoTeclado
    data object IniciarVisao : ComandoDaOrbeDoTeclado
    data object EncerrarVisao : ComandoDaOrbeDoTeclado
    data object EntrarModoArrasto : ComandoDaOrbeDoTeclado
    data object AbrirJanelaEnvioPtt : ComandoDaOrbeDoTeclado
    data class AbrirApp(val manterJanelaEnvioPtt: Boolean = false) : ComandoDaOrbeDoTeclado
    data class DispararFluxo(val fluxoId: String) : ComandoDaOrbeDoTeclado
    data class AvisarFluxoInvalido(val fluxoId: String, val mensagem: String) : ComandoDaOrbeDoTeclado
}

/**
 * Resolvedor JVM puro da orbe do teclado: configuração efetiva (KBO-19), avisos de substituição e aceitação de zero
 * gravar/enviar nos dois modos (KBO-15, D9), catálogo de toques (KBO-16), prioridade da Visão (KBO-25) e gancho
 * injetável de Fluxos que por padrão devolve `null` (KBO-30, KBO-31).
 */
object ResolvedorDaOrbeDoTeclado {

    /**
     * Catálogo das sete ações oferecidas para cada toque da orbe do teclado (KBO-16), na ordem da especificação.
     * Segurar e arrastar são fixos e ficam fora deste catálogo (KBO-18, D7).
     */
    val CATALOGO_DE_TOQUES: List<AcaoDaOrbeDoTeclado> = listOf(
        AcaoDaOrbeDoTeclado.GravarEnviar,
        AcaoDaOrbeDoTeclado.Nenhuma,
        AcaoDaOrbeDoTeclado.RetrairExpandir,
        AcaoDaOrbeDoTeclado.TrocarTeclado,
        AcaoDaOrbeDoTeclado.LerTela,
        AcaoDaOrbeDoTeclado.PausarRetomar,
        AcaoDaOrbeDoTeclado.CancelarDesfazer,
    )

    /** Gancho padrão de Fluxos até a FLX-T29: devolve `null` sem chamar o pacote de Fluxos (KBO-30). */
    val GANCHO_FLUXO_NULO: GanchoDeFluxo = { _, _ -> null }

    /**
     * Calcula a configuração efetiva em memória sem escrever no DataStore (KBO-04, KBO-19, KBO-20, design §5):
     * - com [ConfigOrbeTeclado.copiarDaOrbePrincipal] ligado e `overlayRecordingMode == TOGGLE`: usa `orbSkin`,
     *   modo [ModoDaOrbe.ALTERNAR], 1 toque = [AcaoDaOrbeDoTeclado.GravarEnviar] (gesto nativo da flutuante, ignorando
     *   `shortcut1Tap`) e 2/3 toques mapeados de `shortcut2Tap`/`shortcut3Tap`;
     * - com `copiarDaOrbePrincipal` ligado e `overlayRecordingMode == PUSH_TO_TALK`: usa `orbSkin`, modo
     *   [ModoDaOrbe.SEGURAR_PARA_FALAR], 1 toque = `shortcut1Tap` mapeado (pode ser [AcaoDaOrbeDoTeclado.Nenhuma]) e
     *   2/3 toques mapeados de `shortcut2Tap`/`shortcut3Tap`;
     * - com `copiarDaOrbePrincipal` desligado: devolve [config] intacta (inclusive com zero gravar/enviar ou ações
     *   desconhecidas).
     */
    fun efetiva(config: ConfigOrbeTeclado, settings: Settings): ConfigOrbeTeclado {
        if (!config.copiarDaOrbePrincipal) return config
        val modo = when (settings.overlayRecordingMode) {
            RecordingMode.TOGGLE -> ModoDaOrbe.ALTERNAR
            RecordingMode.PUSH_TO_TALK -> ModoDaOrbe.SEGURAR_PARA_FALAR
        }
        val toque1 = when (modo) {
            ModoDaOrbe.ALTERNAR -> AcaoDaOrbeDoTeclado.GravarEnviar
            ModoDaOrbe.SEGURAR_PARA_FALAR -> mapearAtalhoDaOrbePrincipal(settings.shortcut1Tap)
        }
        return ConfigOrbeTeclado(
            copiarDaOrbePrincipal = true,
            skin = settings.orbSkin.ifBlank { ConfigOrbeTeclado.SKIN_PADRAO },
            modo = modo,
            toque1 = toque1,
            toque2 = mapearAtalhoDaOrbePrincipal(settings.shortcut2Tap),
            toque3 = mapearAtalhoDaOrbePrincipal(settings.shortcut3Tap),
        )
    }

    /** Mapeia um atalho de toque da orbe flutuante para o catálogo da orbe do teclado (design §5). */
    fun mapearAtalhoDaOrbePrincipal(acao: OrbShortcutAction): AcaoDaOrbeDoTeclado = when (acao) {
        OrbShortcutAction.NONE -> AcaoDaOrbeDoTeclado.Nenhuma
        OrbShortcutAction.TOGGLE_KEYBOARD -> AcaoDaOrbeDoTeclado.RetrairExpandir
        OrbShortcutAction.SWITCH_IME -> AcaoDaOrbeDoTeclado.TrocarTeclado
        OrbShortcutAction.VISION_MODE -> AcaoDaOrbeDoTeclado.LerTela
    }

    /**
     * Lugar de onde os gatilhos de Fluxos de 2 e 3 toques devem ser lidos (KBO-30, KBO-31):
     * - copiar ligado → [LugarDaOrbe.FLUTUANTE];
     * - copiar desligado → [LugarDaOrbe.TECLADO].
     */
    fun lugarDeGatilhoEfetivo(config: ConfigOrbeTeclado): LugarDaOrbe =
        if (config.copiarDaOrbePrincipal) LugarDaOrbe.FLUTUANTE else LugarDaOrbe.TECLADO

    /**
     * Liga ou desliga [ConfigOrbeTeclado.copiarDaOrbePrincipal] preservando intactos a skin, o modo e os toques
     * guardados na configuração própria (KBO-04, KBO-20).
     */
    fun alternarCopiar(config: ConfigOrbeTeclado, copiar: Boolean): ConfigOrbeTeclado =
        config.copy(copiarDaOrbePrincipal = copiar)

    /**
     * Avalia a substituição de uma ação já atribuída num [AlvoDeSubstituicao] (toque, gesto ou botão — KBO-15, D9).
     *
     * Só emite [AvisoDeSubstituicao] quando havia uma ação atribuída (`!vazia(acaoAnterior)`) e `acaoNova != acaoAnterior`.
     */
    fun <T> avaliarSubstituicao(
        alvo: AlvoDeSubstituicao,
        acaoAnterior: T?,
        acaoNova: T,
        vazia: (T) -> Boolean,
    ): AvisoDeSubstituicao<T>? {
        if (acaoAnterior == null || vazia(acaoAnterior) || acaoAnterior == acaoNova) return null
        return AvisoDeSubstituicao(
            alvo = alvo,
            acaoSubstituida = acaoAnterior,
            acaoNova = acaoNova,
        )
    }

    /**
     * Substitui a ação do toque `toques` (1, 2 ou 3) em [config], emitindo aviso se o toque já tinha uma ação diferente
     * de [AcaoDaOrbeDoTeclado.Nenhuma] e **nunca** recolocando [AcaoDaOrbeDoTeclado.GravarEnviar] em outro toque (KBO-15, D9).
     */
    fun substituirToque(
        config: ConfigOrbeTeclado,
        toques: Int,
        novaAcao: AcaoDaOrbeDoTeclado,
    ): ResultadoEdicaoOrbe {
        if (toques !in 1..3) return ResultadoEdicaoOrbe(config = config, avisos = emptyList())
        val anterior = config.acaoParaToques(toques)
        val aviso = avaliarSubstituicao(
            alvo = AlvoDeSubstituicao.ToqueDaOrbe(toques),
            acaoAnterior = anterior,
            acaoNova = novaAcao,
            vazia = { it == AcaoDaOrbeDoTeclado.Nenhuma },
        )
        return ResultadoEdicaoOrbe(
            config = config.comToque(toques, novaAcao),
            avisos = listOfNotNull(aviso),
        )
    }

    /**
     * Compara [anterior] e [nova], aceitando qualquer quantidade de [AcaoDaOrbeDoTeclado.GravarEnviar] (inclusive zero,
     * tanto em [ModoDaOrbe.ALTERNAR] quanto em [ModoDaOrbe.SEGURAR_PARA_FALAR]) e listando os avisos das ações que
     * foram substituídas nos toques 1..3 (KBO-15, D9).
     */
    fun aplicarEdicao(
        anterior: ConfigOrbeTeclado,
        nova: ConfigOrbeTeclado,
    ): ResultadoEdicaoOrbe {
        val avisos = (1..3).mapNotNull { toques ->
            avaliarSubstituicao(
                alvo = AlvoDeSubstituicao.ToqueDaOrbe(toques),
                acaoAnterior = anterior.acaoParaToques(toques),
                acaoNova = nova.acaoParaToques(toques),
                vazia = { it == AcaoDaOrbeDoTeclado.Nenhuma },
            )
        }
        return ResultadoEdicaoOrbe(config = nova, avisos = avisos)
    }

    /**
     * Resolve uma rajada assentada de [contagem] toques (KBO-13, KBO-14, KBO-16, KBO-19, KBO-25, KBO-28, KBO-30, KBO-31).
     */
    fun resolverToques(
        contagem: Int,
        config: ConfigOrbeTeclado,
        settings: Settings = Settings(),
        estado: ButtonState = ButtonState.IDLE_READY,
        tecladoVisivel: Boolean = true,
        visaoAtiva: Boolean = false,
        consultarFluxo: GanchoDeFluxo = GANCHO_FLUXO_NULO,
    ): ComandoDaOrbeDoTeclado {
        if (contagem <= 0) return ComandoDaOrbeDoTeclado.Nenhum
        val cfgEfetiva = efetiva(config, settings)
        val lugarFluxo = lugarDeGatilhoEfetivo(config)

        if (contagem <= 3) {
            return resolverToqueUnico(
                toques = contagem,
                cfgEfetiva = cfgEfetiva,
                lugarFluxo = lugarFluxo,
                estado = estado,
                tecladoVisivel = tecladoVisivel,
                visaoAtiva = visaoAtiva,
                consultarFluxo = consultarFluxo,
            )
        }

        return when (settings.shortcutOverflow) {
            ShortcutOverflow.CANCEL -> ComandoDaOrbeDoTeclado.Nenhum
            ShortcutOverflow.NEAREST_DOWN -> {
                for (candidato in 3 downTo 1) {
                    if (visaoAtiva) {
                        if (cfgEfetiva.acaoParaToques(candidato) == AcaoDaOrbeDoTeclado.GravarEnviar) {
                            return ComandoDaOrbeDoTeclado.EncerrarVisao
                        }
                        continue
                    }
                    if (candidato in 2..3) {
                        val resFluxo = consultarFluxo(lugarFluxo, candidato)
                        if (resFluxo != null) return comandoDeFluxo(resFluxo)
                    }
                    val acao = cfgEfetiva.acaoParaToques(candidato)
                    if (acaoTemEfeitoConfigurado(acao)) {
                        return resolverAcaoDeToque(
                            acao = acao,
                            estado = estado,
                            tecladoVisivel = tecladoVisivel,
                            visaoAtiva = false,
                        )
                    }
                }
                ComandoDaOrbeDoTeclado.Nenhum
            }
        }
    }

    /**
     * Resolve uma [Interacao] já classificada (KBO-13..19, KBO-25, KBO-30, KBO-31).
     */
    fun resolverInteracao(
        interacao: Interacao,
        config: ConfigOrbeTeclado,
        settings: Settings = Settings(),
        estado: ButtonState = ButtonState.IDLE_READY,
        tecladoVisivel: Boolean = true,
        visaoAtiva: Boolean = false,
        consultarFluxo: GanchoDeFluxo = GANCHO_FLUXO_NULO,
    ): ComandoDaOrbeDoTeclado = when (interacao) {
        Interacao.TOQUE_1 -> resolverToques(1, config, settings, estado, tecladoVisivel, visaoAtiva, consultarFluxo)
        Interacao.TOQUE_2 -> resolverToques(2, config, settings, estado, tecladoVisivel, visaoAtiva, consultarFluxo)
        Interacao.TOQUE_3 -> resolverToques(3, config, settings, estado, tecladoVisivel, visaoAtiva, consultarFluxo)
        Interacao.SEGURAR -> {
            if (visaoAtiva) {
                ComandoDaOrbeDoTeclado.Nenhum
            } else {
                val cfgEfetiva = efetiva(config, settings)
                when (cfgEfetiva.modo) {
                    ModoDaOrbe.ALTERNAR -> ComandoDaOrbeDoTeclado.EntrarModoArrasto
                    ModoDaOrbe.SEGURAR_PARA_FALAR -> when (estado) {
                        ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD -> ComandoDaOrbeDoTeclado.IniciarGravacao
                        ButtonState.PAUSED -> ComandoDaOrbeDoTeclado.RetomarGravacao
                        else -> ComandoDaOrbeDoTeclado.Nenhum
                    }
                }
            }
        }
        Interacao.ARRASTAR_CIMA -> {
            if (visaoAtiva) {
                ComandoDaOrbeDoTeclado.Nenhum
            } else {
                when (estado) {
                    ButtonState.RECORDING -> ComandoDaOrbeDoTeclado.PausarGravacao
                    ButtonState.PAUSED -> ComandoDaOrbeDoTeclado.RetomarGravacao
                    else -> ComandoDaOrbeDoTeclado.Nenhum
                }
            }
        }
        Interacao.ARRASTAR_LADO -> {
            if (visaoAtiva) {
                ComandoDaOrbeDoTeclado.Nenhum
            } else {
                when (estado) {
                    ButtonState.RECORDING, ButtonState.PAUSED, ButtonState.SENDING ->
                        ComandoDaOrbeDoTeclado.CancelarGravacao
                    else -> ComandoDaOrbeDoTeclado.Nenhum
                }
            }
        }
        Interacao.ARRASTAR_BAIXO -> {
            if (visaoAtiva || estado == ButtonState.SENDING) {
                ComandoDaOrbeDoTeclado.Nenhum
            } else {
                val cfgEfetiva = efetiva(config, settings)
                ComandoDaOrbeDoTeclado.AbrirApp(
                    manterJanelaEnvioPtt = cfgEfetiva.modo == ModoDaOrbe.SEGURAR_PARA_FALAR &&
                        estado == ButtonState.RECORDING,
                )
            }
        }
    }

    /**
     * Resolve o soltar do dedo após um segurar sem entrar em zona de arrasto (KBO-18):
     * no modo [ModoDaOrbe.SEGURAR_PARA_FALAR] enquanto [ButtonState.RECORDING], abre a janela de envio PTT;
     * nos demais estados/modos, não faz nada.
     */
    fun resolverSoltarSegurar(
        config: ConfigOrbeTeclado,
        settings: Settings = Settings(),
        estado: ButtonState = ButtonState.RECORDING,
        visaoAtiva: Boolean = false,
    ): ComandoDaOrbeDoTeclado {
        if (visaoAtiva) return ComandoDaOrbeDoTeclado.Nenhum
        val cfgEfetiva = efetiva(config, settings)
        return if (cfgEfetiva.modo == ModoDaOrbe.SEGURAR_PARA_FALAR && estado == ButtonState.RECORDING) {
            ComandoDaOrbeDoTeclado.AbrirJanelaEnvioPtt
        } else {
            ComandoDaOrbeDoTeclado.Nenhum
        }
    }

    private fun resolverToqueUnico(
        toques: Int,
        cfgEfetiva: ConfigOrbeTeclado,
        lugarFluxo: LugarDaOrbe,
        estado: ButtonState,
        tecladoVisivel: Boolean,
        visaoAtiva: Boolean,
        consultarFluxo: GanchoDeFluxo,
    ): ComandoDaOrbeDoTeclado {
        val acao = cfgEfetiva.acaoParaToques(toques)
        if (visaoAtiva) {
            return if (acao == AcaoDaOrbeDoTeclado.GravarEnviar) {
                ComandoDaOrbeDoTeclado.EncerrarVisao
            } else {
                ComandoDaOrbeDoTeclado.Nenhum
            }
        }
        // KBO-30: só 2 e 3 toques consultam o gancho de Fluxos; 1 toque nunca é superfície de gatilho.
        if (toques in 2..3) {
            val resFluxo = consultarFluxo(lugarFluxo, toques)
            if (resFluxo != null) return comandoDeFluxo(resFluxo)
        }
        return resolverAcaoDeToque(
            acao = acao,
            estado = estado,
            tecladoVisivel = tecladoVisivel,
            visaoAtiva = false,
        )
    }

    private fun comandoDeFluxo(res: ResolucaoDeFluxo): ComandoDaOrbeDoTeclado = when (res) {
        is ResolucaoDeFluxo.Disparar -> ComandoDaOrbeDoTeclado.DispararFluxo(res.fluxoId)
        is ResolucaoDeFluxo.Invalido -> ComandoDaOrbeDoTeclado.AvisarFluxoInvalido(res.fluxoId, res.mensagem)
    }

    private fun acaoTemEfeitoConfigurado(acao: AcaoDaOrbeDoTeclado): Boolean = when (acao) {
        AcaoDaOrbeDoTeclado.GravarEnviar,
        AcaoDaOrbeDoTeclado.RetrairExpandir,
        AcaoDaOrbeDoTeclado.TrocarTeclado,
        AcaoDaOrbeDoTeclado.LerTela,
        AcaoDaOrbeDoTeclado.PausarRetomar,
        AcaoDaOrbeDoTeclado.CancelarDesfazer,
        -> true
        AcaoDaOrbeDoTeclado.Nenhuma,
        is AcaoDaOrbeDoTeclado.RodarFluxo,
        is AcaoDaOrbeDoTeclado.Desconhecida,
        -> false
    }

    private fun resolverAcaoDeToque(
        acao: AcaoDaOrbeDoTeclado,
        estado: ButtonState,
        tecladoVisivel: Boolean,
        visaoAtiva: Boolean,
    ): ComandoDaOrbeDoTeclado {
        if (visaoAtiva) {
            return if (acao == AcaoDaOrbeDoTeclado.GravarEnviar) {
                ComandoDaOrbeDoTeclado.EncerrarVisao
            } else {
                ComandoDaOrbeDoTeclado.Nenhum
            }
        }
        return when (acao) {
            AcaoDaOrbeDoTeclado.GravarEnviar -> when (estado) {
                ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD -> ComandoDaOrbeDoTeclado.IniciarGravacao
                ButtonState.RECORDING, ButtonState.PAUSED -> ComandoDaOrbeDoTeclado.EnviarGravacao
                ButtonState.CANCELLING -> ComandoDaOrbeDoTeclado.ConcluirCancelamento
                ButtonState.PENDING_PASTE -> ComandoDaOrbeDoTeclado.ColarTextoPendente
                ButtonState.ERROR -> ComandoDaOrbeDoTeclado.DispensarErro
                ButtonState.SENDING -> ComandoDaOrbeDoTeclado.Nenhum
            }
            AcaoDaOrbeDoTeclado.Nenhuma -> ComandoDaOrbeDoTeclado.Nenhum
            AcaoDaOrbeDoTeclado.RetrairExpandir ->
                if (tecladoVisivel) ComandoDaOrbeDoTeclado.RetrairExpandirTeclado else ComandoDaOrbeDoTeclado.Nenhum
            AcaoDaOrbeDoTeclado.TrocarTeclado -> ComandoDaOrbeDoTeclado.TrocarTeclado
            AcaoDaOrbeDoTeclado.LerTela -> ComandoDaOrbeDoTeclado.IniciarVisao
            AcaoDaOrbeDoTeclado.PausarRetomar -> when (estado) {
                ButtonState.RECORDING -> ComandoDaOrbeDoTeclado.PausarGravacao
                ButtonState.PAUSED -> ComandoDaOrbeDoTeclado.RetomarGravacao
                else -> ComandoDaOrbeDoTeclado.Nenhum
            }
            AcaoDaOrbeDoTeclado.CancelarDesfazer -> when (estado) {
                ButtonState.RECORDING, ButtonState.PAUSED -> ComandoDaOrbeDoTeclado.CancelarGravacao
                ButtonState.CANCELLING -> ComandoDaOrbeDoTeclado.DesfazerCancelamento
                else -> ComandoDaOrbeDoTeclado.Nenhum
            }
            // KBO-28, KBO-30: flow:<id> reservado e ação desconhecida disparam como Nenhuma enquanto o gancho for nulo.
            is AcaoDaOrbeDoTeclado.RodarFluxo -> ComandoDaOrbeDoTeclado.Nenhum
            is AcaoDaOrbeDoTeclado.Desconhecida -> ComandoDaOrbeDoTeclado.Nenhum
        }
    }
}
