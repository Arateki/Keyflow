package com.keyflow.auth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * O que a tela pede aos provedores — conectar, guardar uma chave, cancelar, desconectar — e o único [ConnectionState] que ela
 * observa (AUTH-03/07). Não sabe de nenhum provedor concreto: tudo vem do [registry].
 */
class AuthController(
    private val registry: List<ProviderAuth>,
    private val store: CredentialStore,
    private val browser: BrowserLauncher,
    private val scope: CoroutineScope,
    @Suppress("unused") private val clock: () -> Long,
) {
    private val connecting = MutableStateFlow<Set<ProviderId>>(emptySet())
    private val errors = MutableStateFlow<Map<ProviderId, AuthErrorKind>>(emptyMap())
    private val jobs = ConcurrentHashMap<ProviderId, Job>()
    private val receivers = ConcurrentHashMap<ProviderId, RedirectReceiver>()

    /**
     * Qual tentativa de login é a atual. Quem foi cancelado por um `connect()` novo (ou por [cancel]) termina DEPOIS, e a limpeza
     * dele não pode apagar o receptor nem o "conectando" da tentativa nova — só a atual limpa.
     */
    private val attempts = ConcurrentHashMap<ProviderId, AtomicLong>()

    private fun newAttempt(provider: ProviderId): Long = attempts.getOrPut(provider) { AtomicLong() }.incrementAndGet()
    private fun isCurrent(provider: ProviderId, attempt: Long) = attempts[provider]?.get() == attempt

    fun state(provider: ProviderId): Flow<ConnectionState> =
        combine(store.changes(provider), connecting, errors) { credential, waiting, failed ->
            when {
                provider in waiting -> ConnectionState.Connecting
                failed[provider] != null -> ConnectionState.Error(failed.getValue(provider))
                else -> stateOf(provider, credential)
            }
        }.distinctUntilChanged()

    private fun stateOf(provider: ProviderId, credential: Credential?): ConnectionState = when (credential) {
        null -> ConnectionState.Disconnected
        is Credential.ApiKey -> ConnectionState.Connected(account = null, method = AuthMethod.API_KEY)
        is Credential.OAuth ->
            if (credential.isSkeleton) ConnectionState.NeedsReconnect
            else ConnectionState.Connected(credential.account, AuthMethod.OAUTH, entry(provider).planUsage(credential))
    }

    /** Login por navegador. Chave de API não passa por aqui — ver [saveApiKey]. [forceConsent]: ver [OAuthFlow.begin]. */
    fun connect(provider: ProviderId, method: AuthMethod, forceConsent: Boolean = false) {
        val flow = entry(provider).flows.firstOrNull { it.method == method } as? OAuthFlow ?: return
        errors.update { it - provider }
        if (flow.availability is Availability.Unavailable) {
            errors.update { it + (provider to AuthErrorKind.DENIED) }
            return
        }
        jobs.remove(provider)?.cancel()
        val attempt = newAttempt(provider)
        connecting.update { it + provider }
        jobs[provider] = scope.launch {
            var start: AuthStart? = null
            try {
                start = flow.begin(forceConsent)
                receivers[provider] = start.redirect
                browser.open(start.url)
                val redirect = start.redirect.await(REDIRECT_TIMEOUT_MS)
                store.put(provider, flow.complete(redirect))
            } catch (e: AuthException) {
                errors.update { it + (provider to e.kind) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.update { it + (provider to AuthErrorKind.UNKNOWN) }
            } finally {
                start?.redirect?.close()
                if (isCurrent(provider, attempt)) {
                    receivers.remove(provider)
                    connecting.update { it - provider }
                }
            }
        }
    }

    /**
     * A chave é guardada só com ASCII imprimível: o colar traz espaços, quebras de linha e caracteres de largura zero que nenhuma
     * chave tem — e que o OkHttp recusa no cabeçalho, derrubando a chamada. Em branco (ou só lixo invisível) = apagar.
     */
    fun saveApiKey(provider: ProviderId, key: String) {
        errors.update { it - provider }
        val trimmed = key.filter { it.code in 0x21..0x7E }
        if (trimmed.isEmpty()) store.clearApiKey(provider) else store.put(provider, Credential.ApiKey(trimmed))
    }

    /** Só a máscara chega à UI; o valor decifrado fica dentro do auth/ e não entra no estado Compose. */
    fun apiKey(provider: ProviderId): String? = CredentialMask.of((store.get(provider) as? Credential.ApiKey)?.value)

    /** A leitura do cofre acontece no IO; a UI recebe apenas a máscara e pode observar mudanças. */
    fun apiKeyFlow(provider: ProviderId): Flow<String?> = store.changes(provider)
        .map { (it as? Credential.ApiKey)?.value?.let(CredentialMask::of) }
        .distinctUntilChanged()
        .flowOn(kotlinx.coroutines.Dispatchers.IO)

    /**
     * Um aviso que se mostra UMA vez por provedor e chave (ex.: "você está usando o seu plano"). Devolve `true` na 1ª chamada e
     * `false` nas seguintes; o marcador é do `meta` do cofre, então sobrevive ao Desconectar.
     */
    fun consumeNotice(provider: ProviderId, key: String): Boolean {
        val marker = "notice.$key"
        if (store.meta(provider, marker) != null) return false
        store.putMeta(provider, marker, "1")
        return true
    }

    /** Desiste do login em andamento: fecha o receptor e volta a "desconectado". */
    fun cancel(provider: ProviderId) {
        newAttempt(provider) // a limpeza de quem foi cancelado não vale mais: esta função já limpou tudo
        jobs.remove(provider)?.cancel()
        receivers.remove(provider)?.close()
        connecting.update { it - provider }
        errors.update { it - provider }
    }

    /** Revoga no provedor (o que der) e apaga o que está guardado — a falha da revogação nunca impede de apagar. */
    fun disconnect(provider: ProviderId) {
        cancel(provider)
        val credential = store.get(provider)
        scope.launch {
            if (credential != null) {
                val method = if (credential is Credential.OAuth) AuthMethod.OAUTH else AuthMethod.API_KEY
                runCatching { entry(provider).flows.firstOrNull { it.method == method }?.revoke(credential) }
            }
            store.clearOAuth(provider)
            errors.update { it - provider }
        }
    }

    private fun entry(provider: ProviderId): ProviderAuth =
        requireNotNull(registry.firstOrNull { it.id == provider }) { "provedor não registrado: ${provider.value}" }

    companion object {
        /** Quanto esperar o navegador voltar antes de desistir. */
        const val REDIRECT_TIMEOUT_MS = 5 * 60_000L
    }
}
