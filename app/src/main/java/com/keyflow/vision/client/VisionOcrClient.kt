package com.keyflow.vision.client

import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.keyflow.vision.ocr.OcrFailure
import com.keyflow.vision.ocr.OcrResult
import com.keyflow.vision.ocr.OcrStats
import com.keyflow.vision.service.IVisionCallback
import com.keyflow.vision.service.VisionModel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * O OCR no processo principal: liga ao `:vision`, pede o reconhecimento do recorte (arquivo RGBA cru) e devolve um [OcrResult] —
 * `Unavailable` se o modelo não está instalado, `Failed(PROCESS_DIED)` se o processo morrer no meio (o `DeathRecipient` avisa o
 * chamador na hora; a conexão é esquecida e o próximo uso liga de novo), `Failed(TIMEOUT)` se não responder a tempo. Cancelar a
 * corrotina manda `cancel()` ao serviço — sem isso o reconhecimento cancelado seguiria ocupando o motor. Sem rede em nenhum ponto.
 */
class VisionOcrClient internal constructor(
    private val modelFor: () -> VisionModel?,
    private val connection: VisionConnection,
    private val timeoutMs: Long = TIMEOUT_MS,
) {

    /** Estatísticas da última chamada (log e medição; o produto não depende delas). */
    @Volatile var lastStats: OcrStats = OcrStats()
        private set

    suspend fun recognize(crop: File, width: Int, height: Int): OcrResult {
        val model = modelFor() ?: return OcrResult.Unavailable
        val service = try {
            connection.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "não consegui ligar ao :vision", e)
            return OcrResult.Failed(OcrFailure.TIMEOUT, "o OCR não iniciou")
        }
        val binder: IBinder = service.asBinder()
        val finished = AtomicBoolean(false)
        var death: IBinder.DeathRecipient? = null
        try {
            return withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    fun finish(result: OcrResult) {
                        if (finished.compareAndSet(false, true) && cont.isActive) cont.resume(result)
                    }
                    val recipient = IBinder.DeathRecipient { finish(OcrResult.Failed(OcrFailure.PROCESS_DIED, "o processo do OCR morreu")) }
                    death = recipient
                    val callback = object : IVisionCallback.Stub() {
                        override fun onStats(
                            loadMs: Long, detectMs: Long, recognizeMs: Long, totalMs: Long,
                            strips: Int, boxes: Int, lines: Int, peakRssKb: Long,
                        ) {
                            lastStats = OcrStats(loadMs, detectMs, recognizeMs, totalMs, strips, boxes, lines, peakRssKb)
                        }

                        override fun onText(text: String) = finish(OcrResult.Text(text))
                        override fun onEmpty() = finish(OcrResult.Empty)
                        override fun onUnavailable(reason: String) = finish(OcrResult.Unavailable)
                        override fun onFailed(kind: Int, message: String) =
                            finish(OcrResult.Failed(OcrFailure.fromCode(kind), message))
                    }
                    try {
                        binder.linkToDeath(recipient, 0)
                        service.recognize(model.toParcel(), crop.absolutePath, width, height, callback)
                    } catch (e: RemoteException) {
                        finish(OcrResult.Failed(OcrFailure.PROCESS_DIED, "o processo do OCR não respondeu"))
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            return OcrResult.Failed(OcrFailure.TIMEOUT, "o OCR não respondeu em ${timeoutMs / 1000} s")
        } finally {
            // Cancelado no meio, com tempo esgotado ou depois do fim: avisa o serviço só se ainda havia reconhecimento em curso.
            if (finished.compareAndSet(false, true)) runCatching { service.cancel() }
            death?.let { runCatching { binder.unlinkToDeath(it, 0) } }
            connection.release()
        }
    }

    companion object {
        private const val TAG = "VisionOcrClient"

        /** Tempo máximo de um reconhecimento (tela cheia num aparelho lento; a meta é ≤ 5 s no A32). */
        const val TIMEOUT_MS = 60_000L

        fun create(context: Context): VisionOcrClient = VisionOcrClient(
            modelFor = { VisionModels.installed(context.applicationContext) },
            connection = VisionConnection.get(context),
        )
    }
}
