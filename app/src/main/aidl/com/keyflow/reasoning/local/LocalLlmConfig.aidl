package com.keyflow.reasoning.local;

/**
 * Tudo que o :reasoning precisa para carregar e rodar um modelo de texto (RRUN-02): ele não lê Settings, DataStore
 * nem o catálogo. Caminho absoluto dentro do armazenamento do próprio app (mesmo UID).
 */
parcelable LocalLlmConfig {
    String modelId;
    /** Arquivo do modelo (.litertlm) instalado pelo ModelStore. */
    String modelPath;
    /** Nome de com.keyflow.data.models.EngineType — hoje só "LITERT_LM". */
    String engineType;
    /** Descarrega o modelo depois deste tempo sem uso; 0 = mantém carregado. */
    long idleUnloadMs;
    /** Memória livre estimada para carregar, em MB — base da guarda de RAM (RRUN-04). */
    int ramEstimateMb;
    /** Identidade da versão do arquivo (SHA-256 do catálogo): trocar o arquivo sob o mesmo [modelId] descarrega o motor residente. */
    String version;
    /** Nome de com.keyflow.core.ComputeBackend onde carregar: "CPU" (padrão; vazio ou desconhecido também) ou "GPU". Troca de backend recarrega o modelo. */
    String backend;
}
