package com.keyflow.fluxos

import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.core.shortcuts.BarsConfig

/**
 * Quais botões `RunFlow` da barra do teclado podem disparar algo (FLX-36): os que têm um gatilho `Barra(botaoId)` para um fluxo que
 * existe e é **válido** (FLX-38). Os demais renderizam desabilitados, como um `GridTrigger` órfão. Puro: o IME recebe só este mapa e
 * nunca conhece o fluxo — o serviço resolve `botaoId → fluxo` na hora do disparo.
 */
object BotoesDeFluxo {
    /** `botaoId → fluxoId` dos gatilhos de barra cujo fluxo existe e passa no [ValidadorFluxo]. */
    fun validos(config: ConfigFluxos): Map<String, String> = buildMap {
        for (gatilho in config.gatilhos) {
            val barra = gatilho.superficie as? Superficie.Barra ?: continue
            val fluxo = config.fluxo(gatilho.fluxoId) ?: continue
            if (ValidadorFluxo.validar(fluxo).valido) put(barra.botaoId, fluxo.id)
        }
    }

    /** `botaoId → nome do fluxo`, para o editor de barras escrever "Fluxo: <nome>" (FLX-36). Vale também para fluxo inválido. */
    fun nomes(config: ConfigFluxos): Map<String, String> = buildMap {
        for (gatilho in config.gatilhos) {
            val barra = gatilho.superficie as? Superficie.Barra ?: continue
            config.fluxo(gatilho.fluxoId)?.let { put(barra.botaoId, it.nome) }
        }
    }

    /**
     * Acrescenta o botão [botaoId] ao fim da barra principal do modo retraído e, se as listas não são espelhadas, também do
     * expandido (FLX-36). Lista vazia vale a de fábrica, como o IME faz, para o botão cair numa barra que existe.
     */
    fun comBotao(barras: BarsConfig, botaoId: String): BarsConfig {
        fun BarDef.mais() = copy(buttons = buttons + BarButton.RunFlow(botaoId))
        fun List<BarDef>.naPrincipal(): List<BarDef> = mapIndexed { i, b -> if (i == 0) b.mais() else b }
        val retraido = barras.collapsed.ifEmpty { BarsConfig.DEFAULT.collapsed }.naPrincipal()
        val expandido = if (barras.mirrored) barras.expanded
        else barras.expanded.ifEmpty { BarsConfig.DEFAULT.expanded }.naPrincipal()
        return barras.copy(collapsed = retraido, expanded = expandido)
    }

    /** Tira dos dois modos os botões de fluxo cujo id está em [botaoIds] (excluir o fluxo ou remover o botão). */
    fun semBotoes(barras: BarsConfig, botaoIds: Set<String>): BarsConfig {
        if (botaoIds.isEmpty()) return barras
        fun List<BarDef>.limpa() = map { bar -> bar.copy(buttons = bar.buttons.filterNot { it is BarButton.RunFlow && it.botaoId in botaoIds }) }
        return barras.copy(collapsed = barras.collapsed.limpa(), expanded = barras.expanded.limpa())
    }
}
