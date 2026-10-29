package com.keyflow.speech.local;

import com.keyflow.speech.local.ISpeechCallback;

/**
 * Fronteira do processo :speech (FLOC-04). oneway + callback: quem chama não bloqueia no binder.
 * O processo não lê Settings nem cofre: recebe o caminho já verificado, o texto e a velocidade.
 */
interface ISpeechService {
    /** Sintetiza [text]. Sem arquivo no caminho, responde MODEL_MISSING e não abre sessão. */
    oneway void speak(String modelPath, String tokensPath, String text, float speed, int ramEstimateMb, ISpeechCallback cb);
    /** Interrompe a fala em curso; depois disso o serviço não fala mais com o callback dela. */
    oneway void cancel();
}
