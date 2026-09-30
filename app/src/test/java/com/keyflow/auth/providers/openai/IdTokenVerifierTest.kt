package com.keyflow.auth.providers.openai

import com.google.common.truth.Truth.assertThat
import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Test

/**
 * O que a documentação da OpenAI manda validar no ID token — assinatura pelo JWKS publicado, `iss`, `aud` (o client_id emitido),
 * `exp` e o `nonce` da tentativa — e cada jeito de falsificá-lo (AUTH-08, Review Focus 1).
 */
class IdTokenVerifierTest {

    private val openai = FakeOpenAi()
    private val http = OkHttpClient()
    private fun verifier(): IdTokenVerifier {
        val discovery = OpenAiDiscovery(http, openai.endpoints().discoveryUrl, ISSUER)
        return IdTokenVerifier(http, discovery, ISSUER) { NOW_MS }
    }

    @After fun tearDown() = openai.shutdown()

    private fun verify(token: String, audience: String = "oaiapp_1", nonce: String = "nonce-1") =
        runBlocking { verifier().verify(token, audience, nonce) }

    private fun failure(token: String, audience: String = "oaiapp_1", nonce: String = "nonce-1"): AuthException =
        runCatching { verify(token, audience, nonce) }.exceptionOrNull() as? AuthException ?: error("deveria ter falhado com AuthException")

    @Test
    fun `it accepts a valid token and returns the verified identity`() {
        val identity = verify(openai.signer.jwt(idTokenPayload()))
        assertThat(identity.subject).isEqualTo("sub-1")
        assertThat(identity.email).isEqualTo("pessoa@exemplo.com")
    }

    @Test
    fun `the email is optional`() {
        assertThat(verify(openai.signer.jwt(idTokenPayload(email = null))).email).isNull()
    }

    @Test
    fun `it rejects a token signed with another key`() {
        val forger = TestSigner(kid = "k1", pair = TestSigner.other) // mesmo kid, chave errada
        assertThat(failure(forger.jwt(idTokenPayload())).kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
    }

    @Test
    fun `it rejects a token whose payload was changed after signing`() {
        val parts = openai.signer.jwt(idTokenPayload(subject = "sub-1")).split('.')
        val tampered = com.keyflow.auth.oauth.Base64Url.encode(idTokenPayload(subject = "invasor").toString().toByteArray())
        assertThat(failure("${parts[0]}.$tampered.${parts[2]}").kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
    }

    @Test
    fun `it rejects the wrong issuer`() {
        assertThat(failure(openai.signer.jwt(idTokenPayload(issuer = "https://outro.test"))).kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
    }

    @Test
    fun `it rejects an audience that is not the issued client id`() {
        assertThat(failure(openai.signer.jwt(idTokenPayload(audience = "dynamic_agent_client"))).kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
    }

    @Test
    fun `an audience array is accepted when it contains the client id`() {
        val payload = idTokenPayload().put("aud", JSONArray().put("outro").put("oaiapp_1"))
        assertThat(verify(openai.signer.jwt(payload)).subject).isEqualTo("sub-1")
    }

    @Test
    fun `an audience array without the client id is rejected`() {
        val payload = idTokenPayload().put("aud", JSONArray().put("outro"))
        assertThat(failure(openai.signer.jwt(payload)).kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
    }

    @Test
    fun `it rejects an expired token but tolerates a minute of clock skew`() {
        assertThat(failure(openai.signer.jwt(idTokenPayload(expSeconds = NOW_SECONDS - 61))).kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
        assertThat(verify(openai.signer.jwt(idTokenPayload(expSeconds = NOW_SECONDS - 30))).subject).isEqualTo("sub-1")
    }

    @Test
    fun `it rejects a different nonce`() {
        assertThat(failure(openai.signer.jwt(idTokenPayload(nonce = "outro-nonce"))).kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
    }

    @Test
    fun `it rejects a token with no nonce`() {
        val payload = idTokenPayload().also { it.remove("nonce") }
        assertThat(failure(openai.signer.jwt(payload)).kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
    }

    @Test
    fun `it rejects an algorithm other than RS256 — including none`() {
        listOf("none", "HS256", "RS512").forEach { alg ->
            val header = JSONObject().put("alg", alg).put("typ", "JWT").put("kid", "k1")
            assertThat(failure(openai.signer.jwt(idTokenPayload(), header)).kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
        }
    }

    @Test
    fun `an unsigned token with alg none is rejected`() {
        val header = com.keyflow.auth.oauth.Base64Url.encode("""{"alg":"none"}""".toByteArray())
        val payload = com.keyflow.auth.oauth.Base64Url.encode(idTokenPayload().toString().toByteArray())
        assertThat(failure("$header.$payload.").kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
    }

    @Test
    fun `it rejects a kid the JWKS does not have`() {
        val stranger = TestSigner(kid = "desconhecido")
        assertThat(failure(stranger.jwt(idTokenPayload())).kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
    }

    /** A OpenAI pode girar as chaves: um `kid` novo faz UMA nova busca do JWKS antes de desistir. */
    @Test
    fun `an unknown kid triggers exactly one JWKS refetch and then succeeds if the key rotated in`() {
        val v = verifier()
        val old = TestSigner(kid = "velha", pair = TestSigner.other)
        openai.jwksBody = old.jwks
        assertThat(runBlocking { v.verify(old.jwt(idTokenPayload()), "oaiapp_1", "nonce-1") }.subject).isEqualTo("sub-1")
        assertThat(openai.requestsTo("/jwks.json")).hasSize(1)

        openai.jwksBody = JSONObject().put("keys", JSONArray().put(old.jwk()).put(openai.signer.jwk())).toString() // a nova chegou
        assertThat(runBlocking { v.verify(openai.signer.jwt(idTokenPayload()), "oaiapp_1", "nonce-1") }.subject).isEqualTo("sub-1")
        assertThat(openai.requestsTo("/jwks.json")).hasSize(2)
    }

    @Test
    fun `the JWKS is cached between verifications`() {
        val v = verifier()
        repeat(3) { runBlocking { v.verify(openai.signer.jwt(idTokenPayload()), "oaiapp_1", "nonce-1") } }
        assertThat(openai.requestsTo("/jwks.json")).hasSize(1)
        assertThat(openai.requestsTo("/.well-known/openid-configuration")).hasSize(1)
    }

    @Test
    fun `malformed tokens are rejected, not crashed on`() {
        listOf("", "abc", "a.b", "a.b.c.d", "!!!.###.@@@", "e30.e30.").forEach {
            assertThat(failure(it).kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
        }
    }

    @Test
    fun `an unreachable JWKS is a network error, not an invalid token`() {
        openai.shutdown()
        assertThat(failure(openai.signer.jwt(idTokenPayload())).kind).isEqualTo(AuthErrorKind.NETWORK)
    }
}
