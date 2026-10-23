package com.keyflow.ui.gallery

import com.keyflow.auth.ApiKeyFlow
import com.keyflow.auth.AuthFlow
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.AuthStart
import com.keyflow.auth.Availability
import com.keyflow.auth.ConnectionState
import com.keyflow.auth.Credential
import com.keyflow.auth.OAuthFlow
import com.keyflow.ui.config.AccountModel
import com.keyflow.ui.config.AuthActions
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * O provedor de nuvem da galeria: um [ConnectionState] fixo e uma lista de modelos fixa, sem rede nem cofre — os prints do painel
 * da OpenAI ficam idênticos entre execuções. Os fluxos são os dois que o registro real oferece (chave e login).
 */
class GalleryAuth(
    private val connection: ConnectionState,
    private val models: List<AccountModel> = emptyList(),
    private val storedKey: String? = null,
) : AuthActions {

    private object KeyFlow : ApiKeyFlow {
        override val method = AuthMethod.API_KEY
        override val availability: Availability = Availability.Available
        override suspend fun revoke(current: Credential) = Unit
    }

    private object LoginFlow : OAuthFlow {
        override val method = AuthMethod.OAUTH
        override val availability: Availability = Availability.Available
        override suspend fun begin(forceConsent: Boolean): AuthStart = error("galeria")
        override suspend fun complete(redirect: URI): Credential.OAuth = error("galeria")
        override suspend fun refresh(current: Credential.OAuth): Credential.OAuth = error("galeria")
        override suspend fun revoke(current: Credential) = Unit
    }

    override val flows: List<AuthFlow> = listOf(KeyFlow, LoginFlow)
    override val usageUrl: String = "https://exemplo.test/uso"
    override fun state(): Flow<ConnectionState> = flowOf(connection)
    override fun connect(method: AuthMethod, forceConsent: Boolean) = Unit
    override fun cancel() = Unit
    override fun disconnect() = Unit
    override fun saveApiKey(key: String) = Unit
    override fun apiKey(): String? = storedKey

    /** O aviso do 1º login já foi visto: o print mostra a tela, não o modal por cima. */
    override suspend fun consumeNotice(key: String): Boolean = false
    override fun openUrl(url: String) = Unit
    override suspend fun listModels(): List<AccountModel> = models
}
