package com.keyflow.bancada.adaptadores

import android.content.Context
import android.graphics.BitmapFactory
import com.keyflow.bancada.executor.ImagemDoCorpus
import com.keyflow.bancada.executor.ImagemEmMemoria
import com.keyflow.bancada.executor.EntradaImagem
import com.keyflow.bancada.executor.ReconhecedorDeImagem
import com.keyflow.bancada.corpus.PromptMontado
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.data.models.ModelCatalog
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningImage
import com.keyflow.vision.VisionResolution
import com.keyflow.vision.VisionResult
import com.keyflow.vision.client.VisionCache
import com.keyflow.vision.client.VisionOcrClient
import com.keyflow.vision.ocr.OcrFailure
import com.keyflow.vision.ocr.OcrResult
import com.keyflow.vision.ocr.RgbaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Lê só imagens do corpus; o OCR local usa o serviço isolado e o modelo de visão usa a origem selecionada. */
class ReconhecedorVisao(
    private val context: Context,
    private val settings: Settings,
    private val resolve: (Settings) -> VisionResolution,
    private val ocr: VisionOcrClient = VisionOcrClient.create(context),
) : ReconhecedorDeImagem {
    override fun reconhecer(imagem: EntradaImagem, prompt: PromptMontado): Flow<ReasoningEvent> = flow {
        val resultado = try { withContext(Dispatchers.IO) {
            val (bytes, mimeType) = when (imagem) {
                is ImagemDoCorpus -> context.assets.open("${BancadaServicos.PASTA_DO_CORPUS}/${imagem.caminho}").use { it.readBytes() } to "image/png"
                is ImagemEmMemoria -> imagem.bytes() to imagem.mimeType
            }
            if (settings.visionSource == VisionSource.LOCAL && settings.visionLocalModelId == ModelCatalog.OCR_PADDLE_LATIN.id) {
                reconhecerOcr(bytes)
            } else {
                when (val resolucao = resolve(settings)) {
                    is VisionResolution.Ready -> resolucao.engine.ask(ReasoningImage(bytes, mimeType), prompt.user)
                    is VisionResolution.Unavailable -> if (resolucao.reason == com.keyflow.reasoning.UnavailableReason.NOT_CONNECTED)
                        VisionResult.Failed(ReasoningError.UNAUTHORIZED) else VisionResult.Unavailable
                }
            }
        } } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (_: OutOfMemoryError) { VisionResult.Failed(ReasoningError.OOM) }
        when (resultado) {
            is VisionResult.Text -> { emit(ReasoningEvent.Delta(resultado.text)); emit(ReasoningEvent.Done) }
            VisionResult.Empty -> emit(ReasoningEvent.Done)
            VisionResult.Unavailable -> emit(ReasoningEvent.Error(ReasoningError.MODEL_MISSING, null))
            is VisionResult.Failed -> emit(ReasoningEvent.Error(resultado.kind, null))
        }
    }

    private suspend fun reconhecerOcr(bytes: ByteArray): VisionResult {
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, limites)
        require(limites.outWidth > 0 && limites.outHeight > 0 &&
            limites.outWidth.toLong() * limites.outHeight <= RgbaImage.MAX_PIXELS) { "imagem fora do limite" }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return VisionResult.Failed(ReasoningError.ENGINE)
        try {
            val w = bitmap.width
            val h = bitmap.height
            require(w.toLong() * h <= RgbaImage.MAX_PIXELS)
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val rgba = ByteArray(pixels.size * 4)
            pixels.forEachIndexed { i, c ->
                rgba[i * 4] = (c shr 16).toByte()
                rgba[i * 4 + 1] = (c shr 8).toByte()
                rgba[i * 4 + 2] = c.toByte()
                rgba[i * 4 + 3] = (c ushr 24).toByte()
            }
            val arquivo = VisionCache.writeCrop(context, RgbaImage(w, h, rgba))
            return try {
                when (val resultado = ocr.recognize(arquivo, w, h)) {
                    is OcrResult.Text -> VisionResult.Text(resultado.text)
                    OcrResult.Empty -> VisionResult.Empty
                    OcrResult.Unavailable -> VisionResult.Unavailable
                    is OcrResult.Failed -> VisionResult.Failed(when (resultado.kind) {
                        OcrFailure.OOM -> ReasoningError.OOM
                        OcrFailure.PROCESS_DIED, OcrFailure.TIMEOUT, OcrFailure.CANCELLED -> ReasoningError.LOCAL
                        else -> ReasoningError.ENGINE
                    })
                }
            } finally { arquivo.delete() }
        } finally { bitmap.recycle() }
    }
}
