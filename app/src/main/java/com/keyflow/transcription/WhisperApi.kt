package com.keyflow.transcription

import com.keyflow.data.Config
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

class WhisperApi(private val client: OkHttpClient = defaultClient()) {

    /**
     * [onCall] entrega o [okhttp3.Call] antes do `execute()` bloquear — permite ao chamador guardar uma
     * referência e cancelar a requisição em andamento (`Call.cancel()`) a partir de outra thread.
     */
    fun post(audioFile: File, cfg: Config, onCall: (okhttp3.Call) -> Unit = {}): Response {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/m4a".toMediaType()))
            .addFormDataPart("model", cfg.model)
            .apply {
                cfg.language?.takeIf { it.isNotBlank() }?.let { addFormDataPart("language", it) }
            }
            .build()

        val request = Request.Builder()
            .url(cfg.endpointUrl)
            .addHeader(cfg.authHeaderName, cfg.authHeaderValue)
            .post(body)
            .build()

        // callTimeout envolve a chamada INTEIRA (DNS+conexão+escrita+leitura) — diferente de
        // connectTimeout/readTimeout, que não cobrem DNS sem timeout no OkHttp/JVM (causa real do bug de
        // travar "transcrevendo" pra sempre com rotas de rede ruins). newBuilder() reusa o pool de
        // conexões/dispatcher do client base; só o timeout muda por [cfg], que pode variar em runtime
        // (config do usuário), então não dá pra fixar isso na construção do client.
        // Sem limite ("∞"): callTimeout 0 já não corta a chamada, e o readTimeout do client base também
        // sai — senão um servidor que fica calado processando um áudio longo cairia nele.
        val callClient = client.newBuilder()
            .callTimeout(cfg.timeoutMs, TimeUnit.MILLISECONDS)
            .apply { if (cfg.waitsForever) readTimeout(0, TimeUnit.MILLISECONDS) }
            .build()
        val call = callClient.newCall(request)
        onCall(call)
        return call.execute()
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
