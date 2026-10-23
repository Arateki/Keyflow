package com.keyflow.auth

import android.content.Context
import com.keyflow.data.models.KeyFlowHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * O grafo de autenticação do processo do app — UM só: cofre, registro, quem entrega os cabeçalhos e o controlador da tela.
 * Vive no `Application`, não numa Activity, por três motivos: a renovação do token é serializada por um `Mutex` que precisa ser
 * o MESMO para todo mundo (duas instâncias renovariam ao mesmo tempo e a segunda usaria um refresh já trocado); um login em
 * andamento não morre com a Activity; e um consumidor futuro (teclado, orbe) usa o mesmo cofre sem montar outro.
 *
 * Tudo é preguiçoso: nada é criado (nem o Keystore aberto) até alguém pedir.
 */
class AuthServices(
    context: Context,
    val http: OkHttpClient = KeyFlowHttpClient.base(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext

    // Fora da main: o controlador faz rede, abre o receptor de loopback e lê o cofre.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val store: CredentialStore by lazy { SecureCredentialStore(appContext) }
    val registry: List<ProviderAuth> by lazy { AuthProviders.create(AuthDependencies(store, clock, http)) }
    val authorization: AuthorizationProvider by lazy { DefaultAuthorizationProvider(registry, store, clock) }
    val browser: BrowserLauncher by lazy { AndroidBrowserLauncher(appContext) }
    val controller: AuthController by lazy { AuthController(registry, store, browser, scope, clock) }

}
