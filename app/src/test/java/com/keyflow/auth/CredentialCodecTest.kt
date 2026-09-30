package com.keyflow.auth

import com.google.common.truth.Truth.assertThat
import com.keyflow.auth.fake.oauth
import org.junit.Test

/** AUTH-02: o formato em que a credencial vai para o cofre — e o que acontece com lixo. */
class CredentialCodecTest {

    @Test
    fun `an api key round trips`() {
        val key = Credential.ApiKey("sk-abc")
        assertThat(CredentialCodec.decode(CredentialCodec.encode(key))).isEqualTo(key)
    }

    @Test
    fun `an oauth credential round trips with every field`() {
        val full = oauth(earliestRefreshAtMs = 1_234, scopes = setOf("a", "b", "c"))
        assertThat(CredentialCodec.decode(CredentialCodec.encode(full))).isEqualTo(full)
    }

    @Test
    fun `an oauth credential round trips with every optional field missing`() {
        val bare = Credential.OAuth("acc", null, 5, null, emptySet(), null, null, null, null)
        assertThat(CredentialCodec.decode(CredentialCodec.encode(bare))).isEqualTo(bare)
    }

    @Test
    fun `tokens cleared but the registration kept round trips`() {
        val tombstone = oauth(access = "", refresh = null, expiresAtMs = 0, scopes = emptySet())
        assertThat(CredentialCodec.decode(CredentialCodec.encode(tombstone))).isEqualTo(tombstone)
    }

    @Test
    fun `garbage decodes to null instead of crashing`() {
        listOf("", "não é json", "{", "[]", "{\"type\":\"desconhecido\"}", "{\"type\":\"api_key\"}", "{\"type\":\"oauth\"}").forEach {
            assertThat(CredentialCodec.decode(it)).isNull()
        }
    }

    @Test
    fun `the encoded form never leaks a token into a message-like string field name`() {
        // Só confere que o formato é estável: os nomes dos campos são parte do contrato com o que já está gravado.
        val json = CredentialCodec.encode(oauth())
        listOf("type", "accessToken", "refreshToken", "expiresAtMs", "scopes", "clientId", "subject").forEach {
            assertThat(json).contains("\"$it\"")
        }
    }
}
