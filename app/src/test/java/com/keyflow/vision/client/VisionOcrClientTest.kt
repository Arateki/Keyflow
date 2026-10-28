package com.keyflow.vision.client

import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
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

    private fun pendingConnection() = VisionConnection(object : ContextWrapper(null) {
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int) = true
        override fun unbindService(connection: ServiceConnection) = Unit
    }, bindTimeoutMs = 10)

    @Test
    fun `timeout do bind retorna falha sem cancelar o chamador`() = runTest {
        val connection = pendingConnection()
        val model = VisionModel("ocr", OcrModelFiles("/det", "/rec", "/dict"), 0, 0, 1)
        try {
            val result = VisionOcrClient({ model }, connection).recognize(File("/recorte"), 1, 1)
            assertThat(result).isInstanceOf(OcrResult.Failed::class.java)
            assertThat((result as OcrResult.Failed).kind).isEqualTo(com.keyflow.vision.ocr.OcrFailure.TIMEOUT)
        } finally { connection.close() }
    }

    @Test
    fun `cancelamento durante bind é propagado ao chamador`() = runTest {
        val connection = pendingConnection()
        val model = VisionModel("ocr", OcrModelFiles("/det", "/rec", "/dict"), 0, 0, 1)
        var returned = false
        try {
            val request = async { VisionOcrClient({ model }, connection).recognize(File("/recorte"), 1, 1); returned = true }
            runCurrent()
            request.cancel()
            runCurrent()
            assertThat(request.isCancelled).isTrue()
            assertThat(returned).isFalse()
        } finally { connection.close() }
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
