package com.keyflow.transcription.local;

/** Resposta de uma chamada ao :inference. `kind` é o código de EngineErrorKind. */
interface IEngineCallback {
    oneway void onSuccess(String text);
    oneway void onError(int kind, String message);
}
