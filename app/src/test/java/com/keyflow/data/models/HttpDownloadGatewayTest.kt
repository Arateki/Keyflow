package com.keyflow.data.models

import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** A fila de downloads que substitui o DownloadManager (mesmo contrato do [DownloadGateway]). */
class HttpDownloadGatewayTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer().apply { start() }
    private val body = ByteArray(200_000) { (it % 253).toByte() }
    private val finished = CopyOnWriteArrayList<String>()
    @Volatile private var net = NetState.OK
    private val gateways = mutableListOf<HttpDownloadGateway>()

    private val listener = object : DownloadListener {
        override fun onFileFinished(modelId: String) {
            finished += modelId
        }
    }

    private fun gateway(): HttpDownloadGateway =
        HttpDownloadGateway(File(tmp.root, "estado.json"), network = { net }, listener = listener, waitMs = 50)
            .also { gateways += it }

    private fun destination(model: String = "modelo", name: String = "arquivo.bin") =
        File(tmp.root, "$model/$name").also { it.parentFile!!.mkdirs() }

    private fun url() = server.url("/arquivo.bin").toString()

    @After
    fun stop() {
        gateways.forEach { it.shutdownForTest() }
        server.shutdown()
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("não aconteceu: $what")
    }

    @Test
    fun `an enqueued file downloads to its destination`() {
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val gateway = gateway()
        val dest = destination()

        val id = gateway.enqueue(url(), dest, "Teste (1/1)", allowMetered = false)
        waitFor("concluir") { gateway.query(listOf(id)).getValue(id).status == RowStatus.SUCCESSFUL }

        assertThat(dest.readBytes()).isEqualTo(body)
        assertThat(gateway.query(listOf(id)).getValue(id).bytesSoFar).isEqualTo(body.size.toLong())
        // O worker publica SUCCESSFUL antes de chamar o listener; são dois sinais distintos.
        waitFor("notificar conclusão") { finished.contains("modelo") }
        assertThat(finished).containsExactly("modelo")
    }

    @Test
    fun `progress shows while the file is coming`() {
        server.enqueue(MockResponse().setBody(Buffer().write(body)).throttleBody(8_192, 50, TimeUnit.MILLISECONDS))
        val gateway = gateway()

        val id = gateway.enqueue(url(), destination(), "Teste", allowMetered = false)

        waitFor("andar") { gateway.query(listOf(id)).getValue(id).let { it.status == RowStatus.RUNNING && it.bytesSoFar > 0 } }
    }

    @Test
    fun `removing a download mid-way stops it and deletes what arrived`() {
        server.enqueue(MockResponse().setBody(Buffer().write(body)).throttleBody(8_192, 50, TimeUnit.MILLISECONDS))
        val gateway = gateway()
        val dest = destination()
        val id = gateway.enqueue(url(), dest, "Teste", allowMetered = false)
        waitFor("andar") { gateway.query(listOf(id)).getValue(id).bytesSoFar > 0 }

        gateway.remove(listOf(id))

        waitFor("parar") { dest.parentFile!!.listFiles()!!.isEmpty() }
        assertThat(gateway.query(listOf(id)).getValue(id).status).isEqualTo(RowStatus.FAILED)
    }

    @Test
    fun `after the process dies the next start resumes from the partial file`() {
        server.enqueue(MockResponse().setBody(Buffer().write(body)).throttleBody(8_192, 50, TimeUnit.MILLISECONDS))
        val first = gateway()
        val dest = destination()
        val id = first.enqueue(url(), dest, "Teste", allowMetered = false)
        waitFor("andar") { first.query(listOf(id)).getValue(id).bytesSoFar > 20_000 }
        first.shutdownForTest() // o processo "morreu": nada é apagado
        val partial = File(dest.path + ".part").length()
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $partial-${body.size - 1}/${body.size}")
                .setBody(Buffer().write(body.copyOfRange(partial.toInt(), body.size))),
        )

        val second = gateway()
        waitFor("concluir na volta") { second.query(listOf(id)).getValue(id).status == RowStatus.SUCCESSFUL }

        server.takeRequest()
        assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=$partial-")
        assertThat(dest.readBytes()).isEqualTo(body)
    }

    @Test
    fun `without network it waits, and goes on when the network comes back`() {
        net = NetState.NONE
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val gateway = gateway()

        val id = gateway.enqueue(url(), destination(), "Teste", allowMetered = false)
        waitFor("esperar rede") { gateway.query(listOf(id)).getValue(id).pauseReason == PauseReason.WAITING_FOR_NETWORK }
        net = NetState.OK

        waitFor("concluir") { gateway.query(listOf(id)).getValue(id).status == RowStatus.SUCCESSFUL }
    }

    @Test
    fun `on mobile data it waits for wifi unless mobile data was allowed`() {
        net = NetState.METERED
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val gateway = gateway()

        val waiting = gateway.enqueue(url(), destination("a"), "Teste", allowMetered = false)
        waitFor("esperar Wi-Fi") { gateway.query(listOf(waiting)).getValue(waiting).pauseReason == PauseReason.WAITING_FOR_WIFI }
        gateway.remove(listOf(waiting))

        val allowed = gateway.enqueue(url(), destination("b"), "Teste", allowMetered = true)
        waitFor("concluir por dados") { gateway.query(listOf(allowed)).getValue(allowed).status == RowStatus.SUCCESSFUL }
    }

    @Test
    fun `a missing file fails at once and the model hears about it`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val gateway = gateway()

        val id = gateway.enqueue(url(), destination(), "Teste", allowMetered = false)

        waitFor("falhar") { gateway.query(listOf(id)).getValue(id).status == RowStatus.FAILED }
        waitFor("notificar falha ao modelo") { finished.contains("modelo") }
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(finished).containsExactly("modelo")
    }

    @Test
    fun `an unknown request reads as failed, like one cleared from the system`() {
        assertThat(gateway().query(listOf(999L)).getValue(999L).status).isEqualTo(RowStatus.FAILED)
    }
}
