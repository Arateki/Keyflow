package com.keyflow.agenthost

import com.google.common.truth.Truth.assertThat
import com.keyflow.agent.SecretFilter
import com.keyflow.auth.Credential
import com.keyflow.auth.ProviderIds
import com.keyflow.auth.fake.FakeCredentialStore
import com.keyflow.data.FakeSharedPreferences
import com.keyflow.data.SettingsSecrets
import org.junit.Test

/**
 * TLS-03 / D5 (pendência da fase 1A): os valores secretos de agora — o cofre das Settings e o cofre de credenciais do `auth/` — chegam ao
 * `SecretFilter`, só leitura, e falham FECHADO. O teste com o cofre cifrado de verdade, no aparelho, é o `AgentSecretsDeviceTest`.
 */
class AgentSecretSourceTest {

    private fun vaultWith(vararg values: Pair<String, String>) = FakeSharedPreferences().also { prefs ->
        val editor = prefs.edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        editor.commit()
    }

    private fun oauth(access: String, refresh: String? = "refresh-token-value-123", id: String? = "id-token-value-456") =
        Credential.OAuth(access, refresh, expiresAtMs = 1, earliestRefreshAtMs = null, scopes = emptySet(), idToken = id, clientId = "client-id-publico", subject = "sub", account = "yan@exemplo.com")

    @Test
    fun `every secret key of the Settings vault is covered`() {
        // Um valor por chave que o SettingsSecrets conhece: se a fonte pulasse alguma, o filtro deixaria o valor passar.
        val values = SettingsSecrets.BY_PROPERTY.values.mapIndexed { i, key -> key to "valor-secreto-numero-$i-$key" }
        val source = AgentSecretSource({ vaultWith(*values.toTypedArray()) }, FakeCredentialStore())
        assertThat(source()).containsAtLeastElementsIn(values.map { it.second })
    }

    @Test
    fun `values that are not in the known list are covered too, so a new secret does not need anyone to remember`() {
        val source = AgentSecretSource({ vaultWith("uma_chave_que_ninguem_listou" to "segredo-de-um-campo-novo-9981") }, FakeCredentialStore())
        assertThat(source()).contains("segredo-de-um-campo-novo-9981")
    }

    @Test
    fun `the api keys and the login tokens of every provider are covered`() {
        val store = FakeCredentialStore()
        store.put(ProviderIds.ANTHROPIC, Credential.ApiKey("chave-anthropic-sentinela-0001"))
        store.put(ProviderIds.OPENAI, oauth("acesso-openai-sentinela-0002", "renovacao-openai-sentinela-0003", "identidade-openai-sentinela-0004"))
        store.put(ProviderIds.GOOGLE, Credential.ApiKey("chave-google-sentinela-0005"))
        val got = AgentSecretSource({ vaultWith() }, store)()
        assertThat(got).containsAtLeast(
            "chave-anthropic-sentinela-0001", "acesso-openai-sentinela-0002", "renovacao-openai-sentinela-0003", "identidade-openai-sentinela-0004", "chave-google-sentinela-0005",
        )
    }

    @Test
    fun `the secrets of the Computador tab reach the filter and are redacted`() {
        val cofre = com.keyflow.computador.chaves.CofreFalso().also {
            it.gravar("ssh_pw.a1b2c3d4", "senha-ssh-sentinela-7731")
            it.gravar("ssh_key.k0000001", "semente-sentinela-4402")
        }
        val source = AgentSecretSource({ vaultWith() }, FakeCredentialStore(), computador = cofre::valores)
        assertThat(source()).containsAtLeast("senha-ssh-sentinela-7731", "semente-sentinela-4402")
        val filtered = SecretFilter(source).redact("o servidor respondeu senha-ssh-sentinela-7731 e semente-sentinela-4402")
        assertThat(filtered).doesNotContain("sentinela")
        // Apagar do cofre tira o valor do filtro na leitura seguinte (a fonte lê a cada uso).
        cofre.apagar("ssh_pw.a1b2c3d4")
        source.invalidate()
        assertThat(source()).doesNotContain("senha-ssh-sentinela-7731")
    }

