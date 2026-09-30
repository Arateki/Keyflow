package com.keyflow.auth

import java.net.URI

/**
 * Recebe a volta do navegador. Implementações: o loopback `http://127.0.0.1:<porta>/…` (OpenAI) hoje; esquema do app ou app
 * link para provedores futuros. Uma só requisição, só do próprio aparelho.
 */
interface RedirectReceiver {
    /** Onde o provedor deve redirecionar (vai na URL de autorização e na troca do código). */
    val redirectUri: String

    /** A URL completa que o navegador chamou. Sem resposta em [timeoutMs] → [AuthException] `TIMEOUT`. */
    suspend fun await(timeoutMs: Long): URI

    fun close()
}

/** Um jeito de se conectar a um provedor. Duas famílias — chave e OAuth — sob o mesmo contrato mínimo. */
interface AuthFlow {
    val method: AuthMethod

    /** `Unavailable` deixa o botão desabilitado com o motivo (AUTH-09). */
    val availability: Availability

    /** Revoga no provedor o que se puder; falha aqui nunca impede de limpar o que está guardado. */
    suspend fun revoke(current: Credential)
}

/** Chave de API: nenhum passo extra — a UI entrega a chave ao [AuthController]. */
interface ApiKeyFlow : AuthFlow

interface OAuthFlow : AuthFlow {
    /**
     * Sobe o receptor, gera PKCE/state/nonce e monta a URL de autorização. [forceConsent] pede ao provedor que mostre de novo
     * a tela de consentimento — o que reabilita uma permissão que o usuário recusou antes (ex.: o uso do plano na OpenAI).
     */
    suspend fun begin(forceConsent: Boolean = false): AuthStart

    /** Valida o retorno (state, nonce, assinatura), troca o código e devolve a credencial — sem gravá-la. */
    suspend fun complete(redirect: URI): Credential.OAuth

    suspend fun refresh(current: Credential.OAuth): Credential.OAuth
}

/** A URL para abrir no navegador e o receptor que espera a volta. */
data class AuthStart(val url: String, val redirect: RedirectReceiver)

/** Abre a URL no navegador do sistema. A implementação Android usa `ACTION_VIEW` (RSN-T16). */
interface BrowserLauncher {
    fun open(url: String)
}

/**
 * Tudo que o app precisa saber de um provedor. [headersFor] — o formato dos cabeçalhos é do PROVEDOR, não do módulo
 * genérico. [planUsage] diz se um login dá direito a usar o plano do usuário (a OpenAI exige um escopo; os outros, sempre).
 * [usageUrl] é a página onde o usuário vê e gerencia o uso desse plano (null = o provedor não tem uma).
 */
class ProviderAuth(
    val id: ProviderId,
    val displayName: String,
    val flows: List<AuthFlow>,
    val planUsage: (Credential.OAuth) -> Boolean = { true },
    val usageUrl: String? = null,
    val headersFor: (Credential) -> Map<String, String>,
)
