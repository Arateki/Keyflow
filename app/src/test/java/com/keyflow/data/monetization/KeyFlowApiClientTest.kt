package com.keyflow.data.monetization

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class KeyFlowApiClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `registro lazy persiste o token e account usa o Bearer`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"accessToken\":\"token-1\"}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"balanceSeconds\":42,\"adsOff\":true,\"skinsUnlocked\":false}"))
        val tokens = FakeTokenStore()
        val client = client(tokens)

        assertThat(client.account()).isEqualTo(KeyFlowAccount(42, adsOff = true, skinsUnlocked = false))
        assertThat(tokens.value).isEqualTo("token-1")
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.path).isEqualTo("/v1/register")
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization")).isEqualTo("Bearer token-1")
    }

    @Test
    fun `401 registra de novo uma vez e repete account`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"accessToken\":\"token-1\"}"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"accessToken\":\"token-2\"}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"balanceSeconds\":7}"))

        val tokens = FakeTokenStore()
        val client = client(tokens)

        assertThat(client.account().balanceSeconds).isEqualTo(7)
        assertThat(tokens.value).isEqualTo("token-2")
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test
    fun `API fora fica como erro re-tentavel`() = runTest {
        server.shutdown()
        val client = client(FakeTokenStore(), baseUrl = server.url("/").toString())

        val failure = runCatching { client.account() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(KeyFlowApiException.Retryable::class.java)
    }

    @Test
    fun `redeem envia a compra ao servidor`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"accessToken\":\"token-1\"}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"credited\"}"))
        val client = client(FakeTokenStore())

        assertThat(client.redeem(
            StorePurchase(
                products = listOf(MonetizationProducts.UNLOCK_FOREVER),
                purchaseToken = "purchase-token",
                state = StorePurchaseState.PURCHASED,
                acknowledged = false,
            ),
        )).isTrue()
        val register = server.takeRequest(1, TimeUnit.SECONDS)
        val redeem = server.takeRequest(1, TimeUnit.SECONDS)
        assertThat(register!!.path).isEqualTo("/v1/register")
        assertThat(redeem!!.path).isEqualTo("/v1/purchases/redeem")
        assertThat(redeem.getHeader("Authorization")).isEqualTo("Bearer token-1")
        assertThat(redeem.body.readUtf8()).contains("purchase-token")
    }

    @Test
    fun `redeem pending não confirma a compra`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"accessToken\":\"token-1\"}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"pending\"}"))

        assertThat(client(FakeTokenStore()).redeem(purchase())).isFalse()
    }

    @Test
    fun `redeem registra de novo uma vez depois de 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"accessToken\":\"token-1\"}"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"accessToken\":\"token-2\"}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"already_credited\"}"))

        val tokens = FakeTokenStore()
        assertThat(client(tokens).redeem(purchase())).isTrue()
        assertThat(tokens.value).isEqualTo("token-2")
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test
    fun `falha de rede no redeem continua sendo erro re-tentavel`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"accessToken\":\"token-1\"}"))
        server.shutdown()

        val failure = runCatching { client(FakeTokenStore(), baseUrl = server.url("/").toString()).redeem(purchase()) }
            .exceptionOrNull()
        assertThat(failure).isInstanceOf(KeyFlowApiException.Retryable::class.java)
    }

    @Test
    fun `excluir limpa token e bloqueia novo registro em leituras até uma nova compra`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val tokens = FakeTokenStore().apply { value = "token-vivo" }
        val api = client(tokens)
        api.deleteAccount()
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertThat(request.method).isEqualTo("DELETE")
        assertThat(request.path).isEqualTo("/v1/account")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer token-vivo")
        assertThat(tokens.value).isNull()
        assertThat(tokens.deleted).isTrue()
        assertThat(client(tokens).account()).isEqualTo(KeyFlowAccount(0, false, false))
        assertThat(client(tokens).redeem(purchase())).isFalse()
        assertThat(server.requestCount).isEqualTo(1)
        api.resumeRegistrationAfterDeletion()
        server.enqueue(MockResponse().setBody("{\"accessToken\":\"novo\"}"))
        server.enqueue(MockResponse().setBody("{\"balanceSeconds\":0}"))
        assertThat(api.account().balanceSeconds).isEqualTo(0)
        assertThat(tokens.value).isEqualTo("novo")
    }

    @Test
    fun `falha de exclusão preserva credencial para tentar novamente`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val tokens = FakeTokenStore().apply { value = "token-vivo" }
        assertThat(runCatching { client(tokens).deleteAccount() }.exceptionOrNull())
            .isInstanceOf(KeyFlowApiException.Retryable::class.java)
        assertThat(tokens.value).isEqualTo("token-vivo")
        assertThat(tokens.deleted).isFalse()
    }

    @Test
    fun `retry após exclusão remota registra e exclui de novo antes de limpar local`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("{\"accessToken\":\"novo\"}"))
        server.enqueue(MockResponse().setResponseCode(204))
        val tokens = FakeTokenStore().apply { value = "antigo" }
        client(tokens).deleteAccount()
        assertThat(server.takeRequest().method).isEqualTo("DELETE")
        val registration = server.takeRequest()
        assertThat(registration.path).isEqualTo("/v1/register")
        assertThat(registration.body.readUtf8()).contains("550e8400-e29b-41d4-a716-446655440000")
        val retry = server.takeRequest()
        assertThat(retry.method).isEqualTo("DELETE")
        assertThat(retry.getHeader("Authorization")).isEqualTo("Bearer novo")
        assertThat(tokens.value).isNull()
        assertThat(tokens.deleted).isTrue()
    }

    private fun client(tokens: FakeTokenStore, baseUrl: String = server.url("/").toString()) = KeyFlowApiClient(
        context = null,
        baseUrl = baseUrl,
        identity = { "550e8400-e29b-41d4-a716-446655440000" },
        integrityToken = { "integrity-fake" },
        tokenStore = tokens,
    )

    private fun purchase() = StorePurchase(
        products = listOf(MonetizationProducts.UNLOCK_FOREVER),
        purchaseToken = "purchase-token",
        state = StorePurchaseState.PURCHASED,
        acknowledged = false,
    )

    private class FakeTokenStore : KeyFlowAccessTokenStore {
        var value: String? = null
        var deleted = false
        override fun get(): String? = value
        override fun put(token: String) { value = token; deleted = false }
        override fun clear() { value = null }
        override fun isDeleted() = deleted
        override fun clearAfterDeletion() { value = null; deleted = true }
        override fun resumeRegistration() { deleted = false }
    }
}
