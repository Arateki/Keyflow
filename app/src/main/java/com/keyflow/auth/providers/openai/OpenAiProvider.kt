package com.keyflow.auth.providers.openai

import com.keyflow.auth.AuthDependencies
import com.keyflow.auth.Credential
import com.keyflow.auth.ProviderAuth
import com.keyflow.auth.ProviderIds

/** A OpenAI como [ProviderAuth]: dois jeitos de entrar (chave e login ChatGPT) e o formato dos cabeçalhos, que é dela. */
internal object OpenAiProvider {

    /** Chave de API e token de login vão do mesmo jeito: `Authorization: Bearer …` (a mesma base de URL da Responses API). */
    fun headersFor(credential: Credential): Map<String, String> = when (credential) {
        is Credential.ApiKey -> mapOf("Authorization" to "Bearer ${credential.value}")
        is Credential.OAuth -> mapOf("Authorization" to "Bearer ${credential.accessToken}")
    }

    /** Sem o escopo do plano o login vale (identidade), mas não dá direito a usar o plano do usuário (RCL-07). */
    fun planUsage(credential: Credential.OAuth): Boolean = OpenAiAuthConfig.planScope in credential.scopes

    fun create(dependencies: AuthDependencies): ProviderAuth {
        val endpoints = OpenAiEndpoints()
        val http = OpenAiHttp.client(dependencies.http)
        val discovery = OpenAiDiscovery(http, endpoints.discoveryUrl, endpoints.issuer)
        val login = OpenAiChatGptFlow(
            store = dependencies.store,
            http = http,
            endpoints = endpoints,
            discovery = discovery,
            verifier = IdTokenVerifier(http, discovery, endpoints.issuer, dependencies.clock),
            hostId = OpenAiHostId(dependencies.store),
            clock = dependencies.clock,
        )
        return ProviderAuth(
            id = ProviderIds.OPENAI,
            displayName = "OpenAI",
            flows = listOf(OpenAiApiKeyFlow(), login),
            planUsage = ::planUsage,
            headersFor = ::headersFor,
        )
    }
}
