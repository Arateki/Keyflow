package com.keyflow.auth

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Quem entrega os cabeçalhos de credencial a um cliente de API (AUTH-04). O cliente pede [headers] e nunca vê a renovação
 * nem o token cru fora do cabeçalho.
 */
interface AuthorizationProvider {
    /**
     * Cabeçalhos prontos, renovando o token antes de expirar. Falha com [AuthException]: `NOT_CONNECTED` (nada guardado),
     * `NEEDS_RECONNECT` (a renovação deixou de valer — só o login de novo resolve) ou o tipo temporário (`NETWORK`…).
     */
    suspend fun headers(provider: ProviderId): Map<String, String>

    /**
     * O servidor recusou a credencial como inválida para SEMPRE (ex.: 401 `subscription_sharing_invalid_user`): um login vira
     * "esqueleto" e a conexão passa a pedir login de novo; uma chave de API não é tocada (corrigi-la é do usuário). Sem efeito
     * quando não há nada guardado.
     */
    fun invalidate(provider: ProviderId) {}
}

/**
 * Renovação segura (AUTH-04/AUTH-11, Review Focus 2). O refresh token do login é **rotativo**: cada renovação devolve outro e
 * invalida o anterior — por isso (1) um `Mutex` por provedor deixa passar uma renovação por vez, relendo o cofre depois do
 * lock (quem esperou usa o token que o outro já renovou); (2) a credencial nova é GRAVADA antes de ser usada; e (3) falha
 * temporária (rede, 5xx) nunca apaga nada — o token ainda válido segue valendo.
 */
class DefaultAuthorizationProvider(
    private val registry: List<ProviderAuth>,
    private val store: CredentialStore,
    private val clock: () -> Long,
    private val marginMs: Long = 60_000,
) : AuthorizationProvider {

    private val locks = ConcurrentHashMap<ProviderId, Mutex>()

    override fun invalidate(provider: ProviderId) {
        val current = store.get(provider) as? Credential.OAuth ?: return
        if (!current.isSkeleton) store.put(provider, skeletonOf(current))
    }

    /** Sem tokens; o REGISTRO (client_id, id_token, conta) fica, e o próximo login o reaproveita. */
    private fun skeletonOf(current: Credential.OAuth) =
        current.copy(accessToken = "", refreshToken = null, expiresAtMs = 0, earliestRefreshAtMs = null, scopes = emptySet())

    override suspend fun headers(provider: ProviderId): Map<String, String> {
        val entry = requireNotNull(registry.firstOrNull { it.id == provider }) { "provedor não registrado: ${provider.value}" }
        var credential = store.get(provider) ?: throw AuthException(AuthErrorKind.NOT_CONNECTED, "sem credencial guardada")
        if (credential is Credential.OAuth && needsRefresh(credential)) {
            credential = locks.getOrPut(provider) { Mutex() }.withLock {
                val current = store.get(provider) ?: throw AuthException(AuthErrorKind.NOT_CONNECTED, "a credencial sumiu")
                // A renovação TROCA o refresh token no servidor: se quem chamou for cancelado no meio, o novo ainda precisa ser
                // gravado — senão o cofre fica com o velho, que a próxima renovação vê como reusado e força um login novo.
                if (current is Credential.OAuth && needsRefresh(current)) withContext(NonCancellable) { refresh(entry, current) } else current
            }
        }
        return entry.headersFor(credential)
    }

    /** Perto de expirar, sem passar antes do `earliest_refresh_at` — a não ser que o token já tenha expirado. */
    private fun needsRefresh(credential: Credential.OAuth): Boolean {
        val now = clock()
        if (credential.isSkeleton) return true // sem token nenhum: cai em NEEDS_RECONNECT no refresh
        val expired = now >= credential.expiresAtMs
        val nearExpiry = now >= credential.expiresAtMs - marginMs
        val tooEarly = credential.earliestRefreshAtMs?.let { now < it } ?: false
        return expired || (nearExpiry && !tooEarly)
    }

    private suspend fun refresh(entry: ProviderAuth, current: Credential.OAuth): Credential {
        val flow = entry.flows.filterIsInstance<OAuthFlow>().firstOrNull()
        if (current.refreshToken == null || flow == null) throw AuthException(AuthErrorKind.NEEDS_RECONNECT, "sem como renovar")
        val renewed = try {
            flow.refresh(current)
        } catch (e: AuthException) {
            when (e.kind) {
                // O refresh deixou de valer: os tokens somem, o REGISTRO (client_id, id_token) fica, e o próximo login o reaproveita.
                AuthErrorKind.INVALID_GRANT -> {
                    store.put(entry.id, skeletonOf(current))
                    throw AuthException(AuthErrorKind.NEEDS_RECONNECT, "a renovação não vale mais", e)
                }
                // Falha temporária: nada é apagado; se o token ainda vale, ele segue valendo.
                else -> if (clock() < current.expiresAtMs) return current else throw e
            }
        }
        store.put(entry.id, renewed) // gravado ANTES de ser usado; se falhar, a exceção sobe e o velho segue lá
        return renewed
    }
}
