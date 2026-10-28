package com.keyflow.vision.service

import android.app.Application
import android.os.Build
import java.io.File

/**
 * Em qual processo estamos? O `:vision` recebe tudo pelo AIDL (design §6: não lê Settings, DataStore nem cofres), mas o Android roda o
 * `Application.onCreate()` em TODO processo do app; `App.onCreate()` pergunta aqui e, no `:vision`, sai antes de abrir cofres e de
 * coletar Settings. `VisionRulesTest` trava a ordem (a guarda vem antes de qualquer acesso).
 */
object VisionProcess {
    const val SUFFIX = ":vision"

    /** `com.arateki.keyflow:vision` sim; o processo principal, `:inference`, `:reasoning` e nomes parecidos não. */
    fun isVision(processName: String?): Boolean = processName != null && processName.length > SUFFIX.length && processName.endsWith(SUFFIX)

    /** O nome do processo atual: `Application.getProcessName()` (API 28+) ou o `cmdline` do próprio processo; `null` se não der para saber. */
    fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8) }.getOrNull()
        }

    fun isCurrent(): Boolean = isVision(currentProcessName())
}
