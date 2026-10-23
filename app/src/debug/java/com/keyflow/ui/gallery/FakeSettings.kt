package com.keyflow.ui.gallery

import com.keyflow.core.OverlaySide
import com.keyflow.core.TranscriptionSource
import com.keyflow.data.Settings

/**
 * Dados mock fixos para o modo galeria (VERIFY-01). Mantém os screenshots determinísticos
 * (mesmo saldo de créditos, mesma máscara de token) entre execuções. Consumido pelas rotas de
 * config/voz a partir de T10–T16.
 */
object FakeSettings {

    /** Saldo de créditos mock exibido na aba Voz (VOZ-04). */
    const val CREDITS_USED = 128
    const val CREDITS_TOTAL = 500
    const val CREDITS_MINUTES_USED = 42
    const val CREDITS_MINUTES_TOTAL = 165

    /** Máscara de token exibida nos campos de credencial. */
    const val MASKED_TOKEN = "sk-••3b"
    const val OPENAI_API_KEY = "sk-galeria-0000"

    val settings = Settings(
        source = TranscriptionSource.OPENAI,
        customEndpoint = "https://self.host/v1/audio/transcriptions",
        overlaySide = OverlaySide.RIGHT,
        orbSizePx = 156,
    )
}
