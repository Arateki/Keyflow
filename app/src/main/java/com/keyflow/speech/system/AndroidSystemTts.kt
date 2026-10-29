package com.keyflow.speech.system

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred

/** O `TextToSpeech` de verdade (FSYS-02): `speak` com `QUEUE_FLUSH`, `UtteranceProgressListener` para `onStart`/`onDone`. Sem permissão extra. */
class AndroidSystemTts(context: Context) : SystemTts {
    private val initialized = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> initialized.complete(status == TextToSpeech.SUCCESS) }

    override suspend fun ready(): Boolean = initialized.await()

    override fun setLanguage(locale: Locale): Int = tts.setLanguage(locale)

    override fun isLanguageAvailable(locale: Locale): Int = tts.isLanguageAvailable(locale)

    override fun setSpeechRate(rate: Float) {
        tts.setSpeechRate(rate)
    }

    override fun speak(text: String, utteranceId: String, callbacks: SystemTts.Callbacks) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = callbacks.onStart()
            override fun onDone(id: String?) = callbacks.onDone()

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = callbacks.onError()
            override fun onError(id: String?, errorCode: Int) = callbacks.onError()
        })
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle.EMPTY, utteranceId) != TextToSpeech.SUCCESS) callbacks.onError()
    }

    override fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }
}
