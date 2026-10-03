package com.keyflow.core

enum class RecordingMode { TOGGLE, PUSH_TO_TALK }

/**
 * Origem da transcrição (aba Voz). CREDITS é stub inerte nesta fase (VOZ-01). LOCAL = modelo de voz
 * rodando no aparelho (native-voice-models, MGR-01) — última da lista, é a 5ª opção do seletor.
 */
enum class TranscriptionSource { CREDITS, OPENAI, GROQ, CUSTOM, LOCAL }

/**
 * Origem do raciocínio (aba Raciocínio, reasoning-models). CREDITS = créditos KeyFlow; LOCAL = modelo de texto rodando no aparelho
 * (LiteRT-LM, processo `:reasoning`); OPENAI/ANTHROPIC/GOOGLE = nuvem. Independente da
 * origem da transcrição: uma não muda a outra.
 */
enum class ReasoningSource { CREDITS, LOCAL, OPENAI, ANTHROPIC, GOOGLE, CUSTOM }

/**
 * Origem da visão (aba Visão, vision-capture). CREDITS = créditos KeyFlow; LOCAL = OCR e modelos visuais rodando no aparelho
 * (100% offline); OPENAI = nuvem GPT-4o; GOOGLE = Google Gemini Vision; ANTHROPIC = Claude Vision; CUSTOM = API personalizada.
 */
enum class VisionSource { CREDITS, LOCAL, OPENAI, GOOGLE, ANTHROPIC, CUSTOM }

/**
 * Origem da voz / síntese de fala (aba Voz, FALA). CREDITS = créditos KeyFlow; LOCAL = modelos de voz no aparelho
 * (100% offline, TTS); OPENAI = síntese via OpenAI TTS; ELEVENLABS = vozes ultra-realistas ElevenLabs;
 * GOOGLE = síntese neural Google Cloud TTS; CUSTOM = API de voz personalizada.
 */
enum class FalaSource { CREDITS, LOCAL, OPENAI, ELEVENLABS, GOOGLE, CUSTOM }

/** Lado inicial do overlay (OVL-05). */
enum class OverlaySide { LEFT, RIGHT }

/** Tamanho do orbe do overlay (OVL-05). */
enum class OrbSize { SMALL, MEDIUM, LARGE }

/** Modo de tema (FND-04). Persistido como booleano `themeDark`. */
enum class ThemeMode { LIGHT, DARK }

/**
 * Ação mapeável aos atalhos de toque da orbe (2 e 3 toques), acionados só em idle/pause.
 * - [TOGGLE_KEYBOARD]: retrai/expande o teclado KeyFlow (requer o KeyFlow como teclado ativo).
 * - [SWITCH_IME]: abre o seletor de teclados do sistema.
 */
enum class OrbShortcutAction { NONE, TOGGLE_KEYBOARD, SWITCH_IME }

/**
 * O que fazer quando o nº de toques passa do máximo configurado (>3):
 * - [CANCEL]: ignora; nada acontece.
 * - [NEAREST_DOWN]: cai no atalho ativo de menos toques mais próximo (ex.: 4 toques aciona o de 3).
 */
enum class ShortcutOverflow { CANCEL, NEAREST_DOWN }

enum class ButtonState {
    IDLE_READY,      // campo focado, pronto para gravar
    IDLE_NO_FIELD,   // sem campo focado
    RECORDING,
    PAUSED,          // gravação pausada (REC-01/REC-02); o tempo em pausa não conta (REC-06)
    SENDING,
    ERROR,
    CANCELLING,      // cancelamento com janela de desfazer: áudio retido, descartado ao expirar (Fase 2a)
    PENDING_PASTE    // transcrição pendente esperando campo
}

sealed interface TranscriptionResult {
    data class Success(val text: String) : TranscriptionResult
    data class Error(val kind: ErrorKind, val message: String) : TranscriptionResult
}

enum class ErrorKind {
    NETWORK, AUTH, HTTP, TIMEOUT, EMPTY_AUDIO, TOO_LARGE, CONFIG_MISSING,
    /** Transcrição no aparelho falhou (native-voice-models, NAT-04): a mensagem já vem pronta para o usuário. */
    LOCAL,
}

/**
 * Ação ao tocar num erro clicável (banner de overlay ou glifo ✖ do teclado/orbe): [NONE] não faz nada,
 * [VOZ] abre a aba Transcrição (erro de config/token), [MIC] abre a aba da interface que avisou (Sobrepor
 * pela orbe, Teclado pelo teclado) já no cartão do modo — o chip de Microfone pulsa sozinho lá
 * (StatusChip.pulsing).
 */
enum class ErrorNavTarget { NONE, VOZ, MIC }
