package com.keyflow.auth

import com.google.common.truth.Truth.assertThat
import com.keyflow.auth.fake.FakeCredentialStore
import org.junit.Test

/** AUTH-03: a lista de provedores que a UI monta os botões — o registro. */
class AuthProvidersTest {

    private val providers = AuthProviders.create(AuthDependencies(FakeCredentialStore()))

    @Test
    fun `provider ids are unique`() {
        assertThat(providers.map { it.id }).containsNoDuplicates()
    }

    @Test
    fun `every provider offers at least one way to connect and a name to show`() {
        providers.forEach {
            assertThat(it.flows).isNotEmpty()
            assertThat(it.displayName).isNotEmpty()
        }
    }

    @Test
    fun `subscription OAuth is offered for OpenAI Anthropic and Google`() {
        listOf(ProviderIds.OPENAI, ProviderIds.ANTHROPIC, ProviderIds.GOOGLE).forEach { id ->
            val oauth = providers.single { it.id == id }.flows.single { it.method == AuthMethod.OAUTH }
            assertThat(oauth.availability).isEqualTo(Availability.Available)
        }
    }

    /** O id da OpenAI mora no núcleo: a UI e o motor o usam sem importar nada de `providers/`. */
    @Test
    fun `the well known provider ids live in the core`() {
        assertThat(ProviderIds.OPENAI.value).isEqualTo("openai")
    }
}
