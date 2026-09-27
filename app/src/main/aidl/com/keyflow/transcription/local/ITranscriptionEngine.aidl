package com.keyflow.transcription.local;

import com.keyflow.transcription.local.IEngineCallback;
import com.keyflow.transcription.local.LocalModelConfig;

/**
 * Fronteira do processo :inference (NAT-08). oneway + callback: quem chama nunca bloqueia no binder.
 * O serviço faz uma coisa por vez, na ordem de chegada.
 */
interface ITranscriptionEngine {
    /** Carrega e mantém o modelo (NAT-05); onSuccess("") quando pronto. */
    oneway void preload(in LocalModelConfig config, IEngineCallback cb);
    /** Decodifica o .m4a em audioPath e transcreve com o modelo (carrega se preciso). */
    oneway void transcribe(in LocalModelConfig config, String audioPath, IEngineCallback cb);
}
