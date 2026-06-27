package com.keyflow.core

enum class RecordingMode { TOGGLE, PUSH_TO_TALK }

enum class ButtonState {
    IDLE_READY,      // campo focado, pronto para gravar
    IDLE_NO_FIELD,   // sem campo focado
    RECORDING,
    SENDING,
    ERROR,
    PENDING_PASTE    // transcrição pendente esperando campo
}

sealed interface TranscriptionResult {
    data class Success(val text: String) : TranscriptionResult
    data class Error(val kind: ErrorKind, val message: String) : TranscriptionResult
}

enum class ErrorKind {
    NETWORK, AUTH, HTTP, TIMEOUT, EMPTY_AUDIO, TOO_LARGE, CONFIG_MISSING
}
