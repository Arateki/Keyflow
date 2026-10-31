package com.keyflow.notificacao

/** A decisão da tela bloqueada para um toque na orbe ou num botão (NTF-15..17, NTF-49, design §10). */
sealed interface Portao {
    data object Aceitar : Portao
    data object RecusarBloqueado : Portao
    data object RecusarPrecisaDesbloqueio : Portao
}

object PortaoDeToque {
    /**
     * - `bloqueada && !permitir` → [Portao.RecusarBloqueado] (o toque não é contado nem age);
     * - `bloqueada && permitir && precisaDeActivity` → [Portao.RecusarPrecisaDesbloqueio] (a Activity exigiria o desbloqueio);
     * - senão → [Portao.Aceitar].
     *
     * `bloqueada` vem de `KeyguardManager.isKeyguardLocked()` **a cada toque** (nunca guardada), injetável para a JVM.
     */
    fun avaliar(bloqueada: Boolean, permitir: Boolean, precisaDeActivity: Boolean): Portao = when {
        bloqueada && !permitir -> Portao.RecusarBloqueado
        bloqueada && precisaDeActivity -> Portao.RecusarPrecisaDesbloqueio
        else -> Portao.Aceitar
    }
}
