package com.keyflow.auth.providers.google

import com.keyflow.auth.AuthDependencies
import com.keyflow.auth.Credential
import com.keyflow.auth.ProviderAuth
import com.keyflow.auth.ProviderIds

internal object GoogleProvider {

    fun headersFor(credential: Credential): Map<String, String> = when (credential) {
        is Credential.ApiKey -> mapOf("x-goog-api-key" to credential.value)
        is Credential.OAuth -> mapOf("Authorization" to "Bearer ${credential.accessToken}")
    }

    fun planUsage(credential: Credential.OAuth): Boolean = true

    fun create(dependencies: AuthDependencies): ProviderAuth {
        val sso = GoogleSsoFlow(
            store = dependencies.store,
            http = dependencies.http,
            clock = dependencies.clock,
        )
        return ProviderAuth(
            id = ProviderIds.GOOGLE,
            displayName = "Google",
            flows = listOf(GoogleApiKeyFlow(), sso),
            planUsage = ::planUsage,
            usageUrl = GoogleAuthConfig.usageUrl,
            headersFor = ::headersFor,
        )
    }
}
