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

    fun post(audioFile: File, cfg: Config): Response {
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

        return client.newCall(request).execute()
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
