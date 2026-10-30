package com.keyflow.agenthost

import android.content.SharedPreferences
import com.keyflow.auth.CredentialStore
import com.keyflow.auth.ProviderId
import com.keyflow.auth.ProviderIds

/**
 * Os valores secretos de agora, para o [com.keyflow.agent.SecretFilter] trocar por valor (TLS-03, D5): as credenciais do app que a
 * heurística de formato não cobre se o provedor mudar o formato da chave. Duas fontes, SÓ LEITURA:
 *  - o cofre cifrado das `Settings` (`keyflow_secure`): TODOS os valores guardados lá, não só uma lista — um segredo novo que alguém
 *    ponha no cofre entra aqui sem ninguém lembrar de acrescentá-lo (o `SettingsSecretsTest` já amarra as chaves conhecidas);
 *  - o cofre de credenciais do `auth/` ([CredentialStore]): a chave de API e, no login, o token de acesso, o de renovação e o de
 *    identidade de cada provedor.
 *
 * **Nunca imprime, loga nem põe valor em exceção**: este arquivo não tem uma linha de log. Se qualquer das fontes não puder ser lida a
 * exceção sobe sem o valor, e o filtro falha FECHADO (troca o texto inteiro por um aviso).
 *
 * Decifrar o cofre custa alguns milissegundos e o filtro é chamado várias vezes por volta (todo o histórico passa por ele), então a
 * leitura é guardada por [ttlMs] e jogada fora na hora em que o cofre das `Settings` muda ([SharedPreferences.OnSharedPreferenceChangeListener]).
 * Uma chave guardada há menos que isso nas credenciais do `auth/` (que não notificam) vale na chamada seguinte ao fim do prazo — e um
 * segredo que acabou de ser digitado nos Ajustes não tem como estar em texto que o app mande ao modelo.
 */
internal class AgentSecretSource(
    /** O cofre das `Settings`; aberto só na primeira leitura (abrir a `EncryptedSharedPreferences` custa o Keystore, e quem monta o agente está na thread principal). */
    vaultProvider: () -> SharedPreferences,
    private val credentials: CredentialStore,
    private val providers: List<ProviderId> = ALL_PROVIDERS,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
    private val vaultSnapshot: ((SharedPreferences) -> Collection<Any?>)? = null,
    /** Os segredos da aba Computador (senhas lembradas e material guardado em envelope, CMP-16): vêm de [com.keyflow.computador.chaves.CofreDeSegredos.valores]. */
    private val computador: () -> Collection<String> = { emptyList() },
) : () -> Collection<String> {

    private class Snapshot(val atMs: Long, val values: List<String>)

    @Volatile
    private var cached: Snapshot? = null

    // A referência forte é de propósito: o `SharedPreferences` guarda os ouvintes em referência fraca.
    private val onVaultChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> cached = null }

    private val vault: SharedPreferences by lazy {
        vaultProvider().also { it.registerOnSharedPreferenceChangeListener(onVaultChanged) }
    }

    /** Joga fora o que foi lido (a próxima chamada lê de novo). */
    fun invalidate() {
        cached = null
    }

    override fun invoke(): Collection<String> {
        val now = clockMs()
        cached?.let { if (now - it.atMs in 0 until ttlMs) return it.values }
        val values = read()
        cached = Snapshot(now, values)
        return values
    }

    private fun read(): List<String> {
        val out = LinkedHashSet<String>()
        val vaultValues = vaultSnapshot?.invoke(vault) ?: vault.all.values
        for (value in vaultValues) if (value is String && value.isNotEmpty()) out += value
        for (provider in providers) {
            // Também prova que o cofre está legível; os valores usados pelo filtro vêm apenas dos acessores tipados.
            credentials.get(provider)
            credentials.getApiKey(provider)?.let { out += it.value }
            credentials.getOAuth(provider)?.let { credential ->
                out += credential.accessToken
                credential.refreshToken?.let { out += it }
                credential.idToken?.let { out += it }
            }
        }
        for (key in com.keyflow.data.SettingsCredentialKeys.ALL) {
            credentials.getSecret(key)?.let { if (it.isNotEmpty()) out += it }
        }
        for (value in computador()) if (value.isNotEmpty()) out += value
        out.remove("")
        return out.toList()
    }

    companion object {
        const val TTL_MS = 2_000L

        /** Todos os provedores que o app conhece (o login e a chave de API de cada um moram no cofre do `auth/`). */
        val ALL_PROVIDERS = listOf(
            ProviderIds.OPENAI,
            ProviderIds.ANTHROPIC,
            ProviderIds.GOOGLE,
            ProviderIds.ELEVENLABS,
            ProviderIds.GROQ,
            ProviderIds.GROK,
        )
    }
}
