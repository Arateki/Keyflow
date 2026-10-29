package com.keyflow.speech

/** Formatos de áudio do Custom (FUX-04). `pcm` cru assume 24 kHz, 16-bit, mono. */
enum class SpeechFormat(val wire: String) {
    MP3("mp3"), OPUS("opus"), AAC("aac"), FLAC("flac"), WAV("wav"), PCM("pcm");

    companion object {
        val DEFAULT = WAV
        val wires: List<String> = entries.map { it.wire }
        fun of(wire: String): SpeechFormat = entries.firstOrNull { it.wire == wire.trim().lowercase() } ?: DEFAULT
    }
}
