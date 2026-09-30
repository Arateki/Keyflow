package com.keyflow.auth.providers.openai

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Test

/**
 * Review, Important 5: o corpo da resposta é lido FORA da thread de quem chamou. O `await` volta na thread do chamador (a main,
 * no login e na renovação): ler o corpo ali bloqueia a interface e, em HTTP/1.1, é `NetworkOnMainThreadException` sob StrictMode.
 */
class OpenAiHttpTest {

    private companion object { const val CALLER = "chamador-teste" }

    private val server = MockWebServer().also { it.start() }
    private val bodyReaders = CopyOnWriteArrayList<String>()

    /** Um cliente que anota o nome da thread em que cada trecho do corpo é lido. */
    private val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        val original = response.body!!
        val spy: BufferedSource = object : ForwardingSource(original.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val n = super.read(sink, byteCount)
                // Só a leitura que TRAZ bytes é I/O; a consulta de fim de fluxo (-1) não bloqueia e não conta.
                if (n > 0) bodyReaders += Thread.currentThread().name
                return n
            }
        }.buffer()
        response.newBuilder().body(object : ResponseBody() {
            override fun contentType(): MediaType? = original.contentType()
            override fun contentLength(): Long = original.contentLength()
            override fun source(): BufferedSource = spy
        }).build()
    }.build()

    private val caller = Executors.newSingleThreadExecutor { Thread(it, CALLER) }.asCoroutineDispatcher()

    @After fun tearDown() {
        server.shutdown()
        caller.close()
    }

    @Test
    fun `getJson reads the body off the calling thread`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true}"""))

        val json = runBlocking(caller) { client.getJson(server.url("/x").toString()) }

        assertThat(json.getBoolean("ok")).isTrue()
        assertThat(bodyReaders).isNotEmpty()
        assertThat(bodyReaders.filter { it.startsWith(CALLER) }).isEmpty() // o depurador de corrotinas acrescenta " @coroutine#N" ao nome
    }

    @Test
    fun `postForm hands back a body that is already read, so reading it on the calling thread touches no network`() {
        server.enqueue(MockResponse().setBody("""{"access_token":"a"}"""))

        val text = runBlocking(caller) { client.postForm(server.url("/token").toString(), mapOf("a" to "b")).use { it.body!!.string() } }

        assertThat(text).contains("access_token")
        assertThat(bodyReaders).isNotEmpty()
        assertThat(bodyReaders.filter { it.startsWith(CALLER) }).isEmpty() // o depurador de corrotinas acrescenta " @coroutine#N" ao nome
    }
}
