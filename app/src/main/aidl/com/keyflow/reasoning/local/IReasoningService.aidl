package com.keyflow.reasoning.local;

import com.keyflow.reasoning.local.IReasoningCallback;
import com.keyflow.reasoning.local.LocalLlmConfig;
import com.keyflow.reasoning.local.LocalGenOptions;

/**
 * Fronteira do processo :reasoning (RRUN-02). oneway + callback: quem chama nunca bloqueia no binder.
 * O serviço faz uma geração por vez, na ordem de chegada.
 */
interface IReasoningService {
    /** Carrega o modelo se preciso e gera; `system` pode ser nulo/vazio. Responde em fluxo pelo callback. */
    oneway void generate(in LocalLlmConfig config, @nullable String system, String user, in LocalGenOptions options, IReasoningCallback cb);
    /** Pré-carga explícita: onDone apenas depois do aquecimento; sem onDelta. */
    oneway void preload(in LocalLlmConfig config, IReasoningCallback cb);
    /** Interrompe a geração em curso; depois disso o serviço não fala mais com o callback dela. */
    oneway void cancel();
    oneway void unload();
    oneway void restart();
}
