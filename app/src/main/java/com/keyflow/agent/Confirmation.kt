package com.keyflow.agent

/** A resposta do usuário ao painel de confirmação (AUI-02). */
enum class ConfirmationDecision {
    ALLOW,

    /** "Permitir sempre esta ferramenta": só vale para `WRITE` no chat ([ToolPolicy.grantAlways]); fora disso conta como [ALLOW]. */
    ALLOW_ALWAYS,
    DENY,
}

/**
 * O que o painel mostra (TLS-02). Tudo aqui é texto gerado PELO APP — [summary] vem de [AgentTool.describe] com os argumentos já
 * validados, nunca de uma frase do modelo sobre a própria ação.
 */
class ConfirmationRequest(
    val callId: String,
    val toolName: String,
    val risk: ToolRisk,
    val summary: String,
    val preview: ToolPreview?,
    /** O painel pode mostrar "Permitir sempre esta ferramenta". */
    val canAlwaysAllow: Boolean,
    val origin: ToolOrigin,
)

/**
 * Quem pergunta ao usuário. A UI implementa (fase 1B); o núcleo só conhece este contrato. Suspende até a resposta; cancelar a
 * coleta cancela a espera. Uma exceção daqui conta como NEGAÇÃO (o [ToolExecutor] falha fechado).
 */
interface Confirmation {
    suspend fun request(request: ConfirmationRequest): ConfirmationDecision
}

/** O padrão seguro quando não há quem pergunte (nenhuma tela aberta): nega tudo. */
object DenyAllConfirmation : Confirmation {
    override suspend fun request(request: ConfirmationRequest): ConfirmationDecision = ConfirmationDecision.DENY
}
