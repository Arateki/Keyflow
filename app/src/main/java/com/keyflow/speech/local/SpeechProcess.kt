package com.keyflow.speech.local

import android.app.Application
import android.os.Build
import java.io.File

/**
 * Em qual processo estamos? O `:speech` recebe tudo pelo AIDL e não abre cofre nem Settings, mas o Android roda
 * `Application.onCreate()` em todo processo. `App.onCreate()` pergunta aqui e sai antes desses acessos.
 */
object SpeechProcess {
    const val SUFFIX = ":speech"

    /** `com.arateki.keyflow:speech` sim; o processo principal e os outros sufixos não. */
    fun isSpeech(processName: String?): Boolean =
        processName != null && processName.length > SUFFIX.length && processName.endsWith(SUFFIX)

    fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            runCatching {
                File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
            }.getOrNull()
        }

    fun isCurrent(): Boolean = isSpeech(currentProcessName())
}
