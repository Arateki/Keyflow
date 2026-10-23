package com.keyflow.auth.providers.anthropic

import com.keyflow.auth.AuthDependencies
import com.keyflow.auth.OAuthFlow

internal object AnthropicSsoFlowFactory {
    fun create(dependencies: AuthDependencies): OAuthFlow = AnthropicSsoFlow(
        store = dependencies.store,
        http = dependencies.http,
        clock = dependencies.clock,
        enabled = AnthropicAuthConfig.enabled,
    )
}
