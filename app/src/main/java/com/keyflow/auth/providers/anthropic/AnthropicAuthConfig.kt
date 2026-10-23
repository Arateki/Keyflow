package com.keyflow.auth.providers.anthropic

internal object AnthropicAuthConfig {
    /** O login por assinatura também faz parte do release (decisão do Yan, 2026-10-23). */
    const val enabled: Boolean = true

    const val apiBaseUrl = "https://api.anthropic.com/v1"
    const val usageUrl = "https://claude.ai/settings/billing"
}
