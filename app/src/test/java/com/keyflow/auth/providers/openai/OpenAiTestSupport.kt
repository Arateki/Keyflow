package com.keyflow.auth.providers.openai

import com.keyflow.auth.oauth.Base64Url
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

/** Um par RSA de teste, o JWKS que o publica e a assinatura RS256 de JWTs — o que a OpenAI faz do lado dela. */
class TestSigner(val kid: String = "k1", private val pair: KeyPair = shared) {
    val jwks: String get() = JSONObject().put("keys", JSONArray().put(jwk())).toString()

    fun jwk(): JSONObject {
        val pub = pair.public as RSAPublicKey
        return JSONObject().put("kty", "RSA").put("kid", kid).put("alg", "RS256").put("use", "sig")
            .put("n", Base64Url.encode(unsigned(pub.modulus))).put("e", Base64Url.encode(unsigned(pub.publicExponent)))
    }

    /** Um JWT assinado com esta chave; [header] e [payload] podem ser adulterados pelo teste. */
    fun jwt(payload: JSONObject, header: JSONObject = JSONObject().put("alg", "RS256").put("typ", "JWT").put("kid", kid)): String {
        val signingInput = Base64Url.encode(header.toString().toByteArray()) + "." + Base64Url.encode(payload.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(pair.private)
            update(signingInput.toByteArray(Charsets.US_ASCII))
        }.sign()
        return signingInput + "." + Base64Url.encode(signature)
    }

    private fun unsigned(n: BigInteger): ByteArray = n.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

    companion object {
        val shared: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
        val other: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    }
}

/** O ID token como a OpenAI o emite: `iss`, `aud` = o client_id emitido, `sub`, `email`, `nonce`, `exp`. */
fun idTokenPayload(
    issuer: String = ISSUER,
    audience: String = "oaiapp_1",
    subject: String = "sub-1",
    email: String? = "pessoa@exemplo.com",
    nonce: String = "nonce-1",
    expSeconds: Long = NOW_SECONDS + 3_600,
): JSONObject = JSONObject().put("iss", issuer).put("aud", audience).put("sub", subject).put("nonce", nonce).put("exp", expSeconds)
    .put("iat", NOW_SECONDS).also { if (email != null) it.put("email", email) }

const val ISSUER = "https://issuer.test"
const val NOW_SECONDS = 1_800_000_000L
const val NOW_MS = NOW_SECONDS * 1000

/** Um servidor HTTP falso da OpenAI: descoberta, JWKS, token endpoint e revogação, com respostas e requisições roteirizáveis. */
class FakeOpenAi(val signer: TestSigner = TestSigner()) {
    val server = MockWebServer()
    val requests = mutableListOf<RecordedRequest>()
    var tokenResponses = ArrayDeque<MockResponse>()
    var revokeResponses = ArrayDeque<MockResponse>()
    var jwksBody: String = signer.jwks
    var discoveryIssuer: String = ISSUER

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return when (request.path) {
                    "/.well-known/openid-configuration" -> json(
                        JSONObject().put("issuer", discoveryIssuer).put("jwks_uri", url("/jwks.json")).put("revocation_endpoint", url("/oauth/revoke"))
                            .put("token_endpoint", url("/oauth/token")).put("authorization_endpoint", url("/authorize")).toString(),
                    )
                    "/jwks.json" -> json(jwksBody)
                    "/oauth/token" -> tokenResponses.removeFirstOrNull() ?: MockResponse().setResponseCode(500)
                    "/oauth/revoke" -> revokeResponses.removeFirstOrNull() ?: MockResponse().setResponseCode(200)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    fun url(path: String): String = server.url(path).toString()
    internal fun endpoints() = OpenAiEndpoints(discoveryUrl = url("/.well-known/openid-configuration"), authorizationUrl = url("/authorize"), tokenUrl = url("/oauth/token"), issuer = ISSUER)
    fun requestsTo(path: String): List<RecordedRequest> = synchronized(requests) { requests.filter { it.path == path } }
    fun shutdown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    fun tokenOk(
        access: String = "access-1",
        refresh: String? = "refresh-1",
        idToken: String? = null,
        expiresIn: Long = 3_600,
        scope: String = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
        earliest: Any? = null,
    ): MockResponse {
        val body = JSONObject().put("access_token", access).put("token_type", "Bearer").put("expires_in", expiresIn).put("scope", scope)
        if (refresh != null) body.put("refresh_token", refresh)
        if (idToken != null) body.put("id_token", idToken)
        if (earliest != null) body.put("earliest_refresh_at", earliest)
        return json(body.toString())
    }

    fun tokenError(status: Int, error: String): MockResponse =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(JSONObject().put("error", error).toString())
}

/** O corpo de um POST de formulário como mapa (o token endpoint e a revogação recebem `application/x-www-form-urlencoded`). */
fun RecordedRequest.form(): Map<String, String> = body.clone().readUtf8().split('&').filter { it.isNotEmpty() }.associate {
    val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
    java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
}
