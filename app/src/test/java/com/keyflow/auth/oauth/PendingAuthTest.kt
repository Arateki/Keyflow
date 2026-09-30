package com.keyflow.auth.oauth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** AUTH-08, Review Focus 1: uma tentativa de login pendente por vez, que expira e só vale uma vez, com o `state` certo. */
class PendingAuthTest {

    private var now = 0L
    private val holder = PendingAuthHolder(clock = { now }, ttlMs = 1_000)

    @Test
    fun `consuming with the right state returns the attempt once`() {
        holder.begin("estado", "nonce", "verifier")
        val first = holder.consume("estado")
        assertThat(first?.verifier).isEqualTo("verifier")
        assertThat(first?.nonce).isEqualTo("nonce")
        assertThat(holder.consume("estado")).isNull() // uma vez só
    }

    /** Callback de um atacante local ou de outra aba: o `state` errado é recusado e NÃO derruba a tentativa legítima. */
    @Test
    fun `a wrong state is refused and keeps the real attempt alive`() {
        holder.begin("estado", "nonce", "verifier")
        assertThat(holder.consume("outro")).isNull()
        assertThat(holder.consume("estado")).isNotNull()
    }

    @Test
    fun `a new begin replaces the previous attempt`() {
        holder.begin("velho", "n1", "v1")
        holder.begin("novo", "n2", "v2")
        assertThat(holder.consume("velho")).isNull()
        assertThat(holder.consume("novo")?.verifier).isEqualTo("v2")
    }

    @Test
    fun `an attempt expires after the ttl`() {
        holder.begin("estado", "nonce", "verifier")
        now = 999
        assertThat(holder.peek()).isNotNull()
        now = 1_000
        assertThat(holder.consume("estado")).isNull()
        assertThat(holder.peek()).isNull() // e não fica guardada
    }

    @Test
    fun `nothing pending consumes to null`() {
        assertThat(holder.consume("qualquer")).isNull()
    }

    @Test
    fun `a callback arriving with no pending attempt is refused — the app died mid login`() {
        val fresh = PendingAuthHolder(clock = { now }) // processo novo: nada em memória
        assertThat(fresh.consume("estado-de-antes")).isNull()
    }

    @Test
    fun `the state comparison does not accept a prefix or an empty state`() {
        holder.begin("abcdef", "n", "v")
        assertThat(holder.consume("abc")).isNull()
        assertThat(holder.consume("")).isNull()
        assertThat(holder.consume("abcdefg")).isNull()
        assertThat(holder.consume("abcdef")).isNotNull()
    }
}
