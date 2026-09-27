package com.keyflow.transcription.local;

/**
 * Tudo que o :inference precisa para carregar um modelo (NAT-08): ele não lê Settings nem o catálogo.
 * Caminhos absolutos dentro do armazenamento do próprio app (mesmo UID).
 */
parcelable LocalModelConfig {
    String modelId;
    /** Nome de com.keyflow.data.models.EngineType: "WHISPER" ou "TRANSDUCER". */
    String engineType;
    String encoderPath;
    String decoderPath;
    /** Só TRANSDUCER. */
    @nullable String joinerPath;
    String tokensPath;
    /** Código ISO do Whisper ("pt", "en"…); nulo = detecção automática. Ignorado no TRANSDUCER. */
    @nullable String language;
    /** Soma dos arquivos — base da guarda de RAM (NAT-07). */
    long modelBytes;
}
