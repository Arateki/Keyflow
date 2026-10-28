package com.keyflow.reasoning.local;

/**
 * Resposta de uma geração no :reasoning, em fluxo: zero ou mais onDelta e, no fim, onDone OU onError.
 * `kind` é o código de ReasoningErrorKind.
 */
interface IReasoningCallback {
    oneway void onDelta(String text);
    oneway void onDone();
    oneway void onError(int kind, String message);
    /**
     * A carga no backend pedido (hoje só "GPU") falhou e o modelo seguiu na CPU: `failed` é o nome do ComputeBackend que falhou, `reason` o
     * motivo (para o log). Vem antes de onDelta/onDone da mesma geração, que continua normalmente.
     */
    oneway void onBackendFallback(String failed, String reason);
}
