package com.keyflow.auth.providers.openai

import com.google.common.truth.Truth.assertThat
import com.keyflow.auth.AuthDependencies
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.AuthProviders
import com.keyflow.auth.Availability
import com.keyflow.auth.Credential
import com.keyflow.auth.ProviderIds
import com.keyflow.auth.fake.FakeCredentialStore
import com.keyflow.auth.fake.oauth
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** As peças pequenas do provedor: config, id do host, fluxo de chave, cabeçalhos e o registro. */
class OpenAiPartsTest {

    // ── config ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the config is well formed — https urls, no placeholders`() {
        listOf(OpenAiAuthConfig.discoveryUrl, OpenAiAuthConfig.authorizationUrl, OpenAiAuthConfig.tokenUrl, OpenAiAuthConfig.issuer,
            OpenAiAuthConfig.resource, OpenAiAuthConfig.apiBaseUrl, OpenAiAuthConfig.usageUrl, OpenAiAuthConfig.docsSource).forEach {
            assertThat(it).startsWith("https://")
            assertThat(it).doesNotContain("…")
            assertThat(it.lowercase()).doesNotContain("todo")
        }
        assertThat(OpenAiAuthConfig.provisionalClientId).isEqualTo("dynamic_agent_client")
        assertThat(OpenAiAuthConfig.scopes).containsAtLeast("openid", "offline_access", OpenAiAuthConfig.planScope)
    }

    /** Guarda contra mudança acidental: a doc exige o MESMO caminho depois do 1º registro — mudar quebraria os registros existentes. */
    @Test
    fun `the redirect path is the one chosen at the first registration and must never change`() {
        assertThat(OpenAiAuthConfig.redirectPath).isEqualTo("/auth/callback")
    }

    @Test
    fun `the login is enabled in debug builds only until the eligibility decision`() {
        assertThat(OpenAiAuthConfig.enabled).isEqualTo(com.keyflow.BuildConfig.DEBUG)
    }

    // ── id do host ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the host id is a urn uuid version 4`() {
        val id = OpenAiHostId(FakeCredentialStore()).get()
        assertThat(id).matches("urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }

    @Test
    fun `the host id is stable across calls and instances and survives clear`() {
        val store = FakeCredentialStore()
        val first = OpenAiHostId(store).get()
        assertThat(OpenAiHostId(store).get()).isEqualTo(first)
        store.put(ProviderIds.OPENAI, Credential.ApiKey("x"))
        store.clear(ProviderIds.OPENAI)
        assertThat(OpenAiHostId(store).get()).isEqualTo(first)
    }

    @Test
    fun `two installations get different host ids`() {
        assertThat(OpenAiHostId(FakeCredentialStore()).get()).isNotEqualTo(OpenAiHostId(FakeCredentialStore()).get())
    }

    // ── chave de API ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the api key flow is always available — even while the login is disabled`() {
        val flow = OpenAiApiKeyFlow()
        assertThat(flow.method).isEqualTo(AuthMethod.API_KEY)
        assertThat(flow.availability).isEqualTo(Availability.Available)
    }

    @Test
    fun `revoking an api key does nothing`() {
        runBlocking { OpenAiApiKeyFlow().revoke(Credential.ApiKey("sk")) }
    }

    // ── cabeçalhos e provedor ─────────────────────────────────────────────────────────────

    @Test
    fun `both an api key and a login token go out as a bearer header`() {
        assertThat(OpenAiProvider.headersFor(Credential.ApiKey("sk-abc"))).containsExactly("Authorization", "Bearer sk-abc")
        assertThat(OpenAiProvider.headersFor(oauth(access = "tok"))).containsExactly("Authorization", "Bearer tok")
    }

    @Test
    fun `plan usage needs the plan scope`() {
        assertThat(OpenAiProvider.planUsage(oauth(scopes = setOf("openid", OpenAiAuthConfig.planScope)))).isTrue()
        assertThat(OpenAiProvider.planUsage(oauth(scopes = setOf("openid")))).isFalse()
    }

    /**
     * A rede do Yan tem IPv6 quebrado (medido em 2026-09-27): quem tenta o IPv6 primeiro trava por minutos. O cliente que fala com a
     * OpenAI tenta o IPv4 antes e limita a chamada inteira — senão "Entrar" pareceria travado.
     */
    @Test
    fun `the client that talks to openai tries IPv4 first and bounds the whole call`() {
        val client = OpenAiHttp.client(okhttp3.OkHttpClient())
        assertThat(client.dns).isInstanceOf(com.keyflow.data.models.Ipv4FirstDns::class.java)
        assertThat(client.callTimeoutMillis).isGreaterThan(0)
        assertThat(client.callTimeoutMillis).isAtMost(120_000)
    }

    @Test
    fun `the openai client keeps the connection pool of the base client`() {
        val base = okhttp3.OkHttpClient()
        assertThat(OpenAiHttp.client(base).connectionPool).isSameInstanceAs(base.connectionPool)
    }

    @Test
    fun `the registry offers the openai provider with a key flow and a login flow`() {
        val providers = AuthProviders.create(AuthDependencies(FakeCredentialStore()))
        val openai = providers.single { it.id == ProviderIds.OPENAI }

        assertThat(openai.displayName).isEqualTo("OpenAI")
        assertThat(openai.flows.map { it.method }).containsExactly(AuthMethod.API_KEY, AuthMethod.OAUTH).inOrder()
    }
}
