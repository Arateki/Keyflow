package com.keyflow.core

enum class RecordingMode { TOGGLE, PUSH_TO_TALK }

/** Origem da transcrição (aba Voz). CREDITS é stub inerte nesta fase (VOZ-01). */
enum class TranscriptionSource { CREDITS, OPENAI, GROQ, CUSTOM }

/** Lado inicial do overlay (OVL-05). */
enum class OverlaySide { LEFT, RIGHT }

/** Tamanho do orbe do overlay (OVL-05). */
enum class OrbSize { SMALL, MEDIUM, LARGE }

/** Modo de tema (FND-04). Persistido como booleano `themeDark`. */
enum class ThemeMode { LIGHT, DARK }

enum class ButtonState {
    IDLE_READY,      // campo focado, pronto para gravar
    IDLE_NO_FIELD,   // sem campo focado
    RECORDING,
    PAUSED,          // gravação pausada (REC-01/REC-02); o tempo em pausa não conta (REC-06)
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
