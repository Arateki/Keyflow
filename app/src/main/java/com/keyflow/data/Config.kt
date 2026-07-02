package com.keyflow.data

import com.keyflow.core.RecordingMode

data class Config(
    val endpointUrl: String = "",
    val authHeaderName: String = "Authorization",
    val authHeaderValue: String = "",
    val model: String = "whisper-1",
    val language: String? = null,
    val recordingMode: RecordingMode = RecordingMode.TOGGLE,
    // Tempo máximo (ms) de espera pela transcrição (WhisperApi.callTimeout + rede de segurança no
    // OverlayCoordinator). Bug real: o OkHttp/JVM não tem timeout de DNS por padrão — sem isto, um
    // provedor com rota de rede ruim trava "transcrevendo" pra sempre (Yan, 2026-07-01).
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    fun isComplete(): Boolean = endpointUrl.isNotBlank() && authHeaderValue.isNotBlank()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
