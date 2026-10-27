package com.keyflow.vision.service;

/**
 * Resposta de um reconhecimento no :vision: no máximo um onStats e, no fim, exatamente UM entre onText, onEmpty, onUnavailable e
 * onFailed. `kind` é o código de OcrFailure.
 */
interface IVisionCallback {
    oneway void onStats(long loadMs, long detectMs, long recognizeMs, long totalMs, int strips, int boxes, int lines, long peakRssKb);
    oneway void onText(String text);
    oneway void onEmpty();
    oneway void onUnavailable(String reason);
    oneway void onFailed(int kind, String message);
}
