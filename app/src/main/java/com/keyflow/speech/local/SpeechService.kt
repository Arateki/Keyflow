package com.keyflow.speech.local

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.os.StrictMode
import android.util.Log
import com.keyflow.BuildConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Processo `:speech` (FLOC-04). Tudo chega pelo AIDL: aqui não se lê Settings, DataStore, catálogo nem cofre,
 * e nada faz rede (no debug, o StrictMode mata o processo se tentar). A sessão é o [VitsVoice], preguiçosa,
 * na `libonnxruntime.so` que o app já tem.
 */
class SpeechService : Service() {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            if (BuildConfig.DEBUG) {
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeath().build())
            }
            runnable.run()
        }, "speech")
    }
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "speech-idle") }

    private val core by lazy {
        SpeechServiceCore(
            voice = VitsVoice(),
            availableMb = ::availableMemoryMb,
            scheduler = object : SpeechIdleScheduler {
                override fun schedule(delayMs: Long, block: () -> Unit): SpeechCancellable {
                    val future = timer.schedule(block, delayMs, TimeUnit.MILLISECONDS)
                    return SpeechCancellable { future.cancel(false) }
                }
            },
            executor = worker,
            peakRssKb = ::peakRssKb,
            resetPeak = ::resetPeakRss,
        )
    }

    private val binder = object : ISpeechService.Stub() {
        override fun speak(modelPath: String, tokensPath: String, text: String, speed: Float, ramEstimateMb: Int, cb: ISpeechCallback) {
            core.speak(SpeechJob(modelPath, tokensPath, text, speed, ramEstimateMb), SpeechCallbackListener(cb) { core.cancel() })
        }

        override fun cancel() = core.cancel()
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeath().build())
        }
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

    private fun peakRssKb(): Long = readStatusKb("VmHWM:")

    private fun resetPeakRss() {
        runCatching { File("/proc/self/clear_refs").writeText("5") }
    }

    private fun readStatusKb(key: String): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith(key) }?.removePrefix(key)?.trim()?.removeSuffix("kB")?.trim()?.toLong() ?: 0L
        }
    }.getOrDefault(0L)
}

/**
 * Entrega o PCM ao cliente. `RemoteException` (cliente morto ou buffer oneway cheio) cancela a fala:
 * sem isso a síntese ia até o fim e um `onDone` fechava o buraco do pedaço perdido.
 */
internal class SpeechCallbackListener(
    private val cb: ISpeechCallback,
    private val cancel: () -> Unit,
) : SpeechServiceCore.Listener {
    private val dead = AtomicBoolean(false)

    override fun onPcm(pcm: ByteArray, sampleRate: Int) = safely { cb.onPcm(pcm, sampleRate) }

    override fun onDone(peakRssKb: Long) = safely { cb.onDone(peakRssKb) }

    override fun onError(kind: Int, detail: String) = safely {
        Log.w(TAG, "falha $kind: $detail")
        cb.onError(kind, detail)
    }

    private inline fun safely(call: () -> Unit) {
        if (dead.get()) return
        try {
            call()
        } catch (e: RemoteException) {
            Log.w(TAG, "cliente sumiu antes da resposta", e)
            if (dead.compareAndSet(false, true)) cancel()
        }
    }

    private companion object {
        const val TAG = "SpeechService"
    }
}