    @Test
    fun `identifiers that are not secrets and the empty skeleton of a login are not collected`() {
        val store = FakeCredentialStore()
        store.put(ProviderIds.OPENAI, Credential.OAuth("", null, 0, null, emptySet(), null, "client-id-publico", "sub", "yan@exemplo.com"))
        val got = AgentSecretSource({ vaultWith() }, store)()
        assertThat(got).doesNotContain("")
        assertThat(got).doesNotContain("client-id-publico")
        assertThat(got).doesNotContain("yan@exemplo.com")
    }

    @Test
    fun `an empty vault gives an empty list`() {
        assertThat(AgentSecretSource({ vaultWith() }, FakeCredentialStore())()).isEmpty()
    }

    @Test
    fun `the read is kept for a short while and then read again`() {
        val prefs = vaultWith("k" to "primeiro-valor-secreto-0001")
        var now = 1_000L
        val source = AgentSecretSource({ prefs }, FakeCredentialStore(), clockMs = { now }, ttlMs = 2_000)
        assertThat(source()).containsExactly("primeiro-valor-secreto-0001")

        prefs.edit().putString("k", "segundo-valor-secreto-0002").commit()
        now += 1_999
        assertThat(source()).containsExactly("primeiro-valor-secreto-0001")
        now += 2
        assertThat(source()).containsExactly("segundo-valor-secreto-0002")
    }

    @Test
    fun `invalidate forces a new read at once`() {
        val prefs = vaultWith("k" to "valor-antigo-secreto-0001")
        val source = AgentSecretSource({ prefs }, FakeCredentialStore(), clockMs = { 10 })
        source()
        prefs.edit().putString("k", "valor-novo-secreto-0002").commit()
        source.invalidate()
        assertThat(source()).containsExactly("valor-novo-secreto-0002")
    }

    @Test
    fun `the vault is not opened until the first read`() {
        var opened = 0
        val source = AgentSecretSource({ opened++; vaultWith() }, FakeCredentialStore())
        assertThat(opened).isEqualTo(0)
        source()
        source()
        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun `a vault that cannot be read makes the filter withhold the whole text, never let it pass`() {
        val broken = AgentSecretSource({ error("cofre ilegível") }, FakeCredentialStore())
        val filter = SecretFilter(broken)
        assertThat(filter.redact("texto qualquer, sem segredo nenhum")).isEqualTo(SecretFilter.WITHHELD)
    }

    @Test
    fun `a credential store that fails makes the filter withhold too`() {
        val failing = object : com.keyflow.auth.CredentialStore by FakeCredentialStore() {
            override fun get(provider: com.keyflow.auth.ProviderId): Credential? = error("cofre de credenciais ilegível")
        }
        val filter = SecretFilter(AgentSecretSource({ vaultWith() }, failing))
        assertThat(filter.redact("oi")).isEqualTo(SecretFilter.WITHHELD)
    }

    @Test
    fun `the filter replaces a vault value that no format pattern would catch, and nothing of it survives`() {
        val secret = "kf-vault-secret-7f3a9c21d84b"
        val filter = SecretFilter(AgentSecretSource({ vaultWith("provider_anthropic_api_key" to secret) }, FakeCredentialStore()))
        val out = filter.redact("o erro dizia: chave=$secret e mais texto")
        assertThat(out).doesNotContain(secret)
        assertThat(out).doesNotContain("7f3a9c21d84b")
        assertThat(out).contains(SecretFilter.MARKER)
        assertThat(filter.containsSecret("argumento com $secret")).isTrue()
        assertThat(filter.containsSecret("argumento sem nada")).isFalse()
    }

    @Test
    fun `no secret shows up in the exception of a failing source`() {
        val secret = "segredo-que-nao-pode-vazar-0001"
        val store = object : com.keyflow.auth.CredentialStore by FakeCredentialStore() {
            override fun get(provider: com.keyflow.auth.ProviderId): Credential? = throw IllegalStateException("falha ao ler")
        }
        val failure = runCatching { AgentSecretSource({ vaultWith("k" to secret) }, store)() }.exceptionOrNull()
        assertThat(failure).isNotNull()
        assertThat(failure.toString()).doesNotContain(secret)
    }
}
