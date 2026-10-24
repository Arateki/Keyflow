package com.keyflow.auth.oauth

import com.google.common.truth.Truth.assertThat
import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import java.net.ConnectException
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * AUTH-08: o receptor do redirect por loopback (`http://127.0.0.1:<porta>/auth/callback`), com sockets reais. Uma requisição
 * `GET` no caminho exato, só do próprio aparelho; o resto é recusado sem derrubar a espera (Review Focus 1).
 */
class LoopbackRedirectReceiverTest {

    private val receivers = mutableListOf<LoopbackRedirectReceiver>()

    private fun receiver(path: String = "/auth/callback") = LoopbackRedirectReceiver(path).also { receivers += it }

    @After fun tearDown() = receivers.forEach { it.close() }

    private fun port(r: LoopbackRedirectReceiver) = URI(r.redirectUri).port

    /** Manda [raw] a `127.0.0.1:porta` e devolve a resposta inteira (a linha de status e o corpo). */
    private fun send(r: LoopbackRedirectReceiver, raw: String): String =
        Socket("127.0.0.1", port(r)).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply { write(raw.toByteArray()); flush() }
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    private fun get(r: LoopbackRedirectReceiver, target: String) = send(r, "GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

    @Test
    fun `it binds only to the loopback address`() {
        val r = receiver()
        assertThat(r.boundAddress.isLoopbackAddress).isTrue()
        assertThat(r.redirectUri).startsWith("http://127.0.0.1:")
        assertThat(r.redirectUri).endsWith("/auth/callback")
        assertThat(port(r)).isGreaterThan(0)
    }

    @Test
    fun `the redirect uri never changes while the receiver lives`() {
        val r = receiver()
        assertThat(r.redirectUri).isEqualTo(r.redirectUri)
        assertThat(receiver().redirectUri).isNotEqualTo(r.redirectUri) // outra instância, outra porta
    }

    @Test
    fun `the callback returns the full uri with code and state and answers a 200 html page`() = runBlocking {
        val r = receiver()
        val waiting = async { r.await(5_000) }

        val response = get(r, "/auth/callback?code=abc&state=xyz&client_id=oaiapp_1")
        val uri = waiting.await()

        assertThat(response).startsWith("HTTP/1.1 200")
        assertThat(response).contains("text/html")
        assertThat(response).contains("KeyFlow")
        assertThat(uri.path).isEqualTo("/auth/callback")
        assertThat(uri.query).isEqualTo("code=abc&state=xyz&client_id=oaiapp_1")
        assertThat(uri.host).isEqualTo("127.0.0.1")
        assertThat(uri.port).isEqualTo(port(r))
    }

    @Test
    fun `an error callback is delivered untouched — the login flow decides what it means`() = runBlocking {
        val r = receiver()
        val waiting = async { r.await(5_000) }

        get(r, "/auth/callback?error=access_denied&state=xyz")

        assertThat(waiting.await().query).isEqualTo("error=access_denied&state=xyz")
    }

    /** Review Focus 1: outro caminho recebe 404 e a espera continua para o callback verdadeiro. */
    @Test
    fun `another path is a 404 and keeps waiting`() = runBlocking {
        val r = receiver()
        val waiting = async { r.await(5_000) }

        assertThat(get(r, "/favicon.ico")).startsWith("HTTP/1.1 404")
        assertThat(get(r, "/callback?code=1&state=2")).startsWith("HTTP/1.1 404") // "/callback" NÃO casa com "/auth/callback"
        assertThat(waiting.isCompleted).isFalse()

        get(r, "/auth/callback?code=ok&state=s")
        assertThat(waiting.await().query).isEqualTo("code=ok&state=s")
    }

    @Test
    fun `a non GET request is a 405 and keeps waiting`() = runBlocking {
        val r = receiver()
        val waiting = async { r.await(5_000) }

        assertThat(send(r, "POST /auth/callback?code=x HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n"))
            .startsWith("HTTP/1.1 405")
        assertThat(waiting.isCompleted).isFalse()

        get(r, "/auth/callback?code=ok&state=s")
        assertThat(waiting.await().query).isEqualTo("code=ok&state=s")
    }

    @Test
    fun `after the first valid callback a second one is refused`() = runBlocking {
        val r = receiver()
        val waiting = async { r.await(5_000) }
        get(r, "/auth/callback?code=1&state=s")
        waiting.await()
        delay(100) // o servidor fecha logo depois de responder

        assertThrows(ConnectException::class.java) { Socket("127.0.0.1", port(r)).close() }
        Unit // o JUnit exige método void: assertThrows devolve a exceção
    }

    /** O navegador abre conexões especulativas sem mandar nada: uma não pode travar a verdadeira. */
    @Test
    fun `an idle connection does not block the real callback`() = runBlocking {
        val r = receiver()
        val waiting = async { r.await(5_000) }
        val idle = Socket("127.0.0.1", port(r)) // conecta e não diz nada
        try {
            // O ciclo INTEIRO (pedido + resposta + entrega) tem de ser rápido: se as conexões fossem atendidas em série, a
            // ociosa seguraria a fila até o prazo de leitura (3 s) — e o `await` sozinho, já completo, não mostraria isso.
            val started = System.nanoTime()
            get(r, "/auth/callback?code=ok&state=s")
            val uri = waiting.await()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertThat(uri.query).isEqualTo("code=ok&state=s")
            assertThat(elapsedMs).isLessThan(1_500L)
        } finally {
            idle.close()
        }
    }

    @Test
    fun `an oversized request line is rejected and keeps waiting`() = runBlocking {
        val r = receiver()
        val waiting = async { r.await(5_000) }

        val reply = runCatching { get(r, "/auth/callback?" + "a".repeat(20_000)) }.getOrDefault("")
        assertThat(reply).doesNotContain("HTTP/1.1 200")
        assertThat(waiting.isCompleted).isFalse()

        get(r, "/auth/callback?code=ok&state=s")
        assertThat(waiting.await().query).isEqualTo("code=ok&state=s")
    }

    @Test
    fun `a garbage request is ignored and keeps waiting`() = runBlocking {
        val r = receiver()
        val waiting = async { r.await(5_000) }

        runCatching { send(r, "\u0000\u0001\u0002 isto não é http\r\n\r\n") }
        assertThat(waiting.isCompleted).isFalse()

        get(r, "/auth/callback?code=ok&state=s")
        assertThat(waiting.await().query).isEqualTo("code=ok&state=s")
    }

    @Test
    fun `await times out with the timeout error kind`() {
        val r = receiver()
        val failure = assertThrows(AuthException::class.java) { runBlocking { r.await(200) } }
        assertThat(failure.kind).isEqualTo(AuthErrorKind.TIMEOUT)
    }

    @Test
    fun `closing cancels a pending await and frees the port`() = runBlocking {
        val r = receiver()
        val waiting = async { runCatching { r.await(30_000) } }
        delay(100)

        r.close()

        val outcome = withTimeout(2_000) { waiting.await() }
        assertThat(outcome.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        // Reivindica a porta liberada: uma conexão nova pode atingir outro listener que a reutilizou.
        // O accept nativo também pode levar alguns instantes para concluir o fechamento.
        withTimeout(2_000) {
            while (true) {
                try {
                    ServerSocket(port(r), 1, InetAddress.getByName("127.0.0.1")).use { }
                    break
                } catch (_: BindException) {
                    delay(10)
                }
            }
        }
        Unit
    }

    @Test
    fun `closing twice is harmless`() {
        val r = receiver()
        r.close()
        r.close()
    }
}
