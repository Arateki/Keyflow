package com.keyflow.speech.cloud

import com.keyflow.speech.SpeechEngine
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechRequest
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okio.Buffer

internal fun SpeechEngine.collect(text: String = "Olá", speed: Float = 1f): List<SpeechEvent> =
    runBlocking { withTimeout(20_000) { speak(SpeechRequest(text, speed)).toList() } }

internal fun List<SpeechEvent>.pcmBytes(): Int = filterIsInstance<SpeechEvent.Audio>().sumOf { it.pcm.size }

internal fun pcmResponse(bytes: Int, type: String = "audio/pcm") =
    MockResponse().setHeader("Content-Type", type).setBody(Buffer().write(ByteArray(bytes)))

/** Um WAV mono/estéreo 16-bit de teste com [frames] quadros de valor constante. */
internal fun wavBytes(frames: Int, rate: Int = 24_000, channels: Int = 1, value: Int = 1000, dataSizeField: Int? = null): ByteArray {
    val dataLen = frames * channels * 2
    val out = ByteArrayOutputStream()
    fun le32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
    fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
    out.write("RIFF".toByteArray()); le32(36 + dataLen); out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); le32(16); le16(1); le16(channels); le32(rate); le32(rate * channels * 2); le16(channels * 2); le16(16)
    out.write("data".toByteArray()); le32(dataSizeField ?: dataLen)
    repeat(frames * channels) { le16(value) }
    return out.toByteArray()
}
