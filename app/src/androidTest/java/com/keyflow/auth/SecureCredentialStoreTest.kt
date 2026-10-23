package com.keyflow.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.data.SecurePrefs
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** AUTH-02, Review Focus 5: o cofre de verdade (Keystore) — gravar, ler, apagar, o `meta` que sobrevive, e o cofre ilegível. */
@RunWith(AndroidJUnit4::class)
class SecureCredentialStoreTest {

    private lateinit var context: Context
    private lateinit var prefsName: String
    private lateinit var keyAlias: String
    private val provider = ProviderId("teste")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString().take(8)
        // Nome e alias PRÓPRIOS do teste: apagar a chave de produção quebraria as credenciais reais do aparelho.
        prefsName = "test_auth_$id"
        keyAlias = "test_auth_master_$id"
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(prefsName)
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias) }
    }

    private fun store() = SecureCredentialStore(context, prefsName, keyAlias)

    @Test
    fun anApiKeyAndAnOAuthCredentialRoundTripThroughTheRealVault() {
        val s = store()
        s.put(provider, Credential.ApiKey("sk-teste"))
        assertEquals(Credential.ApiKey("sk-teste"), store().get(provider))

        val full = oauth(earliestRefreshAtMs = 99, scopes = setOf("a", "b"))
        s.put(provider, full)
        assertEquals(full, store().get(provider)) // uma instância nova lê o que a outra gravou
    }

    @Test
    fun anApiKeyAndOAuthCoexistAndClearingOneLeavesTheOther() {
        val s = store()
        val full = oauth()

        s.put(provider, full)
        s.put(provider, Credential.ApiKey("sk-teste"))

        assertEquals(Credential.ApiKey("sk-teste"), s.get(provider))
        assertEquals(Credential.ApiKey("sk-teste"), s.getApiKey(provider))
        assertEquals(full, s.getOAuth(provider))

        s.clearApiKey(provider)
        assertEquals(full, s.get(provider))
        assertNull(s.getApiKey(provider))
        assertEquals(full, s.getOAuth(provider))

        s.put(provider, Credential.ApiKey("sk-novo"))
        s.clearOAuth(provider)
        assertEquals(Credential.ApiKey("sk-novo"), s.get(provider))
        assertEquals(Credential.ApiKey("sk-novo"), s.getApiKey(provider))
        assertNull(s.getOAuth(provider))
    }

    @Test
    fun clearErasesTheCredentialButNotTheMeta() {
        val s = store()
        s.put(provider, Credential.ApiKey("x"))
        s.putMeta(provider, "host_id", "urn:uuid:abc")

        s.clear(provider)

        assertNull(s.get(provider))
        assertEquals("urn:uuid:abc", s.meta(provider, "host_id")) // o id do host sobrevive ao Desconectar
    }

    @Test
    fun theCredentialsOfDifferentProvidersNeverMix() {
        val s = store()
        s.put(ProviderId("a"), Credential.ApiKey("chave-a"))
        s.put(ProviderId("b"), Credential.ApiKey("chave-b"))
        s.clear(ProviderId("a"))
        assertNull(s.get(ProviderId("a")))
        assertEquals(Credential.ApiKey("chave-b"), s.get(ProviderId("b")))
    }

    @Test
    fun changesEmitsTheCurrentValueAndThenEveryChange() = runBlocking {
        val s = store()
        s.put(provider, Credential.ApiKey("um"))
        assertEquals(Credential.ApiKey("um"), s.changes(provider).first())

        s.put(provider, Credential.ApiKey("dois"))
        assertEquals(Credential.ApiKey("dois"), s.changes(provider).first())

        s.clear(provider)
        assertNull(s.changes(provider).first())
    }

    /** Review Focus 5: backup restaurado sem a chave do Keystore → cofre ilegível → recupera vazio, sem crash. */
    @Test
    fun anUnreadableVaultRecoversEmptyAndWorksAgainInsteadOfCrashing() {
        val original = store()
        original.put(provider, Credential.ApiKey("segredo-antigo"))
        assertEquals(Credential.ApiKey("segredo-antigo"), original.get(provider))

        // Exatamente o que o restore do Android provoca: o arquivo de prefs segue no disco, a chave mestra não veio.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)

        val recovered = store() // antes da recuperação do SecurePrefs: AEADBadTagException em toda leitura
        assertNull(recovered.get(provider))
        recovered.put(provider, Credential.ApiKey("segredo-novo"))
        assertEquals(Credential.ApiKey("segredo-novo"), store().get(provider))
    }

    @Test
    fun aCorruptedValueReadsAsNullAndDoesNotBreakTheOtherProviders() {
        val s = store()
        s.put(ProviderId("bom"), Credential.ApiKey("ok"))
        // grava lixo direto no cofre, no lugar de uma credencial de outro provedor
        SecurePrefs.create(context, prefsName, keyAlias).edit().putString("cred.ruim", "isto não é json").commit()

        assertNull(store().get(ProviderId("ruim")))
        assertEquals(Credential.ApiKey("ok"), store().get(ProviderId("bom")))
    }

    private fun oauth(
        earliestRefreshAtMs: Long? = null,
        scopes: Set<String> = setOf("openid", "offline_access"),
    ) = Credential.OAuth(
        accessToken = "access1", refreshToken = "refresh1", expiresAtMs = 3_600_000, earliestRefreshAtMs = earliestRefreshAtMs,
        scopes = scopes, idToken = "id-token-1", clientId = "oaiapp_1", subject = "sub-1", account = "pessoa@exemplo.com",
    )
}
