package com.keyflow.data.monetization

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnonymousIdentityTest {
    @Test
    fun `arquivo claro vence o Block Store`() {
        val result = AnonymousIdentityResolver.resolve(
            fileIdentity = "550e8400-e29b-41d4-a716-446655440000",
            blockIdentity = "6ba7b810-9dad-41d1-80b4-00c04fd430c8",
            newIdentity = { error("não deve gerar") },
        )

        assertThat(result.value).isEqualTo("550e8400-e29b-41d4-a716-446655440000")
        assertThat(result.source).isEqualTo(IdentitySource.FILE)
    }

    @Test
    fun `Block Store e usado antes de gerar um ID novo`() {
        val result = AnonymousIdentityResolver.resolve(
            fileIdentity = null,
            blockIdentity = "6ba7b810-9dad-41d1-80b4-00c04fd430c8",
            newIdentity = { error("não deve gerar") },
        )

        assertThat(result.value).isEqualTo("6ba7b810-9dad-41d1-80b4-00c04fd430c8")
        assertThat(result.source).isEqualTo(IdentitySource.BLOCK_STORE)
    }

    @Test
    fun `ID novo e normalizado quando as duas fontes faltam`() {
        val result = AnonymousIdentityResolver.resolve(
            fileIdentity = "não é UUID",
            blockIdentity = null,
            newIdentity = { "550E8400-E29B-41D4-A716-446655440000" },
        )

        assertThat(result.value).isEqualTo("550e8400-e29b-41d4-a716-446655440000")
        assertThat(result.source).isEqualTo(IdentitySource.NEW)
    }

    @Test
    fun `somente UUID v4 variante RFC 4122 e aceito`() {
        assertThat(AnonymousIdentityResolver.canonicalUuid("550e8400-e29b-11d4-a716-446655440000")).isNull()
        assertThat(AnonymousIdentityResolver.canonicalUuid("550e8400-e29b-41d4-a716-446655440000"))
            .isEqualTo("550e8400-e29b-41d4-a716-446655440000")
    }
}
