package com.keyflow.auth

/** O que um provedor precisa do resto do app para funcionar. Cresce (rede, etc.) conforme os provedores entram. */
class AuthDependencies(
    val store: CredentialStore,
    val clock: () -> Long = System::currentTimeMillis,
)

/**
 * O registro: a ÚNICA lista que conhece provedores concretos. A UI monta os botões a partir dela (AUTH-03), sem nenhum
 * `if (provedor == …)`. Adicionar um provedor = uma linha aqui, mais a config, o fluxo e o motor dele.
 */
object AuthProviders {
    fun create(dependencies: AuthDependencies): List<ProviderAuth> = emptyList()
}
