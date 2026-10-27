package com.keyflow.vision.service;

import com.keyflow.vision.service.IVisionCallback;
import com.keyflow.vision.service.VisionConfig;

/**
 * Fronteira do processo :vision (OCR-03). oneway + callback: quem chama nunca bloqueia no binder. O recorte NÃO atravessa o binder
 * (uma tela cheia tem ~10 MB, o limite da transação é ~1 MB): vai num arquivo RGBA cru em cacheDir/vision/ e só o caminho e o
 * tamanho passam. O serviço faz um reconhecimento por vez, na ordem de chegada.
 */
interface IVisionService {
    /** Carrega os modelos se preciso e reconhece o recorte (RGBA, 4 bytes por pixel, width x height). Responde pelo callback. */
    oneway void recognize(in VisionConfig config, String cropPath, int width, int height, IVisionCallback cb);
    /** Interrompe o reconhecimento em curso; depois disso o serviço não fala mais com o callback dele. */
    oneway void cancel();
}
