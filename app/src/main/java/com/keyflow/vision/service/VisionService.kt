package com.keyflow.vision.service

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.os.StrictMode
import android.util.Log
import com.keyflow.BuildConfig
import com.keyflow.vision.ocr.OcrParams
import com.keyflow.vision.ocr.OcrResult
import com.keyflow.vision.ocr.OcrStats
import com.keyflow.vision.ocr.PaddleOcrEngine
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Processo `:vision` (OCR-03): hospeda o [PaddleOcrEngine]. Tudo chega pelo AIDL — aqui não se lê Settings, DataStore nem catálogo, e
 * nada faz rede (no debug, o StrictMode de rede mata o processo se algo tentar). O trabalho vive em [VisionServiceCore]; esta classe
 * só liga o binder, a memória do sistema e o relógio de ociosidade. Processo próprio (e não o `:inference`) porque o modelo de fala
 * fica residente lá para o ditado e um crash nativo do OCR não pode derrubá-lo. Se o sistema matar o processo, o cliente refaz o bind.
 */
class VisionService : Service() {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            // Só no debug: rede nesta thread (ou na principal) derruba o processo — o teste vê o serviço morrer (OCR-03).
            if (BuildConfig.DEBUG) {
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeath().build())
            }
            runnable.run()
        }, "vision")
    }
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "vision-idle") }

    private val core by lazy {
        VisionServiceCore(
            engineFor = { model -> PaddleOcrEngine(model.files, OcrParams(threads = model.threads)) },
            availableMb = ::availableMemoryMb,
            scheduler = object : IdleScheduler {
                override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
                    val future = timer.schedule(block, delayMs, TimeUnit.MILLISECONDS)
                    return Cancellable { future.cancel(false) }
                }
            },
            executor = worker,
            peakRssKb = ::peakRssKb,
            resetPeak = ::resetPeakRss,
        )
    }

    private val binder = object : IVisionService.Stub() {
        override fun recognize(config: VisionConfig, cropPath: String, width: Int, height: Int, cb: IVisionCallback) {
            core.recognize(VisionModel.fromParcel(config), cropPath, width, height, CallbackListener(cb))
        }

        override fun cancel() = core.cancel()
    }

    override fun onCreate() {
        super.onCreate()
        // Só no debug, como na thread de trabalho: rede na thread principal do :vision também mata o processo.
        if (BuildConfig.DEBUG) StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeath().build())
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

    /** Pico de RSS do processo (VmHWM, kB) — `/proc/self/status`; 0 se não der para ler. */
    private fun peakRssKb(): Long = readStatusKb("VmHWM:")

    /** Zera o pico (`clear_refs` = 5) para medir cada reconhecimento sozinho; sem permissão, o pico fica acumulado e só sobe. */
    private fun resetPeakRss() {
        runCatching { File("/proc/self/clear_refs").writeText("5") }
    }

    private fun readStatusKb(key: String): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith(key) }?.removePrefix(key)?.trim()?.removeSuffix("kB")?.trim()?.toLong() ?: 0L
        }
    }.getOrDefault(0L)

    /** Liga o núcleo ao callback do AIDL; quem pediu pode ter morrido — aí não há a quem responder. */
    private class CallbackListener(private val cb: IVisionCallback) : VisionServiceCore.Listener {
        override fun onStats(stats: OcrStats) = safely {
            cb.onStats(
                stats.loadMs, stats.detectMs, stats.recognizeMs, stats.totalMs, stats.strips, stats.boxes, stats.lines, stats.peakRssKb,
            )
        }

        override fun onResult(result: OcrResult) = safely {
            when (result) {
                is OcrResult.Text -> cb.onText(result.text)
                OcrResult.Empty -> cb.onEmpty()
                OcrResult.Unavailable -> cb.onUnavailable("modelo ou ONNX Runtime indisponível")
                is OcrResult.Failed -> {
                    Log.w(TAG, "falha ${result.kind}: ${result.message}")
                    cb.onFailed(result.kind.code, result.message)
                }
            }
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
        const val TAG = "VisionService"
    }
}
