package com.keyflow.data.models

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Como terminou uma tentativa de baixar um arquivo. */
sealed interface FetchResult {
    data object Done : FetchResult
    data object Cancelled : FetchResult

    /** Vale tentar de novo: rede caiu, servidor ocupado, resposta cortada. */
    data class Retry(val reason: String) : FetchResult

    /** Não adianta repetir (404, 403…). */
    data class Failed(val httpCode: Int) : FetchResult
}

/**
 * Baixa um arquivo para um `.part`, retomando de onde ele parou (`Range`). Puro (JVM): quem decide
 * quando tentar de novo, esperar rede ou avisar a UI é o [HttpDownloadGateway].
 */
class HttpFileFetcher(private val client: OkHttpClient = defaultClient()) {

    /** Baixa [url] para [part]; [onProgress] recebe (bytes no arquivo, total ou -1), no máximo ~4×/s. */
    fun fetch(url: String, part: File, isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit): FetchResult {
        val have = if (part.isFile) part.length() else 0L
        val request = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        val call = client.newCall(request)
        return try {
            call.execute().use { response ->
                val append = when {
                    response.code == 416 && have > 0 -> return FetchResult.Done // já estava inteiro
                    response.code == 206 -> true
                    response.isSuccessful -> false // ignorou o Range (ou não havia): recomeça do zero
                    response.code == 408 || response.code == 429 || response.code >= 500 ->
                        return FetchResult.Retry("HTTP ${response.code}")
                    else -> return FetchResult.Failed(response.code)
                }
                if (append && rangeStart(response.header("Content-Range")) != have) {
                    part.delete()
                    return FetchResult.Retry("o servidor retomou de outro ponto")
                }
                val body = response.body ?: return FetchResult.Retry("resposta sem corpo")
                val start = if (append) have else 0L
                val total = body.contentLength().let { if (it >= 0) start + it else -1L }
                var done = start
                var lastReport = 0L
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            if (isCancelled()) {
                                call.cancel()
                                return FetchResult.Cancelled
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= PROGRESS_EVERY_MS) {
                                lastReport = now
                                onProgress(done, total)
                            }
                        }
                    }
                }
                onProgress(done, total)
                if (total >= 0 && done < total) FetchResult.Retry("resposta cortada em $done de $total bytes") else FetchResult.Done
            }
        } catch (e: IOException) {
            if (isCancelled()) FetchResult.Cancelled else FetchResult.Retry(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun rangeStart(contentRange: String?): Long? =
        contentRange?.let { Regex("""bytes (\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_EVERY_MS = 250L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(Ipv4FirstDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * IPv4 antes do IPv6. Em rede com IPv6 quebrado (medido na do Yan em 2026-09-27: endereço IPv6 atribuído,
 * nenhum pacote passa) o DownloadManager tentava o IPv6 primeiro e esperava cada tentativa estourar:
 * 2,5 min em 0 bytes antes de cair para o IPv4. O OkHttp 4 tenta os endereços em ordem, sem corrida
 * entre os dois (isso só existe no OkHttp 5); com o IPv4 na frente, o IPv6 só é tentado se ele falhar.
 */
class Ipv4FirstDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        delegate.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
}
