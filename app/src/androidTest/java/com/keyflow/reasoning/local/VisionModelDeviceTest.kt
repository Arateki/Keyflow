package com.keyflow.reasoning.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Content
import com.keyflow.reasoning.ReasoningImage
import com.keyflow.ui.config.VisionTestImage
import com.keyflow.vision.LocalVisionEngine
import com.keyflow.vision.VisionImage
import com.keyflow.vision.VisionResult
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** CI sem pesos: imagem pelo binder real, limpeza de cache e contrato multimodal da biblioteca fixada. */
@RunWith(AndroidJUnit4::class)
class VisionModelDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun imagemPeloBinderTemRespostaELimpaOCachePrivado() = runBlocking {
        val directory = File(context.cacheDir, "reasoning-images")
        val connection = ReasoningConnection(context, lingerMs = 0)
        val model = LlmModel(ECHO_MODEL_ID, "", "LITERT_LM", 0, 0, vision = true)
        val transport = LocalReasoningEngine(ECHO_MODEL_ID, { model }, connection, imageDirectory = directory)
        val bitmap = VisionTestImage.bitmap()
        val image = try { VisionImage.encode(bitmap) } finally { bitmap.recycle() }
        try {
            val result = withTimeout(20_000) { LocalVisionEngine(transport).ask(image, "Resposta pelo binder") }
            assertEquals(VisionResult.Text("Resposta pelo binder"), result)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertTrue(LlmModel.fromParcel(model.toParcel()).vision)
        } finally { connection.close() }
    }
    @Test fun recorteVaiComoPngReduzidoSemReciclarOOriginal() {
        val bitmap = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        try {
            val image = VisionImage.encode(bitmap)
            val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, size)
            assertEquals(1024, size.outWidth); assertEquals(512, size.outHeight)
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }
    @Test fun mensagemDoLiteRtContemImagemEPedido() {
        val bytes = byteArrayOf(1, 2, 3)
        val message = LiteRtConfigs.user("Leia", image = ReasoningImage(bytes))
        val parts = message.contents.contents
        assertTrue(parts.first() is Content.ImageBytes)
        assertArrayEquals(bytes, (parts.first() as Content.ImageBytes).bytes)
        assertEquals("Leia", (parts.last() as Content.Text).text)
    }
}
