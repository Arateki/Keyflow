package com.keyflow.fluxos

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
}
