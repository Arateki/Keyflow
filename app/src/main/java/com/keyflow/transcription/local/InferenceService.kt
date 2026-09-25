package com.keyflow.transcription.local

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
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
    private val engine by lazy { SherpaEngine() }

    private val binder = object : ITranscriptionEngine.Stub() {
        override fun preload(config: LocalModelConfig, cb: IEngineCallback) = submit(cb) {
            ensureLoaded(EngineModel.fromParcel(config))
            ""
        }

        override fun transcribe(config: LocalModelConfig, audioPath: String, cb: IEngineCallback) = submit(cb) {
            ensureLoaded(EngineModel.fromParcel(config))
            val samples = try {
                AudioDecoder.decodeToMono16k(File(audioPath))
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (e: Exception) {
                throw EngineFailure(EngineErrorKind.DECODE, "não consegui ler o áudio: ${e.message}", e)
            }
            engine.transcribe(samples)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    /** O unload entra na fila: nunca solta o modelo no meio de uma transcrição da thread do executor. */
    override fun onDestroy() {
        executor.execute { engine.unload() }
        executor.shutdown()
        super.onDestroy()
    }

    private fun ensureLoaded(model: EngineModel) {
        if (engine.isLoaded(model)) return
        val missing = model.files.filterNot { File(it).isFile }
        if (missing.isNotEmpty()) {
            throw EngineFailure(EngineErrorKind.MODEL_MISSING, "faltam arquivos do ${model.modelId}: ${missing.map { File(it).name }}")
        }
        // Libera o modelo anterior antes de medir: a troca não precisa caber junto com ele.
        engine.unload()
        val available = availableMemoryBytes()
        if (!MemoryGuard.enoughFor(model.bytes, available)) {
            throw EngineFailure(
                EngineErrorKind.OOM,
                "memória insuficiente para ${model.modelId}: ${available shr 20} MB livres, precisa de " +
                    "${MemoryGuard.requiredBytes(model.bytes) shr 20} MB",
            )
        }
        try {
            engine.load(model)
        } catch (e: IllegalArgumentException) {
            throw EngineFailure(EngineErrorKind.ENGINE, "o engine recusou ${model.modelId}", e)
        }
    }

    private fun availableMemoryBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.availMem
    }

    private fun submit(cb: IEngineCallback, work: () -> String) {
        executor.execute {
            val outcome: Result<String> = try {
                Result.success(work())
            } catch (e: OutOfMemoryError) {
                engine.unload()
                Result.failure(EngineFailure(EngineErrorKind.OOM, "memória esgotada durante a transcrição", e))
            } catch (e: Exception) {
                Result.failure(e)
            }
            deliver(cb, outcome)
        }
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
