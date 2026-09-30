package com.keyflow.auth.oauth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** AUTH-08: PKCE (S256) e os valores aleatórios do login — contra o vetor oficial do RFC 7636. */
class PkceTest {

    /** RFC 7636, Apêndice B: o par verifier → challenge de referência. */
    @Test
    fun `the challenge matches the RFC 7636 appendix B vector`() {
        assertThat(Pkce.challengeS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
            .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    @Test
    fun `a new verifier has a valid length and only unreserved characters`() {
        repeat(20) {
            val verifier = Pkce.newVerifier()
            assertThat(verifier.length).isAtLeast(43)
            assertThat(verifier.length).isAtMost(128)
            assertThat(verifier).matches("[A-Za-z0-9\\-._~]+")
        }
    }

    @Test
    fun `verifiers, states and nonces never repeat`() {
        assertThat((1..200).map { Pkce.newVerifier() }.toSet()).hasSize(200)
        assertThat((1..200).map { Pkce.randomToken() }.toSet()).hasSize(200)
    }

    @Test
    fun `base64url has no padding and no plus or slash`() {
        // Bytes que em base64 comum dariam '+', '/' e '=' de preenchimento.
        val encoded = Base64Url.encode(byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xbe.toByte(), 0x01))
        assertThat(encoded).isEqualTo("-_--AQ")
        assertThat(Base64Url.encode(ByteArray(0))).isEmpty()
        assertThat(Base64Url.encode("f".toByteArray())).isEqualTo("Zg")
        assertThat(Base64Url.encode("fo".toByteArray())).isEqualTo("Zm8")
        assertThat(Base64Url.encode("foo".toByteArray())).isEqualTo("Zm9v")
    }

    @Test
    fun `base64url decodes what it encodes`() {
        val bytes = ByteArray(97) { (it * 7).toByte() }
        assertThat(Base64Url.decode(Base64Url.encode(bytes))).isEqualTo(bytes)
    }
}
