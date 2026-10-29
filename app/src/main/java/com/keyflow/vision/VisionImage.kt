package com.keyflow.vision

import android.graphics.Bitmap
import com.keyflow.reasoning.ReasoningImage
import java.io.ByteArrayOutputStream

/** Recorte em memória: no máximo 1024 px no lado maior, PNG efêmero; o bitmap original continua sendo de quem chamou. */
object VisionImage {
    fun encode(bitmap: Bitmap): ReasoningImage {
        val largest = maxOf(bitmap.width, bitmap.height)
        val scale = minOf(1.0, 1024.0 / largest)
        val reduced = if (scale < 1) Bitmap.createScaledBitmap(bitmap,
            maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()), true) else bitmap
        return try {
            val bytes = ByteArrayOutputStream().use { out ->
                check(reduced.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
            ReasoningImage(bytes)
        } finally { if (reduced !== bitmap) reduced.recycle() }
    }
}
