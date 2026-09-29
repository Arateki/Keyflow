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
    // [NO_TIMEOUT] (0) = sem limite, escolha "∞" do usuário: espera o quanto for preciso — dá para
    // cancelar durante o envio.
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    // Origem Local com modelo selecionado (native-voice-models, NM-T9): a transcrição vai para o
    // processo :inference. Nesse caso não há endpoint nem token — nada aqui alcança a rede (PRV-01).
    val local: EffectiveConfig.Local? = null,
) {
    fun isComplete(): Boolean = endpointUrl.isNotBlank() && authHeaderValue.isNotBlank()

    /** "∞" na aba Transcrição: nenhum prazo derruba a espera pela transcrição. */
    val waitsForever: Boolean get() = timeoutMs <= NO_TIMEOUT

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L

        /** Sem prazo de resposta ("∞" na aba Transcrição, pedido do Yan, 2026-09-28). */
        const val NO_TIMEOUT = 0L

        /**
         * Espera mínima da transcrição local: a 1ª do processo inclui carregar o modelo (centenas de MB) e
         * a inferência roda na CPU do aparelho. Generoso de propósito; o timeout da config vale se for maior.
         */
        const val LOCAL_MIN_TIMEOUT_MS = 180_000L

        /** Prazo da origem Local: o da config, com o piso de [LOCAL_MIN_TIMEOUT_MS]; "∞" segue sem prazo. */
        fun localTimeoutMs(settingMs: Long): Long =
            if (settingMs <= NO_TIMEOUT) NO_TIMEOUT else maxOf(settingMs, LOCAL_MIN_TIMEOUT_MS)
    }
}
