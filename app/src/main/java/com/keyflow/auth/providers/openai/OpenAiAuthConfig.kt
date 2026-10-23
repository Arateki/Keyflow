package com.keyflow.auth.providers.openai

/**
 * A configuração do login da OpenAI — SÓ dados, sem lógica (AUTH-05). É o único arquivo a editar quando a OpenAI mudar algo.
 * Cada valor vem do texto literal da documentação (`.specs/features/reasoning-models/openai-siwc-notes.md`, conferido em
 * 2026-09-29). Recurso em prévia: reconferir antes de publicar.
 */
internal object OpenAiAuthConfig {
    /** Login por assinatura disponível no release por decisão do Yan (2026-10-23); chave API independente. */
    const val enabled: Boolean = true

    /** De onde saem o `jwks_uri` e o `revocation_endpoint` (a doc manda usar a descoberta, não fixar a URL). */
    const val discoveryUrl = "https://auth.openai.com/.well-known/openid-configuration"
    const val issuer = "https://auth.openai.com"
    const val authorizationUrl = "https://auth.openai.com/api/accounts/authorize"
    const val tokenUrl = "https://auth.openai.com/api/accounts/oauth/token"

    /** Só no 1º login de uma conta; depois vale o `oaiapp_…` emitido (a doc: "not the client ID to save or use for token exchange"). */
    const val provisionalClientId = "dynamic_agent_client"
    val scopes = listOf("openid", "profile", "email", "offline_access", "resource.invoke", "chatgpt.tokens.use.direct")
    const val planScope = "chatgpt.tokens.use.direct"
    const val resource = "https://api.openai.com/v1"

    /** Escolhido no 1º registro; NUNCA mudar: a doc exige o mesmo caminho depois ("`/callback` does not match `/auth/callback`"). */
    const val redirectPath = "/auth/callback"
    const val agentNameHint = "KeyFlow"
    const val apiBaseUrl = "https://api.openai.com/v1"
    const val usageUrl = "https://chatgpt.com/settings/usage"
    const val docsSource = "https://developers.openai.com/siwc/token-sharing-open-source"
}

/** Os endereços do login, injetáveis para os testes apontarem para um servidor falso. */
internal data class OpenAiEndpoints(
    val discoveryUrl: String = OpenAiAuthConfig.discoveryUrl,
    val authorizationUrl: String = OpenAiAuthConfig.authorizationUrl,
    val tokenUrl: String = OpenAiAuthConfig.tokenUrl,
    val issuer: String = OpenAiAuthConfig.issuer,
)
