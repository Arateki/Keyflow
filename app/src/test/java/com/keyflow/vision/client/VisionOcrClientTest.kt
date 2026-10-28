package com.keyflow.vision.client

import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import com.keyflow.vision.ocr.OcrModelFiles
import com.keyflow.vision.ocr.OcrResult
import com.keyflow.vision.service.IVisionCallback
import com.keyflow.vision.service.IVisionService
import com.keyflow.vision.service.VisionConfig
import com.keyflow.vision.service.VisionModel
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** O cliente real com Binder falso: cancelar sem esperar o finally não pode cancelar o pedido seguinte. */
@OptIn(ExperimentalCoroutinesApi::class)
class VisionOcrClientTest {
    private class Service : IVisionService.Stub() {
        val calls = mutableListOf<String>()
        val pending = mutableListOf<IVisionCallback>()
        override fun recognize(config: VisionConfig, cropPath: String, width: Int, height: Int, cb: IVisionCallback) {
            calls += "ler"
            pending += cb
        }
        override fun cancel() { calls += "cancelar"; pending.clear() }
    }

    @Test
    fun `cancelar sem join publica o cancelamento antes do próximo reconhecimento`() = runTest {
        val service = Service()
        val connection = VisionConnection(ContextWrapper(null), lingerMs = Long.MAX_VALUE)
        // O bind é coberto no Android; aqui injeta só uma conexão já ligada para controlar a ordem do Binder na JVM.
        VisionConnection::class.java.getDeclaredField("service").apply { isAccessible = true }.set(connection, service)
        val model = VisionModel("ocr", OcrModelFiles("/det", "/rec", "/dict"), 0, 0, 1)
        val client = VisionOcrClient({ model }, connection)
        try {
            val first = async { client.recognize(File("/recorte"), 1, 1) }
            runCurrent()
            first.cancel()
            // Publica o novo pedido antes de dar ao dispatcher a vez de executar o finally do anterior.
            val second = async(start = CoroutineStart.UNDISPATCHED) { client.recognize(File("/recorte"), 1, 1) }
            runCurrent()
            service.pending.lastOrNull()?.onText("segundo")
            assertThat(second.await()).isEqualTo(OcrResult.Text("segundo"))
            assertThat(service.calls).containsExactly("ler", "cancelar", "ler").inOrder()
        } finally { connection.close() }
    }
}
