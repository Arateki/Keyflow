package com.keyflow.net

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class CloudflareAccessInterceptorTest {
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        servers.forEach { it.shutdown() }
    }

    @Test
    fun `cabeçalhos presentes com valores e host da API`() {
        val api = server()
        api.enqueue(MockResponse().setResponseCode(204))
        val client = client(apiUrl(api), "client-id", "client-secret")

        val response = call(client, api.url("/v1/config").toString())
        assertThat(response.code).isEqualTo(204)
        response.close()

        val recorded = api.requiredRequest()
        assertThat(recorded.getHeader(CloudflareAccessInterceptor.HEADER_ID)).isEqualTo("client-id")
        assertThat(recorded.getHeader(CloudflareAccessInterceptor.HEADER_SECRET)).isEqualTo("client-secret")
    }

    @Test
    fun `cabeçalhos ausentes quando os valores são vazios`() {
        val api = server()
        api.enqueue(MockResponse().setResponseCode(204))
        val client = client(apiUrl(api), "  ", "")

        call(client, api.url("/v1/account").toString()).close()

        val recorded = api.requiredRequest()
        assertThat(recorded.getHeader(CloudflareAccessInterceptor.HEADER_ID)).isNull()
        assertThat(recorded.getHeader(CloudflareAccessInterceptor.HEADER_SECRET)).isNull()
    }

    @Test
    fun `um valor só não manda cabeçalho nenhum`() {
        val api = server()
        api.enqueue(MockResponse().setResponseCode(204))
        val client = client(apiUrl(api), "client-id", "")

        call(client, api.url("/v1/config").toString()).close()

        val recorded = api.requiredRequest()
        assertThat(recorded.getHeader(CloudflareAccessInterceptor.HEADER_ID)).isNull()
        assertThat(recorded.getHeader(CloudflareAccessInterceptor.HEADER_SECRET)).isNull()
    }

    @Test
    fun `cabeçalhos ausentes para outro host mesmo com valores`() {
        val other = server()
        other.enqueue(MockResponse().setResponseCode(204))
        val client = client("https://keyflow-api-staging.arateki.com", "client-id", "client-secret")

        call(client, other.url("/v1/config").toString()).close()

        val recorded = other.requiredRequest()
        assertThat(recorded.getHeader(CloudflareAccessInterceptor.HEADER_ID)).isNull()
        assertThat(recorded.getHeader(CloudflareAccessInterceptor.HEADER_SECRET)).isNull()
    }

    @Test
    fun `redirect para outro host não leva o token`() {
        val api = server()
        val other = server()
        val apiBase = "http://api.staging.test:${api.port}"
        other.enqueue(MockResponse().setResponseCode(204))
        api.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "http://other.example.test:${other.port}/landed"),
        )
        val client = client(apiBase, "client-id", "client-secret")

        val response = call(client, "$apiBase/v1/config")
        assertThat(response.code).isEqualTo(204)
        response.close()

        assertThat(api.requiredRequest().getHeader(CloudflareAccessInterceptor.HEADER_SECRET))
            .isEqualTo("client-secret")
        val landed = other.requiredRequest()
        assertThat(landed.getHeader(CloudflareAccessInterceptor.HEADER_ID)).isNull()
        assertThat(landed.getHeader(CloudflareAccessInterceptor.HEADER_SECRET)).isNull()
    }

    @Test
    fun `401 sem token vira a mensagem de debug e a resposta segue para o app`() {
        val api = server()
        api.enqueue(MockResponse().setResponseCode(401).setBody("cloudflare"))
        val logs = mutableListOf<String>()
        val client = client(apiUrl(api), "", "", logs)

        val response = call(client, api.url("/v1/config").toString())
        assertThat(response.code).isEqualTo(401)
        assertThat(response.body!!.string()).isEqualTo("cloudflare")
        assertThat(logs).containsExactly(CloudflareAccessInterceptor.MISSING_TOKEN_LOG)
    }

    @Test
    fun `401 com token configurado não diz que o token está ausente`() {
        val api = server()
        api.enqueue(MockResponse().setResponseCode(401))
        val logs = mutableListOf<String>()
        val client = client(apiUrl(api), "client-id", "client-secret", logs)

        call(client, api.url("/v1/config").toString()).close()

        assertThat(logs).isEmpty()
        assertThat(api.requiredRequest().getHeader(CloudflareAccessInterceptor.HEADER_ID)).isEqualTo("client-id")
    }

    @Test
    fun `401 de outro host não vira aviso de token ausente`() {
        val other = server()
        other.enqueue(MockResponse().setResponseCode(401).setBody("alheio"))
        val logs = mutableListOf<String>()
        val client = client("https://keyflow-api-staging.arateki.com", "", "", logs)

        val response = call(client, other.url("/x").toString())
        assertThat(response.code).isEqualTo(401)
        assertThat(response.body!!.string()).isEqualTo("alheio")
        assertThat(logs).isEmpty()
    }

    @Test
    fun `valor inválido para cabeçalho não derruba a chamada`() {
        val api = server()
        api.enqueue(MockResponse().setResponseCode(401).setBody("no"))
        val logs = mutableListOf<String>()
        val client = client(apiUrl(api), "client-id", "segredo\ncom quebra", logs)

        val response = call(client, api.url("/v1/config").toString())
        assertThat(response.code).isEqualTo(401)
        response.close()
        assertThat(api.requiredRequest().getHeader(CloudflareAccessInterceptor.HEADER_SECRET)).isNull()
        assertThat(logs).containsExactly(CloudflareAccessInterceptor.MISSING_TOKEN_LOG)
        assertThat(logs.single()).doesNotContain("segredo")
    }

    @Test
    fun `o cliente HTTP base instala o interceptor uma única vez`() {
        val http = KeyFlowHttpClient.base()
        assertThat(http.networkInterceptors.filterIsInstance<CloudflareAccessInterceptor>()).hasSize(1)
    }

    @Test
    fun `os dois clientes da API não duplicam os cabeçalhos do Access`() {
        listOf(
            "src/main/java/com/keyflow/data/monetization/KeyFlowApiClient.kt",
            "src/main/java/com/keyflow/data/monetization/RemoteConfigRepository.kt",
        ).forEach { path ->
            assertThat(File(path).readText()).doesNotContain("CF-Access-Client")
        }
        assertThat(File("src/main/java/com/keyflow/net/KeyFlowHttpClient.kt").readText())
            .contains("CloudflareAccessInterceptor")
    }

    private fun server(): MockWebServer = MockWebServer().also {
        it.start()
        servers += it
    }

    private fun apiUrl(server: MockWebServer): String = server.url("/").toString().trimEnd('/')

    private fun client(
        apiBaseUrl: String,
        clientId: String,
        clientSecret: String,
        logs: MutableList<String> = mutableListOf(),
    ): OkHttpClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName("127.0.0.1"))
        })
        .addNetworkInterceptor(
            CloudflareAccessInterceptor(clientId, clientSecret, apiBaseUrl) { logs += it },
        )
        .build()

    private fun call(client: OkHttpClient, url: String) =
        client.newCall(Request.Builder().url(url).build()).execute()

    private fun MockWebServer.requiredRequest() =
        checkNotNull(takeRequest(2, TimeUnit.SECONDS)) { "pedido não chegou em ${url("/")}" }
}
