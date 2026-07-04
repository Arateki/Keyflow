package com.keyflow.transcription

import com.keyflow.core.ErrorKind
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.Config
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

class TranscriptionClient(private val api: WhisperApi = WhisperApi()) {

    // Chamada em andamento (se houver) — guardada pra permitir cancelar de outra thread (cancelar durante
    // SENDING, Yan 2026-07-03). @Volatile: escrita no dispatcher IO (dentro de post()), leitura do
    // cancel() a partir da coroutine no Main.
    @Volatile
    private var activeCall: okhttp3.Call? = null

    /** Aborta a requisição em andamento, se houver. [transcribe] resolve com um erro de rede/IO — quem
     * chama já sabe que cancelou e descarta esse resultado (ver [com.keyflow.overlay.OverlayCoordinator]). */
    fun cancel() {
        activeCall?.cancel()
    }

    fun transcribe(audio: File, cfg: Config): TranscriptionResult {
        if (!cfg.isComplete()) {
            return TranscriptionResult.Error(ErrorKind.CONFIG_MISSING, "Config incomplete")
        }

        return try {
            api.post(audio, cfg) { call -> activeCall = call }.use { response ->
                when {
                    response.code == 401 || response.code == 403 ->
                        TranscriptionResult.Error(ErrorKind.AUTH, "HTTP ${response.code}")
                    response.code == 413 ->
                        TranscriptionResult.Error(ErrorKind.TOO_LARGE, "HTTP 413")
                    !response.isSuccessful ->
                        TranscriptionResult.Error(ErrorKind.HTTP, "HTTP ${response.code}")
                    else -> parseSuccess(response.body?.string().orEmpty())
                }
            }
        } catch (e: InterruptedIOException) {
            // Cobre tanto SocketTimeoutException (subtipo — connect/readTimeout) quanto o
            // InterruptedIOException "puro" que o OkHttp lança quando é o callTimeout (chamada inteira,
            // não um socket específico) que expira — achado ao testar cfg.timeoutMs por requisição.
            TranscriptionResult.Error(ErrorKind.TIMEOUT, e.message ?: "timeout")
        } catch (e: IOException) {
            // Cobre também o IOException("Canceled") do Call.cancel() — o coordinator já sabe que
            // cancelou e ignora esse resultado, então não precisa de um ErrorKind dedicado aqui.
            TranscriptionResult.Error(ErrorKind.NETWORK, e.message ?: "network error")
        } finally {
            activeCall = null
        }
    }

    private fun parseSuccess(body: String): TranscriptionResult {
        val rawText = TEXT_FIELD.find(body)?.groupValues?.get(1)
            ?: return TranscriptionResult.Error(ErrorKind.HTTP, "Missing text in response")
        return TranscriptionResult.Success(unescapeJsonString(rawText))
    }

    private fun unescapeJsonString(value: String): String {
        val result = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val char = value[i]
            if (char != '\\' || i == value.lastIndex) {
                result.append(char)
                i++
                continue
            }

            when (val escaped = value[++i]) {
                '"' -> result.append('"')
                '\\' -> result.append('\\')
                '/' -> result.append('/')
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    val hexStart = i + 1
                    val hexEnd = hexStart + 4
                    if (hexEnd <= value.length) {
                        result.append(value.substring(hexStart, hexEnd).toInt(16).toChar())
                        i += 4
                    } else {
                        result.append("\\u")
                    }
                }
                else -> result.append(escaped)
            }
            i++
        }
        return result.toString()
    }

    private companion object {
        val TEXT_FIELD = Regex(""""text"\s*:\s*"((?:\\.|[^"\\])*)"""")
    }
}
