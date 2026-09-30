package com.keyflow.auth

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    override suspend fun headers(provider: ProviderId): Map<String, String> {
        val entry = requireNotNull(registry.firstOrNull { it.id == provider }) { "provedor não registrado: ${provider.value}" }
        var credential = store.get(provider) ?: throw AuthException(AuthErrorKind.NOT_CONNECTED, "sem credencial guardada")
        if (credential is Credential.OAuth && needsRefresh(credential)) {
            credential = locks.getOrPut(provider) { Mutex() }.withLock {
                val current = store.get(provider) ?: throw AuthException(AuthErrorKind.NOT_CONNECTED, "a credencial sumiu")
                if (current is Credential.OAuth && needsRefresh(current)) refresh(entry, current) else current
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
                    store.put(entry.id, current.copy(accessToken = "", refreshToken = null, expiresAtMs = 0, earliestRefreshAtMs = null, scopes = emptySet()))
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
