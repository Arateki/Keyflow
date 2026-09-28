package com.keyflow.data.models

import com.google.common.truth.Truth.assertThat
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress

/** Baixar um arquivo com retomada (Range) — o que o DownloadManager fazia, agora do nosso lado. */
class HttpFileFetcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer().apply { start() }
    private val fetcher = HttpFileFetcher()
    private val body = ByteArray(50_000) { (it % 251).toByte() }

    @After
    fun stop() = server.shutdown()

    private fun part(): File = File(tmp.root, "arquivo.bin.part")

    private fun fetch(part: File = part(), cancelled: () -> Boolean = { false }) =
        fetcher.fetch(server.url("/arquivo.bin").toString(), part, cancelled) { _, _ -> }

    @Test
    fun `a whole file lands in the part file`() {
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        assertThat(fetch()).isEqualTo(FetchResult.Done)
        assertThat(part().readBytes()).isEqualTo(body)
    }

    @Test
    fun `a partial file resumes from where it stopped`() {
        part().writeBytes(body.copyOfRange(0, 10_000))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 10000-49999/50000")
                .setBody(Buffer().write(body.copyOfRange(10_000, body.size))),
        )

        assertThat(fetch()).isEqualTo(FetchResult.Done)
        assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=10000-")
        assertThat(part().readBytes()).isEqualTo(body)
    }

    @Test
    fun `a server that ignores the range sends everything again, and the file starts over`() {
        part().writeBytes(ByteArray(10_000) { 9 })
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        assertThat(fetch()).isEqualTo(FetchResult.Done)
        assertThat(part().readBytes()).isEqualTo(body)
    }

    @Test
    fun `a resume that does not start where the file stopped is thrown away`() {
        part().writeBytes(body.copyOfRange(0, 10_000))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 20000-49999/50000")
                .setBody(Buffer().write(body.copyOfRange(20_000, body.size))),
        )

        assertThat(fetch()).isInstanceOf(FetchResult.Retry::class.java)
        assertThat(part().exists()).isFalse()
    }

    @Test
    fun `not found is final, a server error is worth another try`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(503))

        assertThat(fetch()).isEqualTo(FetchResult.Failed(404))
        assertThat(fetch()).isInstanceOf(FetchResult.Retry::class.java)
    }

    @Test
    fun `a connection that drops mid-body is worth another try and keeps what arrived`() {
        server.enqueue(
            MockResponse().setBody(Buffer().write(body)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        assertThat(fetch()).isInstanceOf(FetchResult.Retry::class.java)
        assertThat(part().length()).isLessThan(body.size.toLong())
    }

    @Test
    fun `cancelling stops the download`() {
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        assertThat(fetch(cancelled = { true })).isEqualTo(FetchResult.Cancelled)
    }

    @Test
    fun `ipv4 addresses are tried before ipv6`() {
        val v6 = InetAddress.getByName("2001:db8::1")
        val v4 = InetAddress.getByName("192.0.2.1")
        val dns = Ipv4FirstDns(object : Dns { override fun lookup(hostname: String) = listOf(v6, v4) })

        assertThat(dns.lookup("exemplo")).containsExactly(v4, v6).inOrder()
    }
}
