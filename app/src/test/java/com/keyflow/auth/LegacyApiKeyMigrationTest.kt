package com.keyflow.auth

import com.google.common.truth.Truth.assertThat
import com.keyflow.auth.fake.FakeCredentialStore
import com.keyflow.data.FakeSharedPreferences
import org.junit.Test

class LegacyApiKeyMigrationTest {

    @Test
    fun `old provider keys move to keyflow_auth and leave no copy`() {
        val legacy = FakeSharedPreferences().apply {
            edit()
                .putString("provider_anthropic_api_key", "fake-anthropic-key")
                .putString("provider_openai_api_key", "fake-openai-key")
                .putString("provider_google_api_key", "fake-google-key")
                .commit()
        }
        val target = FakeCredentialStore()

        LegacyApiKeyMigration.migrate(legacy, target)

        assertThat(target.get(ProviderIds.ANTHROPIC)).isEqualTo(Credential.ApiKey("fake-anthropic-key"))
        assertThat(target.get(ProviderIds.OPENAI)).isEqualTo(Credential.ApiKey("fake-openai-key"))
        assertThat(target.get(ProviderIds.GOOGLE)).isEqualTo(Credential.ApiKey("fake-google-key"))
        assertThat(legacy.contains("provider_anthropic_api_key")).isFalse()
        assertThat(legacy.contains("provider_openai_api_key")).isFalse()
        assertThat(legacy.contains("provider_google_api_key")).isFalse()
    }

    @Test
    fun `an existing credential wins and the legacy copy is still removed`() {
        val legacy = FakeSharedPreferences().apply {
            edit().putString("provider_anthropic_api_key", "old-key").commit()
        }
        val target = FakeCredentialStore().apply {
            put(ProviderIds.ANTHROPIC, Credential.ApiKey("new-key"))
        }

        LegacyApiKeyMigration.migrate(legacy, target)

        assertThat(target.get(ProviderIds.ANTHROPIC)).isEqualTo(Credential.ApiKey("new-key"))
        assertThat(legacy.contains("provider_anthropic_api_key")).isFalse()
    }
}
