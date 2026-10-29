package com.keyflow.speech.local;

/**
 * PCM de uma fala no :speech. onPcm zero ou mais vezes e, no fim, exatamente um entre onDone e onError.
 * kind é o código de SpeechProtocol.
 */
interface ISpeechCallback {
    oneway void onPcm(in byte[] pcm, int sampleRate);
    oneway void onDone(long peakRssKb);
    oneway void onError(int kind, String message);
}
