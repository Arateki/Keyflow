package com.keyflow.vision

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.vision.client.VisionCache
import com.keyflow.vision.client.VisionConnection
import com.keyflow.vision.client.VisionOcrClient
import com.keyflow.vision.ocr.OcrFailure
import com.keyflow.vision.ocr.OcrModelFiles
import com.keyflow.vision.ocr.OcrResult
import com.keyflow.vision.ocr.OcrTestSupport
import com.keyflow.vision.ocr.RgbaImage
import com.keyflow.vision.service.VisionModel
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O cliente de verdade contra o `:vision` de verdade (OCR-03, MDL-04, CAP-04). Sem modelo (arquivos de mentira) cobre o processo
 * separado, o AIDL, o recorte por arquivo, a recusa por entrada ruim e por RAM, e o modelo ausente. Com o modelo real (opt-in:
 * pedir a classe em `-e class`, ou `-e ocrModelE2E true` / `ocrModelDir=<pasta>`, ver [OcrTestSupport.optedIn]) cobre o texto de ponta a ponta, a morte do
 * processo no meio e o cancelamento. No debug o `:vision` roda com StrictMode `detectNetwork` + `penaltyDeath`: uma violação mata o
 * processo e o teste do texto acusa `PROCESS_DIED`.
 */
@RunWith(AndroidJUnit4::class)
class VisionServiceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val connection = VisionConnection(context, lingerMs = 60_000)
    private val dir = File(context.cacheDir, "vision-service-test")

    @Before
    fun setUp() {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        connection.close()
        dir.deleteRecursively()
        VisionCache.clear(context)
    }

    private fun dummyFiles(): OcrModelFiles {
        val files = listOf("det.onnx", "rec.onnx", "dict.txt").map { File(dir, it).also { f -> f.writeText("x") } }
        return OcrModelFiles(files[0].path, files[1].path, files[2].path)
    }

    private fun client(model: VisionModel?, timeoutMs: Long = 60_000) =
        VisionOcrClient(modelFor = { model }, connection = connection, timeoutMs = timeoutMs)

    private fun blankCrop(w: Int = 64, h: Int = 32): Pair<File, RgbaImage> {
        val image = RgbaImage(w, h, ByteArray(w * h * 4) { 0xFF.toByte() })
        return VisionCache.writeCrop(context, image) to image
    }

    private fun visionPid(): Int? {
        val am = context.getSystemService(ActivityManager::class.java)
        return am.runningAppProcesses.orEmpty().firstOrNull { it.processName == "${context.packageName}:vision" }?.pid
    }

    @Test
    fun semModeloInstaladoEUnavailableENemLigaOProcesso() = runBlocking {
        val (file, image) = blankCrop()
        assertEquals(OcrResult.Unavailable, client(null).recognize(file, image.width, image.height))
    }

    @Test
    fun arquivosDoModeloAusentesViramUnavailableVindoDoProcessoVision() = runBlocking {
        val (file, image) = blankCrop()
        val missing = OcrTestSupport.visionModel(OcrModelFiles("/nao/det.onnx", "/nao/rec.onnx", "/nao/dict.txt"))
        assertEquals(OcrResult.Unavailable, client(missing).recognize(file, image.width, image.height))
        val pid = visionPid()
        assertTrue("o :vision não subiu (processos: ${context.getSystemService(ActivityManager::class.java).runningAppProcesses?.map { it.processName }})", pid != null)
        assertNotEquals("o OCR roda em processo próprio", Process.myPid(), pid)
    }

    @Test
    fun recorteComTamanhoQueNaoBateEBadInput() = runBlocking {
        val (file, image) = blankCrop()
        val r = client(OcrTestSupport.visionModel(dummyFiles())).recognize(file, image.width + 1, image.height) as OcrResult.Failed
        assertEquals(OcrFailure.BAD_INPUT, r.kind)
    }

    @Test
    fun semRamParaAEstimativaEFailedOomSemCarregarNada() = runBlocking {
        val (file, image) = blankCrop()
        val model = OcrTestSupport.visionModel(dummyFiles(), ramEstimateMb = Int.MAX_VALUE)
        val r = client(model).recognize(file, image.width, image.height) as OcrResult.Failed
        assertEquals(OcrFailure.OOM, r.kind)
    }

    @Test
    fun recorteSomeNoEpilogoDoFluxo() {
        val (file, _) = blankCrop()
        assertTrue(file.isFile)
        assertTrue(VisionCache.clear(context) >= 1)
        assertFalse(file.exists())
        assertFalse(VisionCache.dir(context).exists() && VisionCache.dir(context).listFiles().orEmpty().isNotEmpty())
    }

    // --- com o modelo real (opt-in) ---

    @Test
    fun textoDeVerdadePeloProcessoVisionSemRedeEComPicoDeMemoria() = runBlocking {
        val files = OcrTestSupport.primaryModel(context, VisionServiceTest::class.java)
        assumeNotNull("opt-in: peça a classe em -e class, ou -e ocrModelE2E true / ocrModelDir=<pasta>", files)
        val sample = OcrTestSupport.synthetic().first { it.name == "pt-20px-claro" }
        val file = VisionCache.writeCrop(context, sample.image)
        val c = client(OcrTestSupport.visionModel(files!!, idleUnloadMs = 30_000))
        val result = c.recognize(file, sample.image.width, sample.image.height)
        assertTrue("esperava texto, veio $result (StrictMode de rede mataria o processo: PROCESS_DIED)", result is OcrResult.Text)
        val cer = OcrTestSupport.cer((result as OcrResult.Text).text, sample.expected)
        assertTrue("CER $cer em \"${result.text}\"", cer <= 0.10)
        assertTrue("o serviço informou o pico de RSS: ${c.lastStats}", c.lastStats.peakRssKb > 0)
        assertTrue(c.lastStats.boxes >= 4)
    }

    @Test
    fun matarOProcessoNoMeioDevolvePROCESS_DIEDEODemaisUsoLigaDeNovo() = runBlocking {
        val files = OcrTestSupport.primaryModel(context, VisionServiceTest::class.java)
        assumeNotNull("opt-in: peça a classe em -e class, ou -e ocrModelE2E true / ocrModelDir=<pasta>", files)
        val model = OcrTestSupport.visionModel(files!!)
        val big = OcrTestSupport.screen(720, 1600)
        val file = VisionCache.writeCrop(context, big.image)
        val c = client(model)
        val pending = async { c.recognize(file, big.image.width, big.image.height) }
        // Espera o :vision aparecer e começar o trabalho, e o mata (como o lmkd ou um crash nativo).
        var pid: Int? = null
        for (i in 0 until 100) {
            pid = visionPid()
            if (pid != null) break
            delay(50)
        }
        assertTrue("o :vision não subiu", pid != null)
        delay(400)
        Process.killProcess(pid!!)
        val r = pending.await()
        assertTrue("esperava PROCESS_DIED, veio $r", r is OcrResult.Failed && r.kind == OcrFailure.PROCESS_DIED)
        // O próximo uso liga um processo novo e funciona.
        val sample = OcrTestSupport.synthetic().first { it.name == "en-24px-escuro" }
        val again = VisionCache.writeCrop(context, sample.image)
        val ok = c.recognize(again, sample.image.width, sample.image.height)
        assertTrue("depois da morte o OCR volta: $ok", ok is OcrResult.Text)
        assertNotEquals(pid, visionPid())
    }

    @Test
    fun cancelarACorrotinaMandaCancelAoServicoEOProximoUsoFunciona() = runBlocking {
        val files = OcrTestSupport.primaryModel(context, VisionServiceTest::class.java)
        assumeNotNull("opt-in: peça a classe em -e class, ou -e ocrModelE2E true / ocrModelDir=<pasta>", files)
        val c = client(OcrTestSupport.visionModel(files!!))
        val big = OcrTestSupport.screen(1080, 2400)
        val file = VisionCache.writeCrop(context, big.image)
        val job = async { c.recognize(file, big.image.width, big.image.height) }
        delay(300)
        job.cancel()
        val sample = OcrTestSupport.synthetic().first { it.name == "es-20px-claro" }
        val small = VisionCache.writeCrop(context, sample.image)
        val ok = c.recognize(small, sample.image.width, sample.image.height)
        assertTrue("o serviço seguiu preso ao reconhecimento cancelado: $ok", ok is OcrResult.Text)
    }
}
