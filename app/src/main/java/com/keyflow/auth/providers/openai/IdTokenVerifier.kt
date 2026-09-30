package com.keyflow.auth.providers.openai

import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.auth.oauth.Base64Url
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Verifica o ID token como a documentação da OpenAI manda (`sign-in` §4): **assinatura** contra o JWKS publicado, **emissor**,
 * **audiência** (o client_id emitido), **validade** e o **nonce** da tentativa; o `sub` verificado é a identidade da conta.
 * Só RS256 é aceito (o documento de descoberta só publica RS256) — `none` e algoritmos simétricos são recusados. Qualquer
 * falha de conteúdo é `INVALID_RESPONSE`; JWKS inalcançável é `NETWORK` (temporário, não é "token inválido").
 */
internal class IdTokenVerifier(
    private val http: OkHttpClient,
    private val discovery: OpenAiDiscovery,
    private val issuer: String,
    private val clock: () -> Long,
) {
    data class Identity(val subject: String, val email: String?)

    private val keys = ConcurrentHashMap<String, PublicKey>()

    suspend fun verify(idToken: String, audience: String, nonce: String): Identity {
        val parts = idToken.split('.')
        if (parts.size != 3 || parts.any { it.isEmpty() }) throw invalid("ID token malformado")
        val header = decodeJson(parts[0])
        val payload = decodeJson(parts[1])
        if (header.optString("alg") != "RS256") throw invalid("algoritmo não aceito")

        val key = keyFor(header.optString("kid"))
        val signature = try {
            Base64Url.decode(parts[2])
        } catch (e: IllegalArgumentException) {
            throw invalid("assinatura malformada")
        }
        val valid = try {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
                verify(signature)
            }
        } catch (e: java.security.GeneralSecurityException) {
            false
        }
        if (!valid) throw invalid("assinatura do ID token não confere")

        if (payload.optString("iss") != issuer) throw invalid("emissor do ID token não confere")
        if (!audienceMatches(payload, audience)) throw invalid("audiência do ID token não confere")
        if (!payload.has("exp") || payload.optLong("exp") * 1000 + LEEWAY_MS < clock()) throw invalid("ID token expirado")
        val tokenNonce = payload.optString("nonce")
        if (tokenNonce.isEmpty() || !MessageDigest.isEqual(tokenNonce.toByteArray(), nonce.toByteArray())) throw invalid("nonce do ID token não confere")
        val subject = payload.optString("sub")
        if (subject.isEmpty()) throw invalid("ID token sem identidade")
        return Identity(subject, payload.optString("email").ifEmpty { null })
    }

    /** A chave do `kid`; se não houver, UMA nova leitura do JWKS (a OpenAI pode ter girado as chaves) e então desiste. */
    private suspend fun keyFor(kid: String): PublicKey {
        keys[kid]?.let { return it }
        loadKeys()
        return keys[kid] ?: throw invalid("chave de assinatura desconhecida")
    }

    private suspend fun loadKeys() {
        val jwks = http.getJson(discovery.get().jwksUri).optJSONArray("keys") ?: throw invalid("JWKS sem chaves")
        for (i in 0 until jwks.length()) {
            val jwk = jwks.optJSONObject(i) ?: continue
            if (jwk.optString("kty") != "RSA" || (jwk.has("use") && jwk.optString("use") != "sig")) continue
            val kid = jwk.optString("kid")
            if (kid.isEmpty()) continue
            try {
                val spec = RSAPublicKeySpec(BigInteger(1, Base64Url.decode(jwk.getString("n"))), BigInteger(1, Base64Url.decode(jwk.getString("e"))))
                keys[kid] = KeyFactory.getInstance("RSA").generatePublic(spec)
            } catch (e: Exception) {
                continue // uma chave ruim não invalida as outras
            }
        }
    }

    private fun audienceMatches(payload: JSONObject, audience: String): Boolean {
        val aud = payload.opt("aud")
        return when (aud) {
            is String -> aud == audience
            is org.json.JSONArray -> (0 until aud.length()).any { aud.optString(it) == audience }
            else -> false
        }
    }

    private fun decodeJson(part: String): JSONObject = try {
        JSONObject(String(Base64Url.decode(part), Charsets.UTF_8))
    } catch (e: Exception) {
        throw invalid("ID token malformado")
    }

    private fun invalid(reason: String) = AuthException(AuthErrorKind.INVALID_RESPONSE, reason)

    private companion object {
        /** Tolerância de relógio entre o aparelho e a OpenAI. */
        const val LEEWAY_MS = 60_000L
    }
}
