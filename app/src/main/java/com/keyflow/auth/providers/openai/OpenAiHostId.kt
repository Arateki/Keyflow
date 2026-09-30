package com.keyflow.auth.providers.openai

import com.keyflow.auth.CredentialStore
import com.keyflow.auth.ProviderIds
import java.util.UUID

/**
 * O `ext_agent_host_id`: um identificador opaco e ESTÁVEL desta instalação, escolhido e guardado antes do 1º login e reenviado
 * em todos. Formato `urn:uuid:<UUIDv4>` — um dos aceitos pela doc; a alternativa por chave pública é "only an identifier"
 * (a OpenAI não verifica a posse da chave), então o Keystore não traria ganho. Fica como *meta* do cofre: sobrevive ao
 * Desconectar e à renovação que falha. Não é credencial e não carrega dado do usuário.
 */
internal class OpenAiHostId(private val store: CredentialStore) {
    @Synchronized
    fun get(): String = store.meta(ProviderIds.OPENAI, KEY) ?: "urn:uuid:${UUID.randomUUID()}".also { store.putMeta(ProviderIds.OPENAI, KEY, it) }

    private companion object {
        const val KEY = "ext_agent_host_id"
    }
}
