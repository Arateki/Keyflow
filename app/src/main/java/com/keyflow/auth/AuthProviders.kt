package com.keyflow.auth

import com.keyflow.auth.providers.anthropic.AnthropicProvider
import com.keyflow.auth.providers.google.GoogleProvider
import com.keyflow.auth.providers.openai.OpenAiProvider
import com.keyflow.data.models.KeyFlowHttpClient
import okhttp3.OkHttpClient

/** O que um provedor precisa do resto do app para funcionar. Cresce conforme os provedores entram. */
class AuthDependencies(
    val store: CredentialStore,
    val clock: () -> Long = System::currentTimeMillis,
    val http: OkHttpClient = KeyFlowHttpClient.base(),
)

/**
 * O registro: a ÚNICA lista que conhece provedores concretos (o `AuthIsolationTest` confere). A UI monta os botões a partir
 * dela (AUTH-03), sem nenhum `if (provedor == …)`. Adicionar um provedor = uma linha aqui, mais a config, o fluxo e o motor dele.
 */
object AuthProviders {
    fun create(dependencies: AuthDependencies): List<ProviderAuth> = listOf(
        OpenAiProvider.create(dependencies),
        AnthropicProvider.create(dependencies),
        GoogleProvider.create(dependencies),
    )
}
