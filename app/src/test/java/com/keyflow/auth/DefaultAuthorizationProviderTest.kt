package com.keyflow.auth

import com.google.common.truth.Truth.assertThat
import com.keyflow.auth.fake.FakeCredentialStore
import com.keyflow.auth.fake.FakeOAuthFlow
import com.keyflow.auth.fake.oauth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * AUTH-04/AUTH-11 + Review Focus 2 e 5: o que entrega o cabeçalho de credencial e renova o token — uma vez só, gravando
 * antes de usar, sem apagar nada por falha temporária e sem nunca chamar de "renovado" o que não foi gravado.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAuthorizationProviderTest {

    private val provider = ProviderId("fake")
    private val store = FakeCredentialStore()
    private val flow = FakeOAuthFlow()

    private fun TestScope.authorization(margin: Long = 60_000) = DefaultAuthorizationProvider(
        registry = listOf(
            ProviderAuth(provider, "Fake", listOf(flow)) { c ->
                mapOf("Authorization" to "Bearer " + when (c) { is Credential.ApiKey -> c.value; is Credential.OAuth -> c.accessToken })
            },
        ),
        store = store,
        clock = { currentTime },
        marginMs = margin,
    )

    /**
     * Review, Important 2: o refresh token é rotativo — se quem chamou for cancelado (Parar, girar a tela, o efeito da lista de
     * modelos recomeçando) DEPOIS de o servidor já ter trocado o token, o novo tem de ser gravado do mesmo jeito; senão o cofre
     * fica com o velho, que a próxima renovação vê como reusado.
     */
    @Test
    fun `cancelling the caller during a refresh still persists the rotated credential`() = runTest {
        store.put(provider, oauth(expiresAtMs = 30_000))
        flow.refreshDelayMs = 100
        flow.refreshResult = { it.copy(accessToken = "access2", refreshToken = "refresh2", expiresAtMs = 4_000_000) }
        val authorization = authorization()

        val caller = launch { runCatching { authorization.headers(provider) } }
        runCurrent() // entra na renovação (dorme 100 ms)
        caller.cancel()
        advanceTimeBy(200); runCurrent()

        val saved = store.get(provider) as Credential.OAuth
        assertThat(saved.refreshToken).isEqualTo("refresh2")
        assertThat(saved.accessToken).isEqualTo("access2")
    }

    /** RCL-03: o servidor disse 401 invalid_user → o login vira "esqueleto" (o registro fica) e o próximo pedido pede login de novo. */
    @Test
    fun `invalidating a login leaves the skeleton and the next call asks to reconnect`() = runTest {
        store.put(provider, oauth(expiresAtMs = 3_600_000))
        val authorization = authorization()

        authorization.invalidate(provider)

        val saved = store.get(provider) as Credential.OAuth
        assertThat(saved.isSkeleton).isTrue()
        assertThat(saved.clientId).isEqualTo("oaiapp_1") // o registro não se perde
        val failure = runCatching { authorization.headers(provider) }.exceptionOrNull() as AuthException
        assertThat(failure.kind).isEqualTo(AuthErrorKind.NEEDS_RECONNECT)
    }

    /** A chave de API recusada é do usuário para corrigir; apagá-la em silêncio seria pior. */
    @Test
    fun `invalidating an api key or nothing changes nothing`() = runTest {
        val authorization = authorization()
        authorization.invalidate(provider) // nada guardado: sem exceção
        assertThat(store.get(provider)).isNull()

        store.put(provider, Credential.ApiKey("sk-x"))
        authorization.invalidate(provider)
        assertThat(store.get(provider)).isEqualTo(Credential.ApiKey("sk-x"))
    }

    @Test
    fun `o tipo corresponde aos cabecalhos da credencial ativa e nao revela o segredo`() = runTest {
        store.put(provider, Credential.ApiKey("chave-falsa"))
        val api = authorization().authorizedHeaders(provider)
        assertThat(api.apiKey).isTrue()
        assertThat(api.headers["Authorization"]).isEqualTo("Bearer chave-falsa")
        assertThat(api.toString()).doesNotContain("chave-falsa")
        store.put(provider, oauth(expiresAtMs = 3_600_000))
        val login = authorization().authorizedHeaders(provider)
        assertThat(login.apiKey).isFalse()
        assertThat(login.headers["Authorization"]).isEqualTo("Bearer access1")
        assertThat(login.toString()).doesNotContain("access1")
    }

    @Test
    fun `a valid token is not refreshed`() = runTest {
        store.put(provider, oauth(expiresAtMs = 3_600_000))
        val headers = authorization().headers(provider)
        assertThat(headers["Authorization"]).isEqualTo("Bearer access1")
        assertThat(flow.refreshCalls).isEqualTo(0)
    }

    @Test
    fun `near expiry it refreshes once and has persisted every field before it returns`() = runTest {
        store.put(provider, oauth(expiresAtMs = 30_000)) // dentro da margem de 60 s
        flow.refreshResult = { it.copy(accessToken = "access2", refreshToken = "refresh2", expiresAtMs = 4_000_000, scopes = setOf("novo")) }

        val headers = authorization().headers(provider)

        assertThat(headers["Authorization"]).isEqualTo("Bearer access2")
        val saved = store.get(provider) as Credential.OAuth
        assertThat(saved.accessToken).isEqualTo("access2")
        assertThat(saved.refreshToken).isEqualTo("refresh2")      // o refresh ROTATIVO, junto
        assertThat(saved.expiresAtMs).isEqualTo(4_000_000L)
        assertThat(saved.scopes).containsExactly("novo")
        assertThat(saved.clientId).isEqualTo("oaiapp_1")           // o registro não se perde
        assertThat(flow.refreshCalls).isEqualTo(1)
    }

    /** Review Focus 2: cinco pedidos ao mesmo tempo, UMA renovação — a segunda usaria um refresh que já não vale. */
    @Test
    fun `five concurrent header calls trigger exactly one refresh`() = runTest {
        store.put(provider, oauth(expiresAtMs = 30_000))
        flow.refreshDelayMs = 100
        val authorization = authorization()

        val results = (1..5).map { async { authorization.headers(provider) } }.awaitAll()
        runCurrent()

        assertThat(flow.refreshCalls).isEqualTo(1)
        assertThat(results.map { it["Authorization"] }.toSet()).containsExactly("Bearer access1+")
    }

    @Test
    fun `it waits for earliest refresh at while the token is still valid`() = runTest {
        store.put(provider, oauth(expiresAtMs = 90_000, earliestRefreshAtMs = 50_000)) // na margem (90−60=30 s), mas cedo demais
        advanceTimeBy(40_000)
        val authorization = authorization()

        assertThat(authorization.headers(provider)["Authorization"]).isEqualTo("Bearer access1")
        assertThat(flow.refreshCalls).isEqualTo(0)

        advanceTimeBy(15_000) // 55 s ≥ earliest
        assertThat(authorization.headers(provider)["Authorization"]).isEqualTo("Bearer access1+")
        assertThat(flow.refreshCalls).isEqualTo(1)
    }

    @Test
    fun `an expired token is refreshed even before earliest refresh at`() = runTest {
        store.put(provider, oauth(expiresAtMs = 10_000, earliestRefreshAtMs = 500_000))
        advanceTimeBy(20_000) // já expirou
        assertThat(authorization().headers(provider)["Authorization"]).isEqualTo("Bearer access1+")
        assertThat(flow.refreshCalls).isEqualTo(1)
    }

    @Test
    fun `an api key is returned as is and never refreshed`() = runTest {
        store.put(provider, Credential.ApiKey("sk-teste"))
        assertThat(authorization().headers(provider)["Authorization"]).isEqualTo("Bearer sk-teste")
        assertThat(flow.refreshCalls).isEqualTo(0)
    }

    @Test
    fun `an unusable refresh token clears the tokens, keeps the registration and asks to reconnect`() = runTest {
        store.put(provider, oauth(expiresAtMs = 30_000))
        flow.refreshError = AuthException(AuthErrorKind.INVALID_GRANT, "refresh_token_invalidated")

        val failure = failureOf<AuthException> { authorization().headers(provider) }

        assertThat(failure.kind).isEqualTo(AuthErrorKind.NEEDS_RECONNECT)
        val kept = store.get(provider) as Credential.OAuth
        assertThat(kept.accessToken).isEmpty()
        assertThat(kept.refreshToken).isNull()
        assertThat(kept.clientId).isEqualTo("oaiapp_1")     // repete o OAuth com o MESMO registro
        assertThat(kept.idToken).isEqualTo("id-token-1")    // e com o id_token_hint
    }

    /** "Do not erase credentials solely because of a temporary network or infrastructure failure." */
    @Test
    fun `a network failure erases nothing and a still valid token keeps working`() = runTest {
        store.put(provider, oauth(expiresAtMs = 30_000))     // vale mais 30 s, mas dentro da margem
        flow.refreshError = AuthException(AuthErrorKind.NETWORK, "sem rede")

        val headers = authorization().headers(provider)

        assertThat(headers["Authorization"]).isEqualTo("Bearer access1")
        assertThat(store.get(provider)).isEqualTo(oauth(expiresAtMs = 30_000))
    }

    @Test
    fun `a network failure on an already expired token fails but still erases nothing`() = runTest {
        store.put(provider, oauth(expiresAtMs = 10_000))
        advanceTimeBy(20_000)
        flow.refreshError = AuthException(AuthErrorKind.NETWORK, "sem rede")

        val failure = failureOf<AuthException> { authorization().headers(provider) }

        assertThat(failure.kind).isEqualTo(AuthErrorKind.NETWORK)
        assertThat((store.get(provider) as Credential.OAuth).refreshToken).isEqualTo("refresh1")
    }

    /** O novo access token só vale depois de gravado: se a gravação falha, a exceção sobe — não se usa um token não guardado. */
    @Test
    fun `a store write failure after a refresh is not swallowed`() = runTest {
        store.put(provider, oauth(expiresAtMs = 30_000))
        store.failNextPut = IllegalStateException("disco cheio")

        val failure = failureOf<IllegalStateException> { authorization().headers(provider) }

        assertThat(failure).hasMessageThat().contains("disco cheio")
        assertThat((store.get(provider) as Credential.OAuth).accessToken).isEqualTo("access1") // o velho segue lá
    }

    @Test
    fun `no credential means not connected`() = runTest {
        val failure = failureOf<AuthException> { authorization().headers(provider) }
        assertThat(failure.kind).isEqualTo(AuthErrorKind.NOT_CONNECTED)
    }

    @Test
    fun `an expired token with no refresh token asks to reconnect`() = runTest {
        store.put(provider, oauth(refresh = null, expiresAtMs = 10_000))
        advanceTimeBy(20_000)
        val failure = failureOf<AuthException> { authorization().headers(provider) }
        assertThat(failure.kind).isEqualTo(AuthErrorKind.NEEDS_RECONNECT)
    }

    @Test
    fun `asking for a provider nobody registered is a programming error`() = runTest {
        failureOf<IllegalArgumentException> { authorization().headers(ProviderId("outro")) }
    }

    /** A exceção que o bloco suspenso lança (e falha o teste se não lançar ou se for de outro tipo). */
    private suspend inline fun <reified E : Throwable> failureOf(block: suspend () -> Any?): E {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(E::class.java)
        return thrown as E
    }
}
