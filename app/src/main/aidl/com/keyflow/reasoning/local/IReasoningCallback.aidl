package com.keyflow.reasoning.local;

/**
 * Resposta de uma geração no :reasoning, em fluxo: zero ou mais onDelta e, no fim, onDone OU onError.
 * `kind` é o código de ReasoningErrorKind.
 */
interface IReasoningCallback {
    oneway void onDelta(String text);
    oneway void onDone();
    oneway void onError(int kind, String message);
}
