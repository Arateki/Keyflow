package com.keyflow.auth.providers.openai

import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.data.models.Ipv4FirstDns
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/** O cliente HTTP que fala com a OpenAI (login e modelos), derivado do cliente base do app. */
internal object OpenAiHttp {
    /**
     * IPv4 antes do IPv6 — em rede com IPv6 quebrado (a do Yan, medido em 2026-09-27) quem tenta o IPv6 primeiro espera cada
     * tentativa estourar, por minutos — e um prazo para a chamada INTEIRA (DNS + conexão + leitura), que `connectTimeout` e
     * `readTimeout` sozinhos não cobrem. Divide o pool de conexões com o cliente base. Chamadas em fluxo (o motor de
     * geração) derivam deste e tiram o prazo, porque uma resposta longa não pode ser cortada.
     */
    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .dns(Ipv4FirstDns())
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
}

/**
 * Uma chamada OkHttp que suspende e cancela junto com a corrotina (nada de thread presa esperando a rede).
 *
 * O corpo é lido AQUI, em `Dispatchers.IO`, e a [Response] volta com ele já em memória: a corrotina retoma na thread de quem
 * chamou (a main, no login e na renovação), e ler o corpo ali bloquearia a interface — e em HTTP/1.1 lançaria
 * `NetworkOnMainThreadException` sob StrictMode. Um cancelamento fecha a resposta em vez de vazá-la.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun Call.await(): Response {
    val response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, _, _ -> response.close() }
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        })
    }
    try {
        withContext(Dispatchers.IO) { response.body?.source()?.request(Long.MAX_VALUE) }
    } catch (e: Throwable) {
        response.close()
        throw e
    }
    return response
}

/** `POST` de formulário (`application/x-www-form-urlencoded`), como o token endpoint e a revogação esperam. */
internal suspend fun OkHttpClient.postForm(url: String, form: Map<String, String>): Response {
    val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
    return newCall(Request.Builder().url(url).header("Accept", "application/json").post(body).build()).await()
}

/**
 * `GET` de um JSON. Falha de rede ou 5xx → `NETWORK` (temporário); qualquer outra resposta que não seja um JSON válido →
 * `INVALID_RESPONSE`. A mensagem nunca leva o corpo da resposta.
 */
internal suspend fun OkHttpClient.getJson(url: String): JSONObject {
    val response = try {
        newCall(Request.Builder().url(url).header("Accept", "application/json").build()).await()
    } catch (e: IOException) {
        throw AuthException(AuthErrorKind.NETWORK, "sem conexão com o provedor", e)
    }
    response.use { r ->
        if (r.code >= 500) throw AuthException(AuthErrorKind.NETWORK, "o provedor respondeu HTTP ${r.code}")
        if (!r.isSuccessful) throw AuthException(AuthErrorKind.INVALID_RESPONSE, "o provedor respondeu HTTP ${r.code}")
        return parseJson(r.body?.string().orEmpty())
    }
}

internal fun parseJson(text: String): JSONObject = try {
    JSONObject(text)
} catch (e: JSONException) {
    throw AuthException(AuthErrorKind.INVALID_RESPONSE, "resposta do provedor não é JSON")
}
