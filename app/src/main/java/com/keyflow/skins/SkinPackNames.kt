package com.keyflow.skins

/**
 * Os estados do app que uma orbe de pacote desenha (FORMAT.md §2). Mesmo mapa do `OrbButtonView.orbStateFor`: gravando é
 * `LISTENING`; pausada e a janela de desfazer o cancelamento são `PAUSED`; enviando é `TRANSCRIBING`; o resto (inclusive o
 * erro e a entrada do arrasto) é `IDLE`. Puro: o mapa para o `OrbState` do Compose mora em `PackOrb`.
 */
enum class OrbPackState(
    /** O pedaço do nome de arquivo (`orb_<key>_<frame>.svg`) e a chave de `orb.states.<key>` no manifest. */
    val key: String,
) {
    IDLE("idle"),
    LISTENING("listening"),
    PAUSED("paused"),
    TRANSCRIBING("transcribing"),
    ;

    companion object {
        fun ofKey(key: String): OrbPackState? = entries.firstOrNull { it.key == key }
    }
}

/** As peças do teclado de um pacote (FORMAT.md §2) e o nome de arquivo fixo de cada uma. */
enum class KeyboardPart(val fileName: String) {
    KEY_NORMAL("keyboard_key_normal.svg"),
    KEY_PRESSED("keyboard_key_pressed.svg"),
    MODIFIER_NORMAL("keyboard_modifier_normal.svg"),
    MODIFIER_PRESSED("keyboard_modifier_pressed.svg"),
    TRAY("keyboard_tray.svg"),
    ;

    companion object {
        fun ofFileName(name: String): KeyboardPart? = entries.firstOrNull { it.fileName == name }
    }
}

/** O que um nome de arquivo do pacote significa. */
sealed interface PackFileKind {
    data object Manifest : PackFileKind

    /** `orb_<estado>_<frame>.svg`; [frame] já é o inteiro (≥ 1), sem os zeros à esquerda. */
    data class OrbFrame(val state: OrbPackState, val frame: Int) : PackFileKind

    /** Parece um frame de orbe mas o número não vale: [tooLarge] (dígitos demais para um inteiro) ou zero (`orb_idle_00.svg`). */
    data class InvalidOrbFrame(val tooLarge: Boolean) : PackFileKind

    data class KeyboardFace(val part: KeyboardPart) : PackFileKind

    /** Nomes reservados para versões futuras (FORMAT.md §7): hoje são ignorados com aviso. */
    data object Reserved : PackFileKind

    /**
     * Fora do vocabulário. [lowercaseWouldMatch]: o nome só não casa por causa de maiúsculas (`Orb_Idle_01.svg`) — o aviso
     * diz isso, porque é o erro mais comum de quem monta o zip no Windows ou no macOS.
     */
    data class Unknown(val lowercaseWouldMatch: Boolean = false) : PackFileKind
}

/**
 * Vocabulário de nomes de arquivo do pacote: `orb_<estado>_<frame>.svg`, as cinco peças `keyboard_*.svg` e `manifest.json`
 * (FORMAT.md §2). Tudo minúsculo; o `<frame>` aceita zeros à esquerda (`1`, `01`, `0001`) e vale pelo número. Puro (JVM).
 */
object SkinPackNames {
    const val MANIFEST = "manifest.json"

    /** Dígitos demais viram `NumberFormatException`; nenhum pacote real passa de 64 frames por estado. */
    private const val MAX_FRAME_DIGITS = 9

    private val ORB_FRAME = Regex("""orb_([a-z]+)_([0-9]+)\.svg""")
    private val RESERVED = Regex("""(?:keyboard_fx_.*|orb_error_.*|orb_cancelling_.*)""")

    /** Classifica o NOME DO ARQUIVO (sem pasta). */
    fun classify(fileName: String): PackFileKind {
        if (fileName == MANIFEST) return PackFileKind.Manifest
        KeyboardPart.ofFileName(fileName)?.let { return PackFileKind.KeyboardFace(it) }
        if (RESERVED.matches(fileName)) return PackFileKind.Reserved
        val orb = ORB_FRAME.matchEntire(fileName)
        if (orb != null) {
            val state = OrbPackState.ofKey(orb.groupValues[1])
            if (state != null) {
                val digits = orb.groupValues[2]
                if (digits.length > MAX_FRAME_DIGITS) return PackFileKind.InvalidOrbFrame(tooLarge = true)
                val frame = digits.toInt()
                if (frame < 1) return PackFileKind.InvalidOrbFrame(tooLarge = false)
                return PackFileKind.OrbFrame(state, frame)
            }
        }
        val lower = fileName.lowercase()
        return PackFileKind.Unknown(lowercaseWouldMatch = lower != fileName && classify(lower) !is PackFileKind.Unknown)
    }

    /** Nome com o qual o frame é instalado: o número sem ambiguidade, com quatro dígitos (`orb_idle_0007.svg`). */
    fun frameFileName(state: OrbPackState, frame: Int): String = "orb_${state.key}_${frame.toString().padStart(4, '0')}.svg"
}
