package com.keyflow.reasoning.local

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.keyflow.core.ComputeBackend
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
            totalMb = ::totalMemoryMb,
            ramProbe = ::RamProbe,
            scheduler = object : IdleScheduler {
                override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
                    val future = timer.schedule(block, delayMs, TimeUnit.MILLISECONDS)
                    return Cancellable { future.cancel(false) }
                }
            },
            executor = worker,
            onWarmed = { model, elapsed, failure ->
                if (failure == null) Log.i(TAG, "${model.modelId}: aquecimento em $elapsed ms")
                else Log.w(TAG, "${model.modelId}: aquecimento falhou após $elapsed ms; seguindo sem ele", failure)
            },
        )
    }

    private val binder = object : IReasoningService.Stub() {
        override fun generate(config: LocalLlmConfig, system: String?, user: String, options: LocalGenOptions, cb: IReasoningCallback) {
            core.generate(
                LlmModel.fromParcel(config),
                ReasoningRequest(system = system?.takeIf { it.isNotBlank() }, user = user, options = options.toOptions()),
                CallbackListener(cb),
            )
        }

        override fun preload(config: LocalLlmConfig, cb: IReasoningCallback) =
            core.preload(LlmModel.fromParcel(config), CallbackListener(cb))

        override fun cancel() = core.cancel()

        override fun unload() = core.unload()

        override fun restart() {
            core.shutdown()
            worker.execute { android.os.Process.killProcess(android.os.Process.myPid()) }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        core.shutdown()
        worker.shutdown()
        timer.shutdownNow()
        super.onDestroy()
    }

    private fun totalMemoryMb(): Long {
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem shr 20
    }

    private fun availableMemoryMb(): Long {
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.availMem shr 20
    }

    /** Liga o núcleo ao callback do AIDL; quem pediu pode ter morrido — aí não há a quem responder. */
    private class CallbackListener(private val cb: IReasoningCallback) : ReasoningServiceCore.Listener {
        override fun onMetrics(metrics: com.keyflow.reasoning.ReasoningEvent.Metrics) = safely { cb.onMetrics(LocalMetricsCodec.encode(metrics)) }
        override fun onDelta(text: String) = safely { cb.onDelta(text) }
        override fun onDone() = safely { cb.onDone() }
        override fun onError(kind: ReasoningErrorKind, message: String) {
            Log.w(TAG, "falha $kind: $message")
            safely { cb.onError(kind.code, message) }
        }

        override fun onBackendFallback(failed: ComputeBackend, reason: String) {
            Log.w(TAG, "a carga na $failed falhou; seguindo na CPU: $reason")
            safely { cb.onBackendFallback(failed.name, reason) }
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
