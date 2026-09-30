package com.keyflow.auth.oauth

import java.security.MessageDigest

/** Uma tentativa de login em andamento: o que só quem a começou sabe (o `verifier` do PKCE e o `nonce` do ID token). */
class PendingAuth(val state: String, val nonce: String, val verifier: String, val expiresAtMs: Long)

/**
 * A tentativa pendente (AUTH-08, Review Focus 1): **uma por vez**, só em memória, que **expira** e só vale **uma vez**, com o
 * `state` certo. Se o app morrer durante o login, o callback chega sem tentativa e é descartado — o usuário toca "Entrar" de
 * novo. Um `state` errado é recusado e NÃO derruba a tentativa legítima (um callback forjado não pode cancelar o login).
 */
class PendingAuthHolder(private val clock: () -> Long, private val ttlMs: Long = 600_000) {
    private var pending: PendingAuth? = null

    /** Começa uma tentativa, substituindo a anterior. */
    @Synchronized
    fun begin(state: String, nonce: String, verifier: String): PendingAuth =
        PendingAuth(state, nonce, verifier, clock() + ttlMs).also { pending = it }

    /** A tentativa vigente, se ainda não expirou (não a consome). */
    @Synchronized
    fun peek(): PendingAuth? = live()

    /** Entrega a tentativa se o [state] confere (comparação em tempo constante) e a apaga; senão `null`, sem apagar. */
    @Synchronized
    fun consume(state: String): PendingAuth? {
        val current = live() ?: return null
        val same = MessageDigest.isEqual(current.state.toByteArray(Charsets.UTF_8), state.toByteArray(Charsets.UTF_8))
        if (!same) return null
        pending = null
        return current
    }

    private fun live(): PendingAuth? {
        val current = pending ?: return null
        if (clock() >= current.expiresAtMs) {
            pending = null
            return null
        }
        return current
    }
}
