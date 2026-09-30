package com.keyflow.auth.providers.openai

import com.keyflow.auth.ApiKeyFlow
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.Availability
import com.keyflow.auth.Credential

/**
 * Chave de API da OpenAI: colar e guardar. **Sempre disponível** — o interruptor `enabled` da config é só do login ChatGPT
 * (RPRV-04); a chave funciona sem depender da decisão de elegibilidade.
 */
internal class OpenAiApiKeyFlow : ApiKeyFlow {
    override val method = AuthMethod.API_KEY
    override val availability: Availability = Availability.Available

    /** Uma chave de API não se revoga daqui (é da conta do usuário na OpenAI). */
    override suspend fun revoke(current: Credential) = Unit
}
