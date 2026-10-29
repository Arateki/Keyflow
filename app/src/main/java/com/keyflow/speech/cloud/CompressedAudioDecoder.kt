package com.keyflow.speech.cloud

import com.keyflow.speech.SpeechFormat

/** PCM 16-bit mono já decodificado. */
class DecodedPcm(val pcm: ByteArray, val sampleRate: Int)

/** Decodifica mp3, opus, aac e flac (FCLD-05). A implementação do app usa `MediaCodec`; os testes de JVM usam um falso. */
fun interface CompressedAudioDecoder {
    /** Nulo = os bytes não são áudio nesse formato. */
    fun decode(bytes: ByteArray, format: SpeechFormat): DecodedPcm?

    companion object {
        val Unsupported = CompressedAudioDecoder { _, _ -> null }
    }
}
