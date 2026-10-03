package com.keyflow.auth.providers.anthropic

import com.keyflow.auth.AuthDependencies
import com.keyflow.auth.Credential
import com.keyflow.auth.ProviderAuth
import com.keyflow.auth.ProviderIds

internal object AnthropicProvider {

    fun headersFor(credential: Credential): Map<String, String> = when (credential) {
        is Credential.ApiKey -> mapOf(
            "x-api-key" to credential.value,
            "anthropic-version" to "2023-06-01",
        )
        is Credential.OAuth -> mapOf(
            "Authorization" to "Bearer ${credential.accessToken}",
            "anthropic-version" to "2023-06-01",
            "anthropic-beta" to "oauth-2025-04-20,claude-code-20250219",
            "User-Agent" to "claude-code/2.1.131 (cli)",
        )
    }

    fun planUsage(credential: Credential.OAuth): Boolean = true

    fun create(dependencies: AuthDependencies): ProviderAuth {
        val sso = AnthropicSsoFlow(
            store = dependencies.store,
            http = dependencies.http,
            clock = dependencies.clock,
        )
        return ProviderAuth(
            id = ProviderIds.ANTHROPIC,
            displayName = "Anthropic",
            flows = listOf(AnthropicApiKeyFlow(), sso),
            planUsage = ::planUsage,
            usageUrl = AnthropicAuthConfig.usageUrl,
            headersFor = ::headersFor,
        )
    }
}
