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

    private fun client(tokens: FakeTokenStore, baseUrl: String = server.url("/").toString()) = KeyFlowApiClient(
        context = null,
        baseUrl = baseUrl,
        identity = { "550e8400-e29b-41d4-a716-446655440000" },
        integrityToken = { "integrity-fake" },
        tokenStore = tokens,
    )

    private class FakeTokenStore : KeyFlowAccessTokenStore {
        var value: String? = null
        override fun get(): String? = value
        override fun put(token: String) { value = token }
        override fun clear() { value = null }
    }
}
