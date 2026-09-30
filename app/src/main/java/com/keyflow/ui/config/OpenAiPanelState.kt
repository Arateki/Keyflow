package com.keyflow.ui.config

import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthFlow
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.Availability
import com.keyflow.auth.ConnectionState
import com.keyflow.auth.UnavailableAuthReason
import com.keyflow.i18n.tr

enum class OpenAiStatus { NOT_CONNECTED, CONNECTING, CONNECTED_LOGIN, CONNECTED_KEY, NEEDS_RECONNECT, ERROR }

/**
 * O botão do login. [disabledReason] só vem quando o registro diz que o fluxo está indisponível (AUTH-09); [again] troca o
 * rótulo para "entrar de novo" (a sessão expirou, ou o login voltou sem o uso do plano).
 */
data class LoginButton(val enabled: Boolean, val disabledReason: UnavailableAuthReason?, val again: Boolean)

/**
 * O que o painel da OpenAI mostra — decidido aqui, numa função pura, para a tela só desenhar (RSC-03, RCL-07, AUTH-03/07).
 * [login] é nulo quando o registro não oferece login, ou quando não há o que fazer com ele; [showKeyField] segue o registro.
 */
data class OpenAiPanelState(
    val status: OpenAiStatus,
    val account: String?,
    val planInUse: Boolean,
    val planMissing: Boolean,
    val login: LoginButton?,
    val showKeyField: Boolean,
    val showDisconnect: Boolean,
    val showCancel: Boolean,
    val error: AuthErrorKind?,
)

/** Os controles vêm dos [flows] do registro — nunca de um "se for a OpenAI" (AUTH-03). */
fun openAiPanelState(connection: ConnectionState, flows: List<AuthFlow>): OpenAiPanelState {
    val oauth = flows.firstOrNull { it.method == AuthMethod.OAUTH }
    val hasKeyFlow = flows.any { it.method == AuthMethod.API_KEY }
    val loginAvailable = oauth?.availability == Availability.Available
    val reason = (oauth?.availability as? Availability.Unavailable)?.reason

    fun login(enabled: Boolean = loginAvailable, again: Boolean = false): LoginButton? =
        oauth?.let { LoginButton(enabled = enabled, disabledReason = reason, again = again) }

    return when (connection) {
        ConnectionState.Disconnected -> OpenAiPanelState(
            OpenAiStatus.NOT_CONNECTED, null, planInUse = false, planMissing = false,
            login = login(), showKeyField = hasKeyFlow, showDisconnect = false, showCancel = false, error = null,
        )
        ConnectionState.Connecting -> OpenAiPanelState(
            OpenAiStatus.CONNECTING, null, planInUse = false, planMissing = false,
            login = login(enabled = false), showKeyField = false, showDisconnect = false, showCancel = true, error = null,
        )
        is ConnectionState.Connected -> if (connection.method == AuthMethod.OAUTH) {
            OpenAiPanelState(
                OpenAiStatus.CONNECTED_LOGIN, connection.account, planInUse = connection.planUsage, planMissing = !connection.planUsage,
                // Sem o uso do plano: reautorizar (`prompt=consent`) ou trocar por uma chave. Com o plano, nada a oferecer.
                login = if (connection.planUsage) null else login(again = true),
                showKeyField = hasKeyFlow && !connection.planUsage,
                showDisconnect = true, showCancel = false, error = null,
            )
        } else {
            OpenAiPanelState(
                OpenAiStatus.CONNECTED_KEY, null, planInUse = false, planMissing = false,
                login = null, showKeyField = hasKeyFlow, showDisconnect = true, showCancel = false, error = null,
            )
        }
        ConnectionState.NeedsReconnect -> OpenAiPanelState(
            OpenAiStatus.NEEDS_RECONNECT, null, planInUse = false, planMissing = false,
            login = login(again = true), showKeyField = hasKeyFlow, showDisconnect = true, showCancel = false, error = null,
        )
        is ConnectionState.Error -> OpenAiPanelState(
            OpenAiStatus.ERROR, null, planInUse = false, planMissing = false,
            // Um login que falhou por cima de uma conexão que existia deixa a credencial no cofre: Desconectar (que também limpa o erro)
            // é a saída — sem ela, entrar com outra conta girava em círculo.
            login = login(), showKeyField = hasKeyFlow, showDisconnect = true, showCancel = false, error = connection.kind,
        )
    }
}

/** Por que a conexão falhou — em palavras, sem token nem mensagem crua do provedor. */
fun openAiErrorMessage(kind: AuthErrorKind): String = when (kind) {
    AuthErrorKind.NETWORK -> tr("Sem conexão com a OpenAI — confira a rede e tente de novo.")
    AuthErrorKind.DENIED -> tr("O acesso foi negado — tente entrar de novo. Para trocar de conta, toque em Desconectar antes.")
    AuthErrorKind.INVALID_RESPONSE -> tr("A OpenAI respondeu de um jeito inesperado — tente de novo mais tarde.")
    AuthErrorKind.TIMEOUT -> tr("O navegador não voltou a tempo — tente entrar de novo.")
    AuthErrorKind.UNKNOWN -> tr("Não foi possível entrar — tente de novo.")
    AuthErrorKind.INVALID_GRANT -> tr("A OpenAI recusou a autorização — entre de novo.")
    AuthErrorKind.NEEDS_RECONNECT -> tr("A sessão expirou — entre de novo.")
    AuthErrorKind.NOT_CONNECTED -> tr("Nenhuma conta conectada.")
}

/** Por que o botão do login está desligado. */
fun openAiUnavailableMessage(reason: UnavailableAuthReason): String = when (reason) {
    UnavailableAuthReason.PROVIDER_DISABLED -> tr("Entrar com o ChatGPT ainda não está liberado nesta versão — use uma chave de API.")
}
