package com.keyflow.ui.config

import com.keyflow.auth.AuthController
import com.keyflow.auth.AuthFlow
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.BrowserLauncher
import com.keyflow.auth.ConnectionState
import com.keyflow.auth.Credential
import com.keyflow.auth.CredentialMask
import com.keyflow.auth.CredentialStore
import com.keyflow.auth.ProviderAuth
import com.keyflow.auth.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** Um modelo que a conta pode usar: o [id] vai na chamada, o [displayName] vai na tela. */
data class AccountModel(val id: String, val displayName: String)

/**
 * O que o painel de um provedor de nuvem pede — conectar, guardar uma chave, cancelar, desconectar — e o que ele observa: só o
 * [ConnectionState] (AUTH-07). É a única porta entre a tela e o módulo `auth/`: nada de token, credencial nem `Settings`.
 * Os [flows] vêm do registro, e a tela monta os botões a partir deles (AUTH-03).
 */
interface AuthActions {
    val flows: List<AuthFlow>

    /** Onde o usuário vê e gerencia o uso do plano (null = o provedor não tem). */
    val usageUrl: String?

    fun state(): Flow<ConnectionState>
    fun connect(method: AuthMethod, forceConsent: Boolean = false)
    fun cancel()
    fun disconnect()
    fun saveApiKey(key: String)

    /** A chave guardada, só para preencher o campo mascarado; nulo se a conexão não é por chave. */
    fun apiKey(): String?

    /** A máscara observável; a leitura do cofre fica fora da thread da UI. */
    fun apiKeyFlow(): Flow<String?> = flowOf(apiKey())

    /** `true` na 1ª vez que o aviso [key] é pedido, `false` depois (ex.: o modal do 1º login com o plano). */
    suspend fun consumeNotice(key: String): Boolean
    fun openUrl(url: String)

    /** Pode falar com a rede: chamar fora da main thread. Falha = lista vazia. */
    suspend fun listModels(): List<AccountModel>

    /** Sem provedor (galeria, testes de outras abas): não há nada para conectar. */
    object None : AuthActions {
        override val flows: List<AuthFlow> = emptyList()
        override val usageUrl: String? = null
        override fun state(): Flow<ConnectionState> = flowOf(ConnectionState.Disconnected)
        override fun connect(method: AuthMethod, forceConsent: Boolean) = Unit
        override fun cancel() = Unit
        override fun disconnect() = Unit
        override fun saveApiKey(key: String) = Unit
        override fun apiKey(): String? = null
        override fun apiKeyFlow(): Flow<String?> = flowOf(null)
        override suspend fun consumeNotice(key: String): Boolean = false
        override fun openUrl(url: String) = Unit
        override suspend fun listModels(): List<AccountModel> = emptyList()
    }
}

/** Liga a tela ao [AuthController] de UM provedor do registro. [models] é o que lista os modelos daquele provedor. */
class ControllerAuthActions(
    private val provider: ProviderAuth,
    private val controller: AuthController,
    private val browser: BrowserLauncher,
    private val models: suspend () -> List<AccountModel>,
) : AuthActions {
    override val flows: List<AuthFlow> get() = provider.flows
    override val usageUrl: String? get() = provider.usageUrl
    // O cofre decifra a cada leitura (Keystore): fora da main thread.
    override fun state(): Flow<ConnectionState> = controller.state(provider.id).flowOn(Dispatchers.IO)
    override fun connect(method: AuthMethod, forceConsent: Boolean) = controller.connect(provider.id, method, forceConsent)
    override fun cancel() = controller.cancel(provider.id)
    override fun disconnect() = controller.disconnect(provider.id)
    override fun saveApiKey(key: String) = controller.saveApiKey(provider.id, key)
    override fun apiKey(): String? = controller.apiKey(provider.id)
    override fun apiKeyFlow(): Flow<String?> = controller.apiKeyFlow(provider.id)
    override suspend fun consumeNotice(key: String): Boolean = controller.consumeNotice(provider.id, key)
    override fun openUrl(url: String) {
        runCatching { browser.open(url) } // sem navegador, o toque não faz nada — e não derruba a tela
    }
    override suspend fun listModels(): List<AccountModel> = models()
}

/** Ação de chave direta para provedores sem login OAuth (por exemplo Groq e ElevenLabs). */
class DirectApiKeyActions(
    private val store: CredentialStore,
    private val provider: ProviderId,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : AuthActions {
    override val flows: List<AuthFlow> = emptyList()
    override val usageUrl: String? = null

    override fun state(): Flow<ConnectionState> = store.changes(provider)
        .map { credential ->
            if (credential is Credential.ApiKey) ConnectionState.Connected(null, AuthMethod.API_KEY)
            else ConnectionState.Disconnected
        }
        .flowOn(Dispatchers.IO)

    override fun connect(method: AuthMethod, forceConsent: Boolean) = Unit
    override fun cancel() = Unit
    override fun disconnect() { scope.launch(Dispatchers.IO) { store.clearApiKey(provider) } }

    override fun saveApiKey(key: String) {
        val sanitized = key.filter { it.code in 0x21..0x7E }
        scope.launch(Dispatchers.IO) {
            if (sanitized.isEmpty()) store.clearApiKey(provider)
            else store.put(provider, Credential.ApiKey(sanitized))
        }
    }

    override fun apiKey(): String? = CredentialMask.of(store.getApiKey(provider)?.value)

    override fun apiKeyFlow(): Flow<String?> = store.changes(provider)
        .map { store.getApiKey(provider)?.value?.let(CredentialMask::of) }
        .flowOn(Dispatchers.IO)

    override suspend fun consumeNotice(key: String): Boolean = false
    override fun openUrl(url: String) = Unit
    override suspend fun listModels(): List<AccountModel> = emptyList()
}
