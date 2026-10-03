package com.keyflow.auth.providers.anthropic

import com.keyflow.BuildConfig

internal object AnthropicAuthConfig {
    val enabled: Boolean = BuildConfig.DEBUG

    // Credenciais e endpoints oficiais do Claude Code CLI
    const val defaultClientId = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    const val authorizationUrl = "https://claude.com/cai/oauth/authorize"
    const val consoleAuthorizationUrl = "https://platform.claude.com/oauth/authorize"
    const val tokenUrl = "https://platform.claude.com/v1/oauth/token"
    const val profileUrl = "https://api.anthropic.com/api/oauth/profile"
    const val apiBaseUrl = "https://api.anthropic.com/v1"
    val scopes = listOf(
        "org:create_api_key",
        "user:profile",
        "user:inference",
        "user:sessions:claude_code",
        "user:mcp_servers",
        "user:file_upload",
    )
    const val redirectPath = "/callback"
    const val manualRedirectUrl = "https://platform.claude.com/oauth/code/callback"
    const val usageUrl = "https://claude.ai/settings/billing"
}
