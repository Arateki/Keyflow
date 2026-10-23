package com.keyflow.auth.providers.anthropic

import com.keyflow.auth.AuthDependencies
import com.keyflow.auth.OAuthFlow

/** No release, o login por assinatura da Anthropic não entra no APK. */
internal object AnthropicSsoFlowFactory {
    fun create(@Suppress("UNUSED_PARAMETER") dependencies: AuthDependencies): OAuthFlow? = null
}
