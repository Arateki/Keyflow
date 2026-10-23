package com.keyflow.auth.providers.anthropic

import com.keyflow.BuildConfig

internal object AnthropicAuthConfig {
    /** O login fica disponível só no debug; a variante release nem compila o fluxo OAuth. */
    val enabled: Boolean = BuildConfig.DEBUG

    const val apiBaseUrl = "https://api.anthropic.com/v1"
    const val usageUrl = "https://claude.ai/settings/billing"
}
