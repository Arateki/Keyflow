package com.keyflow.auth.oauth

import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.auth.RedirectReceiver
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Recebe o redirect do login por **loopback** (`http://127.0.0.1:<porta><path>`): a OpenAI só aceita esse jeito (porta variável,
 * caminho fixo, nunca `localhost`). O navegador é outro processo do MESMO aparelho, então alcança este socket.
 *
 * Segurança e robustez (AUTH-08, Review Focus 1):
 * - liga só ao endereço de loopback; atende **uma** requisição válida (`GET` no caminho exato) e fecha logo depois;
 * - outro caminho → 404, outro método → 405, linha enorme ou lixo → recusado — e a espera CONTINUA para o callback de verdade;
 * - cada conexão tem o seu prazo de leitura: uma conexão ociosa (o navegador abre especulativas) não trava as outras;
 * - quem valida o `state` é o fluxo de login, não este receptor (ele só entrega a URL completa).
 */
class LoopbackRedirectReceiver(
    private val path: String,
    host: String = "127.0.0.1",
    private val uriHost: String = host,
) : RedirectReceiver {

    private val address: InetAddress = InetAddress.getByName(host).also { require(it.isLoopbackAddress) { "só loopback: $host" } }
    private val server = ServerSocket(0, BACKLOG, address)
    private val origin = "http://$uriHost:${server.localPort}"
    private val result = CompletableDeferred<URI>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val boundAddress: InetAddress get() = server.inetAddress

    override val redirectUri: String = origin + path

    init {
        scope.launch {
            while (isActive) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break // o servidor foi fechado
                }
                scope.launch { handle(socket) }
            }
        }
    }

    override suspend fun await(timeoutMs: Long): URI =
        withTimeoutOrNull(timeoutMs) { result.await() }
            ?: throw AuthException(AuthErrorKind.TIMEOUT, "o navegador não voltou a tempo")

    override fun close() {
        result.completeExceptionally(CancellationException("receptor fechado")) // no-op se já entregou
        scope.cancel()
        runCatching { server.close() }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = READ_TIMEOUT_MS
            val line = try {
                readRequestLine(s.getInputStream())
            } catch (e: IOException) {
                return // ocioso, cortado ou lixo: ignora e segue esperando
            }
            val out = s.getOutputStream()
            val parts = line?.split(' ')
            if (parts == null || parts.size != 3 || !parts[2].startsWith("HTTP/")) {
                respond(out, 400, "Bad Request", "Requisição inválida.")
                return
            }
            val (method, target) = parts
            when {
                method != "GET" -> respond(out, 405, "Method Not Allowed", "Só GET.", "Allow: GET\r\n")
                target.substringBefore('?') != path -> respond(out, 404, "Not Found", "Não encontrado.")
                else -> {
                    val uri = try {
                        URI(origin + target)
                    } catch (e: URISyntaxException) {
                        respond(out, 400, "Bad Request", "Endereço inválido.")
                        return
                    }
                    if (result.complete(uri)) {
                        respond(out, 200, "OK", DONE_PAGE)
                        // A requisição de verdade chegou: ninguém mais precisa deste porto.
                        runCatching { server.close() }
                    } else {
                        respond(out, 410, "Gone", "Este login já foi concluído.")
                    }
                }
            }
        }
    }

    /** A primeira linha do pedido (`GET /x HTTP/1.1`), lida até o `\n` com teto de [MAX_LINE] bytes; `null` se passar do teto. */
    private fun readRequestLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (bytes.size() >= MAX_LINE) return null
            bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8.name()).trimEnd('\r')
    }

    private fun respond(out: OutputStream, code: Int, reason: String, body: String, extraHeaders: String = "") {
        val html = if (body.startsWith("<")) body else "<!doctype html><meta charset=utf-8><title>KeyFlow</title><p>$body</p>"
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n$extraHeaders\r\n"
        try {
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
        } catch (e: IOException) {
            // O navegador desistiu antes de ler a resposta: o callback já foi entregue, não há o que fazer.
        }
    }

    private companion object {
        const val BACKLOG = 8
        const val READ_TIMEOUT_MS = 3_000
        const val MAX_LINE = 8_192
        const val DONE_PAGE = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
            "<title>KeyFlow</title><body style=\"font-family:sans-serif;text-align:center;margin-top:20vh\">" +
            "<h2>Pronto</h2><p>Pode voltar ao KeyFlow.</p></body>"
    }
}
