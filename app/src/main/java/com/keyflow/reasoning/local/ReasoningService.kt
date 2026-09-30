package com.keyflow.reasoning.local

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.keyflow.reasoning.ReasoningRequest
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Processo `:reasoning` (RRUN-02): hospeda o motor de texto local. Tudo chega pelo AIDL — aqui não se lê Settings,
 * DataStore nem catálogo. O trabalho vive em [ReasoningServiceCore]; esta classe só liga o binder, a memória do sistema
 * e o relógio de ociosidade. Se o sistema matar o processo, o cliente refaz o bind no próximo uso (RRUN-05).
 */
class ReasoningService : Service() {

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "reasoning") }
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "reasoning-idle") }

    private val core by lazy {
        ReasoningServiceCore(
            backendFor = { model -> ReasoningBackends.create(model, cacheDir.path) },
            fileExists = { File(it).isFile },
            availableMb = ::availableMemoryMb,
            scheduler = object : IdleScheduler {
                override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
                    val future = timer.schedule(block, delayMs, TimeUnit.MILLISECONDS)
                    return Cancellable { future.cancel(false) }
                }
            },
            executor = worker,
        )
    }

    private val binder = object : IReasoningService.Stub() {
        override fun generate(config: LocalLlmConfig, system: String?, user: String, cb: IReasoningCallback) {
            core.generate(
                LlmModel.fromParcel(config),
                ReasoningRequest(system = system?.takeIf { it.isNotBlank() }, user = user),
                CallbackListener(cb),
            )
        }

        override fun cancel() = core.cancel()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        core.shutdown()
        worker.shutdown()
        timer.shutdownNow()
        super.onDestroy()
    }

    private fun availableMemoryMb(): Long {
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.availMem shr 20
    }

    /** Liga o núcleo ao callback do AIDL; quem pediu pode ter morrido — aí não há a quem responder. */
    private class CallbackListener(private val cb: IReasoningCallback) : ReasoningServiceCore.Listener {
        override fun onDelta(text: String) = safely { cb.onDelta(text) }
        override fun onDone() = safely { cb.onDone() }
        override fun onError(kind: ReasoningErrorKind, message: String) {
            Log.w(TAG, "falha $kind: $message")
            safely { cb.onError(kind.code, message) }
        }

        private inline fun safely(call: () -> Unit) {
            try {
                call()
            } catch (e: RemoteException) {
                Log.w(TAG, "cliente sumiu antes da resposta", e)
            }
        }
    }

    private companion object {
        const val TAG = "ReasoningService"
    }
}
