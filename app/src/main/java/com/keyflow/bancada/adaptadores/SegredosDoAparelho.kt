package com.keyflow.bancada.adaptadores

import android.content.SharedPreferences
import com.keyflow.auth.CredentialStore
import com.keyflow.auth.ProviderId
import com.keyflow.auth.ProviderIds
import com.keyflow.data.SettingsCredentialKeys

/**
 * Os valores secretos de agora, para o [com.keyflow.bancada.redacao.RedatorDeSegredos] trocar por valor (BON-03): o cofre cifrado das `Settings`
 * (todos os valores, não uma lista) e o cofre de credenciais do `auth/` (chave de API e, no login, os tokens de cada provedor). Só leitura.
 * É o equivalente do `AgentSecretSource` do agente, sem importá-lo: o agente não vai no APK de release e a bancada sim.
 *
 * Decifrar o cofre custa alguns milissegundos e o redator é chamado a cada caso gravado, então a leitura fica guardada por [ttlMs] e é jogada fora
 * quando o cofre das `Settings` muda. **Não lê nem imprime valor em log**: se uma fonte não puder ser lida a exceção sobe sem o valor e o redator falha fechado.
 */
internal class SegredosDoAparelho(
    vaultProvider: () -> SharedPreferences,
    private val credentials: CredentialStore,
    private val providers: List<ProviderId> = TODOS_OS_PROVEDORES,
    private val relogio: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
    private val valoresDoCofre: ((SharedPreferences) -> Collection<Any?>)? = null,
) : () -> Collection<String> {

    private class Leitura(val em: Long, val valores: List<String>)

    @Volatile
    private var guardada: Leitura? = null

    // Referência forte de propósito: o SharedPreferences guarda os ouvintes em referência fraca.
    private val aoMudarOCofre = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> guardada = null }

    private val cofre: SharedPreferences by lazy { vaultProvider().also { it.registerOnSharedPreferenceChangeListener(aoMudarOCofre) } }

    override fun invoke(): Collection<String> {
        val agora = relogio()
        guardada?.let { if (agora - it.em in 0 until ttlMs) return it.valores }
        val valores = ler()
        guardada = Leitura(agora, valores)
        return valores
    }

    private fun ler(): List<String> {
        val saida = LinkedHashSet<String>()
        for (valor in valoresDoCofre?.invoke(cofre) ?: cofre.all.values) if (valor is String && valor.isNotEmpty()) saida += valor
        for (provedor in providers) {
            credentials.get(provedor) // também prova que o cofre está legível
            credentials.getApiKey(provedor)?.let { saida += it.value }
            credentials.getOAuth(provedor)?.let { credencial ->
                saida += credencial.accessToken
                credencial.refreshToken?.let { saida += it }
                credencial.idToken?.let { saida += it }
            }
        }
        for (chave in SettingsCredentialKeys.ALL) credentials.getSecret(chave)?.let { if (it.isNotEmpty()) saida += it }
        saida.remove("")
        return saida.toList()
    }

    companion object {
        const val TTL_MS = 2_000L

        val TODOS_OS_PROVEDORES = listOf(
            ProviderIds.OPENAI, ProviderIds.ANTHROPIC, ProviderIds.GOOGLE, ProviderIds.ELEVENLABS, ProviderIds.GROQ, ProviderIds.GROK,
        )
    }
}
