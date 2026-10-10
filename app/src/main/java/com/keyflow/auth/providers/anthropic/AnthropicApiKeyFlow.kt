package com.keyflow.auth.providers.anthropic

import com.keyflow.auth.ApiKeyFlow
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.Availability
import com.keyflow.auth.Credential

internal class AnthropicApiKeyFlow : ApiKeyFlow {
    override val method: AuthMethod = AuthMethod.API_KEY
    override val availability: Availability = Availability.Available

    override suspend fun revoke(current: Credential) {
        // Chave de API não tem revogação remota via endpoint padrão
    }
}
