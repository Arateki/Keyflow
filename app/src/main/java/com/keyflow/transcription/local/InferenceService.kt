package com.keyflow.transcription.local

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Processo `:inference` (NAT-08): hospeda o [SherpaEngine] e o [AudioDecoder]. Tudo chega pelo AIDL —
 * aqui não se lê Settings, DataStore nem catálogo (G9). Trabalho serializado num executor único: uma
 * transcrição por vez, na ordem de chegada. O modelo fica carregado (G10); se o sistema matar o
 * processo, o cliente refaz bind e preload (NM-T9).
 */
class InferenceService : Service() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "inference")
    }
    private val core by lazy {
        InferenceServiceCore(
            engine = SherpaEngine(),
            executor = executor,
            fileExists = { File(it).isFile },
            availableBytes = ::availableMemoryBytes,
            onLoaded = { model, elapsed, available ->
                Log.i(TAG, "${model.modelId}: carregado em $elapsed ms (${available shr 20} MB livres antes)")
            },
            onWarmed = { model, elapsed, failure ->
                if (failure == null) Log.i(TAG, "${model.modelId}: aquecimento em $elapsed ms")
                else Log.w(TAG, "${model.modelId}: aquecimento falhou após $elapsed ms; seguindo sem ele", failure)
            },
        )
    }

    private val binder = object : ITranscriptionEngine.Stub() {
        override fun preload(config: LocalModelConfig, cb: IEngineCallback) =
            core.preload({ EngineModel.fromParcel(config) }) { deliver(cb, it) }

        override fun transcribe(config: LocalModelConfig, audioPath: String, cb: IEngineCallback) = core.transcribe(
            model = { EngineModel.fromParcel(config) },
            reply = { deliver(cb, it) },
        ) { engine, model ->
            val started = SystemClock.elapsedRealtime()
            val samples = try {
                AudioDecoder.decodeToMono16k(File(audioPath))
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (e: Exception) {
                throw EngineFailure(EngineErrorKind.DECODE, "não consegui ler o áudio: ${e.message}", e)
            }
            val decoded = SystemClock.elapsedRealtime()
            engine.transcribe(samples).also {
                // Medição informativa do A32 (G11, NM-T13): `adb logcat -s InferenceService`.
                Log.i(
                    TAG,
                    "${model.modelId}: ${samples.size * 1000L / AudioDecoder.TARGET_SAMPLE_RATE} ms de áudio → " +
                        "decodificar ${decoded - started} ms + transcrever ${SystemClock.elapsedRealtime() - decoded} ms",
                )
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    /** O unload entra na fila: nunca solta o modelo no meio de uma transcrição da thread do executor. */
    override fun onDestroy() {
        core.shutdown()
        executor.shutdown()
        super.onDestroy()
    }

    private fun availableMemoryBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.availMem
    }

    private fun deliver(cb: IEngineCallback, outcome: Result<String>) {
        try {
            outcome.fold(
                onSuccess = { cb.onSuccess(it) },
                onFailure = { e ->
                    val failure = e as? EngineFailure ?: EngineFailure(EngineErrorKind.ENGINE, e.message ?: e.toString(), e)
                    Log.w(TAG, "falha ${failure.kind}: ${failure.message}", failure.cause)
                    cb.onError(failure.kind.code, failure.message)
                },
            )
        } catch (e: RemoteException) {
            // Quem pediu morreu: não há a quem responder.
            Log.w(TAG, "cliente sumiu antes da resposta", e)
        }
    }

    private companion object {
        const val TAG = "InferenceService"
    }
}
