package com.keyflow.auth

/** Um provedor de IA. O id é estável e é a chave de tudo que se guarda dele. */
@JvmInline
value class ProviderId(val value: String)

/**
 * Os provedores conhecidos. Moram no NÚCLEO de propósito: a UI e os motores precisam do id da OpenAI e não podem importar
 * nada de `auth/providers/` (o `AuthIsolationTest` confere).
 */
object ProviderIds {
    val OPENAI = ProviderId("openai")
    val ANTHROPIC = ProviderId("anthropic")
    val GOOGLE = ProviderId("google")
    val ELEVENLABS = ProviderId("elevenlabs")
    val GROQ = ProviderId("groq")
    val GROK = ProviderId("grok")
}

/** Como se prova quem é: com uma chave colada, ou com um login no navegador. */
enum class AuthMethod { API_KEY, OAUTH }

enum class UnavailableAuthReason { PROVIDER_DISABLED }

/** Por que uma autenticação falhou — o que a UI traduz; nunca carrega token nem mensagem crua do provedor. */
enum class AuthErrorKind { NETWORK, DENIED, INVALID_RESPONSE, TIMEOUT, UNKNOWN, INVALID_GRANT, NEEDS_RECONNECT, NOT_CONNECTED }

/** Falha de autenticação com tipo. A mensagem é para o log de quem desenvolve: sem token, sem dado pessoal. */
class AuthException(val kind: AuthErrorKind, message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface Availability {
    data object Available : Availability
    data class Unavailable(val reason: UnavailableAuthReason) : Availability
}

/** O que se guarda para falar com um provedor. Nunca vai para `Settings`, log, estado de UI nem backup (AUTH-02). */
sealed interface Credential {
    data class ApiKey(val value: String) : Credential

    /**
     * Login. [clientId] é o EMITIDO no registro (não o provisório) e fica com [subject] — a identidade verificada — para
     * duas contas nunca se misturarem (AUTH-10). Com [accessToken] vazio e [refreshToken] nulo é o "esqueleto" que sobra
     * quando a renovação deixa de valer: os tokens somem, o registro fica, e o próximo login reautoriza sobre ele.
     */
    data class OAuth(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMs: Long,
        val earliestRefreshAtMs: Long?,
        val scopes: Set<String>,
        val idToken: String?,
        val clientId: String?,
        val subject: String?,
        val account: String?,
    ) : Credential {
        /** Sem tokens, só o registro (ver acima). */
        val isSkeleton: Boolean get() = accessToken.isEmpty() && refreshToken == null
    }
}

/** A UI só recebe esta forma — nunca o texto da credencial que o cofre decifrou. */
object CredentialMask {
    fun of(value: String?): String? {
        val secret = value?.takeIf { it.isNotEmpty() } ?: return null
        return "••••${secret.takeLast(4)}"
    }
}

/** O único estado de conexão que a UI observa (AUTH-07). */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState

    /** [planUsage] falso = conectado, mas sem permissão de usar o plano do usuário (RCL-07). */
    data class Connected(val account: String?, val method: AuthMethod, val planUsage: Boolean = true) : ConnectionState
    data object NeedsReconnect : ConnectionState
    data class Error(val kind: AuthErrorKind) : ConnectionState
}
