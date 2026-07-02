package com.keyflow.transcription

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ErrorKind
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.Config
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class TranscriptionClientTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `transcribe returns success text for 200 response`() {
        server.enqueue(jsonResponse(200, """{"text":"hello"}"""))

        val result = client().transcribe(audioFile(), config())

        assertThat(result).isEqualTo(TranscriptionResult.Success("hello"))
    }

    @Test
    fun `transcribe sends configured auth header and multipart fields`() {
        server.enqueue(jsonResponse(200, """{"text":"ok"}"""))

        client().transcribe(audioFile(), config(language = "pt"))

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertThat(request.getHeader("X-Api-Key")).isEqualTo("secret")
        assertThat(request.path).isEqualTo("/audio")
        assertThat(request.getHeader("Content-Type")).contains("multipart/form-data")
        assertThat(body).contains("""name="file"""")
        assertThat(body).contains("sample.m4a")
        assertThat(body).contains("""name="model"""")
        assertThat(body).contains("whisper-1")
        assertThat(body).contains("""name="language"""")
        assertThat(body).contains("pt")
    }

    @Test
    fun `transcribe maps 401 to auth error`() {
        server.enqueue(jsonResponse(401, """{"error":"unauthorized"}"""))

        val result = client().transcribe(audioFile(), config())

        assertError(result, ErrorKind.AUTH, "HTTP 401")
    }

    @Test
    fun `transcribe maps 403 to auth error`() {
        server.enqueue(jsonResponse(403, """{"error":"forbidden"}"""))

        val result = client().transcribe(audioFile(), config())

        assertError(result, ErrorKind.AUTH, "HTTP 403")
    }

    @Test
    fun `transcribe maps 413 to too large error`() {
        server.enqueue(jsonResponse(413, """{"error":"too large"}"""))

        val result = client().transcribe(audioFile(), config())

        assertError(result, ErrorKind.TOO_LARGE, "HTTP 413")
    }

    @Test
    fun `transcribe maps other unsuccessful responses to http error`() {
        server.enqueue(jsonResponse(500, """{"error":"server"}"""))

        val result = client().transcribe(audioFile(), config())

        assertError(result, ErrorKind.HTTP, "HTTP 500")
    }

    @Test
    fun `transcribe maps timeout to timeout error`() {
        server.enqueue(
            jsonResponse(200, """{"text":"slow"}""")
                .setBodyDelay(250, TimeUnit.MILLISECONDS)
        )
        val timeoutClient = client(readTimeoutMs = 50)

        val result = timeoutClient.transcribe(audioFile(), config())

        assertThat((result as TranscriptionResult.Error).kind).isEqualTo(ErrorKind.TIMEOUT)
    }

    @Test
    fun `transcribe honors cfg timeoutMs as a call timeout even with a generous base client`() {
        // Bug real (Yan, 2026-07-01): o client base tinha readTimeout generoso, mas nada limitava a
        // chamada inteira (ex.: DNS sem timeout no OkHttp/JVM) — o cfg.timeoutMs precisa governar por
        // requisição, sobrepondo o que o client base tiver configurado.
        server.enqueue(
            jsonResponse(200, """{"text":"slow"}""")
                .setBodyDelay(300, TimeUnit.MILLISECONDS)
        )
        val generousClient = client(readTimeoutMs = 10_000) // não dispararia sozinho em 300ms

        val result = generousClient.transcribe(audioFile(), config(timeoutMs = 50))

        assertThat((result as TranscriptionResult.Error).kind).isEqualTo(ErrorKind.TIMEOUT)
    }

    @Test
    fun `transcribe maps incomplete config before network call`() {
        val result = client().transcribe(audioFile(), Config())

        assertError(result, ErrorKind.CONFIG_MISSING, "Config incomplete")
        assertThat(server.requestCount).isEqualTo(0)
    }

    private fun client(readTimeoutMs: Long = 5_000): TranscriptionClient {
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return TranscriptionClient(WhisperApi(okHttp))
    }

    private fun config(language: String? = null, timeoutMs: Long = Config.DEFAULT_TIMEOUT_MS) = Config(
        endpointUrl = server.url("/audio").toString(),
        authHeaderName = "X-Api-Key",
        authHeaderValue = "secret",
        language = language,
        timeoutMs = timeoutMs,
    )

    private fun audioFile(): File = temp.newFile("sample.m4a").apply {
        writeBytes(byteArrayOf(1, 2, 3))
    }

    private fun jsonResponse(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun assertError(result: TranscriptionResult, kind: ErrorKind, message: String) {
        val error = result as TranscriptionResult.Error
        assertThat(error.kind).isEqualTo(kind)
        assertThat(error.message).contains(message)
    }
}
