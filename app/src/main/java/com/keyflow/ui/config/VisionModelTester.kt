package com.keyflow.ui.config

import android.content.Context
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.data.models.ModelCatalog
import com.keyflow.reasoning.ReasoningError
import com.keyflow.vision.VisionImage
import com.keyflow.vision.VisionResolution
import com.keyflow.vision.VisionResult
import com.keyflow.vision.ocr.OcrResult
import com.keyflow.vision.ocr.OcrFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface VisionTester { suspend fun run(settings: Settings): VisionResult }

/** A mesma imagem nossa para OCR, Gemma e nuvem; só a origem explicitamente escolhida recebe os pixels. */
class VisionModelTester(
    context: Context,
    private val resolve: (Settings) -> VisionResolution,
    private val ocr: OcrTester = VisionOcrTester(context),
) : VisionTester {
    override suspend fun run(settings: Settings): VisionResult = withContext(Dispatchers.IO) {
        try {
            if (settings.visionSource == VisionSource.LOCAL && settings.visionLocalModelId == ModelCatalog.OCR_PADDLE_LATIN.id) {
                return@withContext when (val result = ocr.run()) {
                    is OcrResult.Text -> VisionResult.Text(result.text)
                    OcrResult.Empty -> VisionResult.Empty
                    OcrResult.Unavailable -> VisionResult.Unavailable
                    is OcrResult.Failed -> VisionResult.Failed(if (result.kind == OcrFailure.OOM) ReasoningError.OOM else ReasoningError.LOCAL)
                }
            }
            when (val resolution = resolve(settings)) {
                is VisionResolution.Unavailable -> if (resolution.reason == com.keyflow.reasoning.UnavailableReason.NOT_CONNECTED)
                    VisionResult.Failed(ReasoningError.UNAUTHORIZED) else VisionResult.Unavailable
                is VisionResolution.Ready -> {
                    val bitmap = VisionTestImage.bitmap()
                    try { resolution.engine.read(VisionImage.encode(bitmap)) } finally { bitmap.recycle() }
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: OutOfMemoryError) { VisionResult.Failed(ReasoningError.OOM)
        } catch (_: Exception) { VisionResult.Failed(ReasoningError.ENGINE) }
    }
}
