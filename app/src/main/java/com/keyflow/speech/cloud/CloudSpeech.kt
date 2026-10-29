package com.keyflow.speech.cloud

import com.keyflow.auth.HttpErrorMessage
import com.keyflow.data.models.Ipv4FirstDns
import com.keyflow.i18n.tr
import com.keyflow.speech.SpeechClock
import com.keyflow.speech.SpeechError
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechFormat
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.decodeBase64
import org.json.JSONException
import org.json.JSONObject

/** O que o corpo da resposta de um provedor carrega. */
internal sealed interface AudioBody {
    /** PCM cru 16-bit mono little-endian, lido em fluxo. */
    data class RawPcm(val sampleRate: Int) : AudioBody

    /** WAV com cabeçalho, lido inteiro. */
    data object Wav : AudioBody

    /** mp3, opus, aac ou flac, decodificado inteiro. */
    data class Compressed(val format: SpeechFormat, val decoder: CompressedAudioDecoder) : AudioBody

    /** JSON do Google Cloud TTS: `audioContent` em base64, com cabeçalho WAV (LINEAR16). */
    data object GoogleJson : AudioBody
}

/** O pedido HTTP de um provedor, ou a falha antes de sair da tela (sem chave). */
internal sealed interface BuiltRequest {
    class Ready(val request: Request) : BuiltRequest
    class Failed(val event: SpeechEvent.Error) : BuiltRequest
}

/**
 * O miolo HTTP comum a OpenAI, ElevenLabs, Google e Custom (FCLD-02..07): faz a chamada, mapeia o status, lê o corpo e emite os eventos de
 * áudio. Cancelar a coleta cancela a chamada de verdade. Corpo de erro HTTP nunca vai para a mensagem.
 */
internal class CloudSpeech(
    http: OkHttpClient,
    private val service: String,
    private val nowMs: () -> Long,
) {
    private val client: OkHttpClient = http.newBuilder()
        .dns(Ipv4FirstDns())
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun run(build: suspend () -> BuiltRequest, body: AudioBody): Flow<SpeechEvent> = channelFlow {
        val clock = SpeechClock(nowMs)
        val request = when (val built = build()) {
            is BuiltRequest.Failed -> { send(built.event); return@channelFlow }
            is BuiltRequest.Ready -> built.request
        }
        val call = client.newCall(request)
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            withContext(Dispatchers.IO) { execute(call, body, clock) }
        } finally {
            handle.dispose()
        }
    }

    private suspend fun ProducerScope<SpeechEvent>.execute(call: okhttp3.Call, body: AudioBody, clock: SpeechClock) {
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (call.isCanceled()) throw CancellationException("cancelado")
            send(SpeechEvent.Error(SpeechError.NETWORK, tr("Sem conexão com %s.", service)))
            return
        }
        response.use { r ->
            if (!r.isSuccessful) {
                send(httpError(r))
                return
            }
            val failed = try {
                read(r, body, clock)
            } catch (e: IOException) {
                if (call.isCanceled()) throw CancellationException("cancelado")
                send(SpeechEvent.Error(SpeechError.NETWORK, tr("Sem conexão com %s.", service)))
                return
            }
            if (failed != null) send(failed) else send(clock.done())
        }
    }

    /** Lê o corpo emitindo [SpeechEvent.Audio]; devolve o erro, ou nulo no sucesso. */
    private suspend fun ProducerScope<SpeechEvent>.read(r: Response, body: AudioBody, clock: SpeechClock): SpeechEvent.Error? {
        val notAudio = SpeechEvent.Error(SpeechError.ENGINE, tr("A resposta não é áudio nesse formato."))
        val bodyBytes = r.body ?: return notAudio
        if (body !is AudioBody.GoogleJson) {
            val type = bodyBytes.contentType()
            if (type != null && (type.subtype.contains("json") || type.type == "text")) return notAudio
        }
        when (body) {
            is AudioBody.RawPcm -> {
                val input = bodyBytes.byteStream()
                val buffer = ByteArray(CHUNK)
                var carry = -1
                while (true) {
                    val offset = if (carry >= 0) 1 else 0
                    if (carry >= 0) buffer[0] = carry.toByte()
                    val n = input.read(buffer, offset, CHUNK - offset)
                    if (n < 0) break
                    val total = offset + n
                    val usable = total - (total % 2)
                    carry = if (total % 2 == 1) buffer[total - 1].toInt() and 0xFF else -1
                    if (usable > 0) emit(buffer.copyOf(usable), body.sampleRate, clock)
                }
            }
            is AudioBody.Wav -> return emitWav(bodyBytes.bytes(), clock)
            is AudioBody.Compressed -> {
                val decoded = body.decoder.decode(bodyBytes.bytes(), body.format) ?: return notAudio
                emit(decoded.pcm, decoded.sampleRate, clock)
            }
            is AudioBody.GoogleJson -> {
                val audio = try {
                    JSONObject(bodyBytes.string()).optString("audioContent").decodeBase64()?.toByteArray()
                } catch (e: JSONException) {
                    null
                } ?: return notAudio
                return emitWav(audio, clock)
            }
        }
        return null
    }

    private suspend fun ProducerScope<SpeechEvent>.emitWav(bytes: ByteArray, clock: SpeechClock): SpeechEvent.Error? {
        val info = WavAudio.parse(bytes) ?: return SpeechEvent.Error(SpeechError.ENGINE, tr("A resposta não é áudio nesse formato."))
        emit(WavAudio.toMono(bytes, info), info.sampleRate, clock)
        return null
    }

    private suspend fun ProducerScope<SpeechEvent>.emit(pcm: ByteArray, sampleRate: Int, clock: SpeechClock) {
        val first = clock.onAudio(pcm, sampleRate)
        send(SpeechEvent.Audio(pcm, sampleRate))
        if (first != null) send(first)
    }

    private fun httpError(r: Response): SpeechEvent.Error {
        // O corpo só serve para o código curto de HttpErrorMessage (e o detalhe no debug); nunca vai inteiro para a tela.
        val raw = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
        return when (r.code) {
            401, 403 -> SpeechEvent.Error(SpeechError.UNAUTHORIZED, tr("%s recusou a chave ou as credenciais.", service))
            429 -> SpeechEvent.Error(SpeechError.RATE_LIMIT, tr("Limite de uso de %s atingido. Tente mais tarde.", service))
            else -> SpeechEvent.Error(SpeechError.ENGINE, HttpErrorMessage.of(service, r.code, raw))
        }
    }

    private companion object {
        const val CHUNK = 4800
    }
}

internal fun missingKey(): BuiltRequest.Failed =
    BuiltRequest.Failed(SpeechEvent.Error(SpeechError.UNAUTHORIZED, tr("Conecte nesta aba")))

internal fun badCredentials(): BuiltRequest.Failed =
    BuiltRequest.Failed(SpeechEvent.Error(SpeechError.UNAUTHORIZED, tr("A credencial tem caracteres que não podem ser enviados.")))

/** Limita a velocidade ao intervalo que a API aceita, sem virar erro. */
internal fun Float.clampTo(min: Float, max: Float): Float = coerceIn(min, max)
