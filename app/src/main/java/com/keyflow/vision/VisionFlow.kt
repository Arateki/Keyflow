package com.keyflow.vision

import com.keyflow.core.ButtonState

/** Etapa efêmera independente da origem do acionamento e da interface de seleção. */
enum class VisionFlowState { NONE, CONSENT, SELECTING, ACTIONS, WORKING, COPYING }

/**
 * Quem recebe o resultado da captura (FLX-19, design §7.2). [Gestos] é o caminho de sempre — o `onResult` do [VisionFlow];
 * [Fluxo] entrega à etapa Capturar de um fluxo: [Fluxo.entrega] recebe o valor capturado (que passa a ser **dele**: o [VisionFlow]
 * não o recicla) ou `null` quando não houve captura (consentimento negado, ✕ do seletor, falha, `finish()` sem captura).
 */
sealed interface DestinoCaptura {
    data object Gestos : DestinoCaptura

    class Fluxo<T>(val entrega: (T?) -> Unit) : DestinoCaptura
}

/** Entrada tipada que qualquer gatilho pode fornecer. */
data class VisionFlowRequest(val recordingState: ButtonState, val destino: DestinoCaptura = DestinoCaptura.Gestos)

/** Fluxo não altera a máquina de gravação além de solicitar pausa antes do consentimento. */
class VisionFlow<T>(
    private val pause: () -> Unit,
    private val requestConsent: () -> Unit,
    private val showSelector: () -> Unit,
    private val cleanup: () -> Unit,
    private val onState: (VisionFlowState) -> Unit = {},
    private val onResult: (T) -> Unit,
) {
    var state: VisionFlowState = VisionFlowState.NONE
        private set

    // O destino do pedido corrente; nulo = nenhum pedido, ou o fluxo já recebeu o resultado (entrega uma vez só).
    private var destino: DestinoCaptura.Fluxo<T>? = null

    fun start(request: VisionFlowRequest): Boolean {
        if (state != VisionFlowState.NONE || request.recordingState == ButtonState.SENDING ||
            request.recordingState == ButtonState.CANCELLING) return false
        // Um fluxo nunca pausa nem mexe numa gravação em curso (D14): quem recusa com aviso é o host; aqui é a rede de segurança.
        if (request.destino is DestinoCaptura.Fluxo<*> &&
            (request.recordingState == ButtonState.RECORDING || request.recordingState == ButtonState.PAUSED)) return false
        @Suppress("UNCHECKED_CAST")
        destino = request.destino as? DestinoCaptura.Fluxo<T>
        if (request.recordingState == ButtonState.RECORDING) pause()
        set(VisionFlowState.CONSENT)
        try { requestConsent() } catch (e: Exception) { finish(); throw e }
        return true
    }

    fun consentGranted() {
        if (state != VisionFlowState.CONSENT) return
        set(VisionFlowState.SELECTING)
        try { showSelector() } catch (e: Exception) { finish(); throw e }
    }

    fun selected() {
        if (state == VisionFlowState.SELECTING) set(VisionFlowState.WORKING)
    }

    fun captured(value: T) {
        if (state != VisionFlowState.WORKING) return
        val dono = destino
        destino = null
        try { if (dono != null) dono.entrega(value) else onResult(value) } finally { finish() }
    }

    fun finish() {
        if (state == VisionFlowState.NONE) return
        val dono = destino
        destino = null
        set(VisionFlowState.NONE)
        // Sem captura: o fluxo recebe `null` depois da limpeza (o teclado e as views já voltaram), uma vez só.
        try { cleanup() } finally { dono?.entrega(null) }
    }

    private fun set(next: VisionFlowState) { state = next; onState(next) }
}
