package com.keyflow

import android.os.Build
import android.os.StrictMode
import android.os.strictmode.Violation
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * StrictMode no build de debug: acusa disco e rede feitos NA thread principal e recursos que ninguém fecha (o `runBlocking`
 * esperando outra thread não aparece aqui — esse fica com o ArchitectureRulesTest). Só registra (log + [violations]), nunca
 * derruba o app: o instrumentado StrictModeKeyboardTest lê a lista e falha com violação nova no caminho do teclado.
 */
object StrictModeGuard {

    /** Uma violação: o tipo e a 1ª linha da pilha que é do KeyFlow (onde ela nasceu no nosso código), se houver. */
    data class Record(val kind: String, val ourFrame: String?, val thread: String)

    private val recorded = ConcurrentLinkedQueue<Record>()

    /** As violações desde o início do processo (ou desde o último [clear]), na ordem em que aconteceram. */
    val violations: List<Record> get() = recorded.toList()

    fun clear() = recorded.clear()

    fun install() {
        val thread = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .detectCustomSlowCalls()
            .penaltyLog()
        val vm = StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects()
            .detectLeakedRegistrationObjects()
            .detectLeakedSqlLiteObjects()
            .penaltyLog()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val executor = Executors.newSingleThreadExecutor { Thread(it, "StrictModeGuard") }
            thread.penaltyListener(executor) { record(it, "main") }
            vm.penaltyListener(executor) { record(it, "vm") }
        }
        StrictMode.setThreadPolicy(thread.build())
        StrictMode.setVmPolicy(vm.build())
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun record(violation: Violation, thread: String) {
        val ours = violation.stackTrace.firstOrNull { it.className.startsWith("com.keyflow.") && !it.className.startsWith(StrictModeGuard::class.java.name) }
        val entry = Record(violation.javaClass.simpleName, ours?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }, thread)
        recorded += entry
        if (recorded.size > MAX) recorded.poll()
        Log.w(TAG, "${entry.kind} em ${entry.ourFrame ?: "código do sistema"} ($thread)")
    }

    private const val MAX = 500
    private const val TAG = "KeyFlow.StrictMode"
}
