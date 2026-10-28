package com.keyflow.vision.service;

/**
 * Tudo que o :vision precisa para carregar e rodar o OCR (design §6): ele não lê Settings, DataStore nem o catálogo. Caminhos
 * absolutos dentro do armazenamento do próprio app (mesmo UID).
 */
parcelable VisionConfig {
    String modelId;
    /** Detector (papel DETECTOR do catálogo), reconhecedor (RECOGNIZER) e dicionário (DICTIONARY). */
    String detectorPath;
    String recognizerPath;
    String dictionaryPath;
    /** Descarrega os modelos depois deste tempo sem uso; 0 = mantém carregado. */
    long idleUnloadMs;
    /** Memória livre estimada para carregar, em MB — base da guarda de RAM. */
    int ramEstimateMb;
    /** Threads de cálculo da ONNX Runtime por sessão. */
    int threads;
    /** Identidade da versão dos arquivos (SHA-256 do catálogo): trocar os arquivos sob o mesmo [modelId] descarrega o residente. */
    String version;
}
