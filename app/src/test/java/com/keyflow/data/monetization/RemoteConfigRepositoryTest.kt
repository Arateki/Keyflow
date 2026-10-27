package com.keyflow.data.monetization

import com.google.common.truth.Truth.assertThat
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelKind
import com.keyflow.data.models.ThinkingSupport
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class RemoteConfigRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        ModelCatalog.clearRemoteManifest()
    }

    @After
    fun tearDown() {
        ModelCatalog.clearRemoteManifest()
        server.shutdown()
    }

    @Test
    fun `200 guarda etag e 304 usa o cache`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"config-v1\"").setBody(configJson()))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"models-v1\"").setBody(modelsJson("remote-fast")))
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"config-v1\""))
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"models-v1\""))
        val cache = MemoryCache()
        val repository = repository(cache)

        val first = repository.refresh()
        val second = repository.refresh()

        assertThat(first.config.products["credits_20"]?.minutes).isEqualTo(25)
        assertThat(first.models.single().id).isEqualTo("remote-fast")
        assertThat(first.models.single().vendor).isEqualTo(com.keyflow.data.models.ModelVendor.NVIDIA)
        assertThat(first.configSource).isEqualTo(RemoteDocumentSource.NETWORK)
        assertThat(first.modelsSource).isEqualTo(RemoteDocumentSource.NETWORK)
        assertThat(second.config).isEqualTo(first.config)
        assertThat(second.models).isEqualTo(first.models)
        assertThat(second.configSource).isEqualTo(RemoteDocumentSource.CACHE)
        assertThat(second.modelsSource).isEqualTo(RemoteDocumentSource.CACHE)
        assertThat(server.takeRequest().path).isEqualTo("/v1/config")
        assertThat(server.takeRequest().path).isEqualTo("/v1/models")
        assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("\"config-v1\"")
        assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("\"models-v1\"")
    }

    @Test
    fun `falha de rede preserva cache e manifesto válido substitui catálogo`() = runTest {
        val cache = MemoryCache()
        server.enqueue(MockResponse().setResponseCode(200).setBody(configJson()))
        server.enqueue(MockResponse().setResponseCode(200).setBody(modelsJson("remote-balanced")))
        val repository = repository(cache)
        repository.refresh()

        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        val offline = repository.refresh()
        ModelCatalog.applyRemoteManifest(offline.models)

        assertThat(offline.configSource).isEqualTo(RemoteDocumentSource.CACHE)
        assertThat(offline.modelsSource).isEqualTo(RemoteDocumentSource.CACHE)
        assertThat(ModelCatalog.current(ModelKind.SPEECH).single().id).isEqualTo("remote-balanced")
        assertThat(ModelCatalog.byId("remote-balanced")?.kind).isEqualTo(ModelKind.SPEECH)
    }

    @Test
    fun `manifesto vazio ou inválido não apaga o fallback`() {
        val original = ModelCatalog.SPEECH
        assertThat(runCatching { RemoteConfigRepository.parseModels("{\"models\":[]}") }.isFailure).isTrue()
        assertThat(runCatching { RemoteConfigRepository.parseModels("{\"models\":[{\"id\":\"x\"}]}") }.isFailure).isTrue()
        ModelCatalog.applyRemoteManifest(emptyList())
        assertThat(ModelCatalog.current(ModelKind.SPEECH)).isEqualTo(original)
    }

    @Test
    fun `thinking do manifesto é opcional e tolera valor desconhecido`() {
        fun parse(thinking: String?) = RemoteConfigRepository.parseModels(
            modelsJson("m").let { json ->
                if (thinking == null) json else json.replace("\"vendor\": \"NVIDIA\",", "\"vendor\": \"NVIDIA\", \"thinking\": \"$thinking\",")
            },
        ).single().thinking

        assertThat(parse(null)).isEqualTo(ThinkingSupport.NONE)
        assertThat(parse("OPTIONAL")).isEqualTo(ThinkingSupport.OPTIONAL)
        assertThat(parse("ALWAYS")).isEqualTo(ThinkingSupport.ALWAYS)
        assertThat(parse("SOMETIMES")).isEqualTo(ThinkingSupport.NONE)
    }

    @Test
    fun `config remota expõe aviso de versão mínima`() {
        val config = RemoteConfigRepository.parseConfig(configJson())
        assertThat(RemoteConfigSnapshot(config = config).requiresUpdate("0.1.0")).isTrue()
        assertThat(RemoteConfigSnapshot(config = config).requiresUpdate("9.0.0")).isFalse()
    }

    private fun repository(cache: MemoryCache) = RemoteConfigRepository(
        baseUrl = server.url("/").toString(),
        http = OkHttpClient(),
        cache = cache,
    )

    private fun configJson() = """
        {
          "products": {
            "credits_20": {"minutes": 25, "grantsEntitlements": true},
            "unlock_forever": {"grantsEntitlements": true}
          },
          "ads": {
            "initialCooldownSeconds": 90,
            "cooldownFactor": 2,
            "maxCooldownSeconds": 3600,
            "minEngagementSeconds": 5,
            "postPurchaseWindowSeconds": 300,
            "dailyCap": 10
          },
          "minAppVersion": "1.0.0",
          "killSwitch": false
        }
    """.trimIndent()

    private fun modelsJson(id: String) = """
        {
          "models": [{
            "id": "$id",
            "displayName": "Rápido remoto",
            "summary": "Modelo remoto de teste",
            "languages": "PT, EN",
            "engineType": "WHISPER",
            "kind": "SPEECH",
            "vendor": "NVIDIA",
            "acceptsLanguageHint": true,
            "recommendedRamMb": 512,
            "modelName": "Whisper remoto",
            "files": [{
              "role": "TOKENS",
              "fileName": "tokens.txt",
              "url": "https://example.test/tokens.txt",
              "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "bytes": 128
            }]
          }]
        }
    """.trimIndent()

    private class MemoryCache : RemoteConfigCache {
        private val values = mutableMapOf<String, RemoteCacheEntry>()
        override fun read(key: String): RemoteCacheEntry? = values[key]
        override fun write(key: String, entry: RemoteCacheEntry) { values[key] = entry }
    }
}
