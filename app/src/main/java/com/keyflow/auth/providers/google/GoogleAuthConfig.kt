package com.keyflow.auth.providers.google

import com.keyflow.BuildConfig

internal object GoogleAuthConfig {
    // Só em debug, como o login da OpenAI e o da Anthropic (escolha conservadora da integração, 2026-10-03: o client_id abaixo não tem origem
    // documentada no repositório). Ligar no release só depois de confirmar de quem é o registro do app no Google.
    val enabled: Boolean = BuildConfig.DEBUG

    const val authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth"
    const val tokenUrl = "https://oauth2.googleapis.com/token"
    const val userinfoUrl = "https://openidconnect.googleapis.com/v1/userinfo"
    const val revokeUrl = "https://oauth2.googleapis.com/revoke"
    const val apiBaseUrl = "https://generativelanguage.googleapis.com/v1beta"
    const val defaultClientId = "926521949168-d0b43jcf885m8kh024kfa7cblv3d3qsf.apps.googleusercontent.com"
    val scopes = listOf(
        "openid",
        "email",
        "profile",
        "https://www.googleapis.com/auth/generative-language",
    )
    const val redirectPath = "/auth/callback/google"
    const val usageUrl = "https://aistudio.google.com"
}
