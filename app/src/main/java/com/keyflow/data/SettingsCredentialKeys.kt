package com.keyflow.data

/** Nomes estáveis dos segredos de configuração dentro do cofre `keyflow_auth`. */
internal object SettingsCredentialKeys {
    const val OPENAI_TOKEN = "openai_api_key"
    const val GROQ_TOKEN = "groq_api_key"
    const val VISION_GOOGLE_API_KEY = "vision_google_api_key"
    const val VISION_ANTHROPIC_API_KEY = "vision_anthropic_api_key"
    const val FALA_ELEVENLABS_API_KEY = "fala_elevenlabs_api_key"
    const val FALA_GOOGLE_API_KEY = "fala_google_api_key"
    const val PROVIDER_GROQ_API_KEY = "provider_groq_api_key"
    const val PROVIDER_GROK_API_KEY = "provider_grok_api_key"
    const val CUSTOM_HEADER = "custom_header_value"
    const val REASONING_CUSTOM_HEADER = "reasoning_custom_header_value"
    const val VISION_CUSTOM_HEADER = "vision_custom_header_value"
    const val FALA_CUSTOM_HEADER = "fala_custom_header_value"
    const val COMPUTER_SSH_PASSWORD = "computer_ssh_password"
    const val COMPUTER_SSH_KEY = "computer_ssh_key"

    val ALL = listOf(
        OPENAI_TOKEN, GROQ_TOKEN, VISION_GOOGLE_API_KEY, VISION_ANTHROPIC_API_KEY,
        FALA_ELEVENLABS_API_KEY, FALA_GOOGLE_API_KEY, PROVIDER_GROQ_API_KEY, PROVIDER_GROK_API_KEY,
        CUSTOM_HEADER, REASONING_CUSTOM_HEADER, VISION_CUSTOM_HEADER, FALA_CUSTOM_HEADER,
        COMPUTER_SSH_PASSWORD, COMPUTER_SSH_KEY,
    )
}

/** Um campo de segredo que o estado Compose pode carregar sem carregar o segredo. */
data class SecretDisplay(val masked: String = "") {
    val isConfigured: Boolean get() = masked.isNotEmpty()
}

/** Só o repositório de dados usa os valores decifrados para montar a Config do transcritor. */
internal data class SettingsCredentialValues(
    val openaiToken: String = "",
    val groqToken: String = "",
)
