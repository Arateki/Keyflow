package com.keyflow.vision.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.data.models.DownloadState
import com.keyflow.data.models.HttpDownloadGateway
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelDownloader
import com.keyflow.data.models.ModelStore
import com.keyflow.data.models.StartResult
import com.keyflow.onnx.OnnxOptions
import com.keyflow.onnx.OnnxSession
import com.keyflow.onnx.Tensor
import com.keyflow.vision.client.VisionModels
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Corpus sintético, CER e instalação do modelo real para os testes instrumentados do OCR (VC-T6). */
object OcrTestSupport {

    // --- corpus -------------------------------------------------------------------------------------------------------------

    class Sample(val name: String, val group: String, val image: RgbaImage, val expected: String)

    private class Style(val name: String, val sizePx: Float, val dark: Boolean, val typeface: Typeface? = null)

    private val groups: Map<String, List<String>> = linkedMapOf(
        "pt" to listOf(
            "A transcrição acontece no próprio aparelho, sem enviar áudio.",
            "Configurações avançadas: ação, atenção, coração e opção.",
            "São Paulo, João e José chegaram às 17h45 de ontem.",
            "Você pode copiar o texto da captura e colar em qualquer lugar.",
        ),
        "en" to listOf(
            "The quick brown fox jumps over the lazy dog.",
            "Open Settings, then tap Keyboard and choose KeyFlow.",
            "Version 2.4.1 was released on March 3, 2026.",
            "Your voice is transcribed on this device only.",
        ),
        "es" to listOf(
            "¿Dónde está la biblioteca? Mañana tendremos reunión.",
            "El niño comió piñas y jalapeños en el año nuevo.",
            "¡Hola! Configuración de teclado y voz en español.",
            "La señal es débil, intenta de nuevo en un momento.",
        ),
        "code" to listOf(
            "val text = recognizer.read(image)",
            "fun main(args: Array<String>) {",
            "if (count >= 10 && ready) { return }",
            "https://keyflow.arateki.com/docs?id=42",
        ),
        "ui" to listOf(
            "Wi-Fi 5 GHz · 87% · 12:45",
            "Saldo: R\$ 1.234,56   Total: 45,90",
            "Mensagem enviada para Maria Souza",
            "Bateria: 64% - Modo economia ativado",
        ),
    )

    private val styles = listOf(
        Style("12px-claro", 12f, dark = false),
        Style("14px-escuro", 14f, dark = true),
        Style("16px-claro", 16f, dark = false),
        Style("20px-claro", 20f, dark = false),
        Style("24px-escuro", 24f, dark = true),
        Style("32px-claro", 32f, dark = false),
    )

    private fun typefaceFor(group: String, style: Style): Typeface = when {
        group == "code" -> Typeface.MONOSPACE
        style.typeface != null -> style.typeface
        else -> Typeface.DEFAULT
    }

    /** ≥ 20 imagens: 5 grupos de texto x 6 estilos (12–32 px, fundo claro e escuro, monoespaçada no código) = 30. */
    fun synthetic(width: Int = 720): List<Sample> = groups.flatMap { (group, lines) ->
        styles.map { style -> render(group, style, lines, width) }
    }

    private fun render(group: String, style: Style, lines: List<String>, width: Int): Sample {
        val margin = 16
        val lineHeight = (style.sizePx * 1.5f).toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = style.sizePx
            typeface = typefaceFor(group, style)
            color = if (style.dark) Color.parseColor("#E8E8E8") else Color.parseColor("#111111")
        }
        // Quebra em palavras para nenhuma linha passar da largura (a 32 px a frase inteira não cabe em 720 px; cortada na borda, a
        // verdade do corpus deixaria de ser o que está desenhado). A verdade segue sendo as frases, em ordem, separadas por espaço.
        val drawn = lines.flatMap { wrap(it, paint, (width - 2 * margin).toFloat()) }
        val height = margin * 2 + lineHeight * drawn.size
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (style.dark) Color.parseColor("#121212") else Color.WHITE)
        drawn.forEachIndexed { i, line ->
            canvas.drawText(line, margin.toFloat(), margin + lineHeight * (i + 0.75f), paint)
        }
        return Sample("$group-${style.name}", group, toRgba(bitmap), lines.joinToString(" "))
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        var current = StringBuilder()
        for (word in text.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                out += current.toString()
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** Uma "tela" de [width]x[height]: as linhas do corpus repetidas em colunas de texto de [sizePx] px, de cima a baixo. */
    fun screen(width: Int, height: Int, sizePx: Float = 16f, dark: Boolean = false): Sample {
        val all = groups.filterKeys { it != "code" }.values.flatten()
        val lineHeight = (sizePx * 1.6f).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (dark) Color.parseColor("#121212") else Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            color = if (dark) Color.parseColor("#E8E8E8") else Color.parseColor("#111111")
        }
        val drawn = ArrayList<String>()
        var y = 48f
        var i = 0
        while (y < height - 32) {
            // Um parágrafo de 4 frases, depois uma lacuna de 2 linhas (bloco); a frase que não cabe na largura quebra em palavras.
            for (line in wrap(all[i % all.size], paint, width - 48f)) {
                if (y >= height - 32) break
                canvas.drawText(line, 24f, y, paint)
                drawn += line
                y += lineHeight
            }
            i++
            if (i % 4 == 0) y += lineHeight * 2
        }
        return Sample("tela-${width}x$height", "screen", toRgba(bitmap), drawn.joinToString(" "))
    }

    fun toRgba(bitmap: Bitmap): RgbaImage {
        val buffer = ByteBuffer.allocate(bitmap.width * bitmap.height * 4)
        bitmap.copyPixelsToBuffer(buffer)
        return RgbaImage(bitmap.width, bitmap.height, buffer.array())
    }

    /** Capturas reais (PNG + `nome.txt` com a verdade) em [dir], se houver — para rodar nos aparelhos. */
    fun realCaptures(dir: File?): List<Sample> {
        if (dir == null || !dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.extension.equals("png", true) }.orEmpty().sortedBy { it.name }.mapNotNull { png ->
            val bitmap = BitmapFactory.decodeFile(png.path)?.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
                ?: return@mapNotNull null
            val truth = File(png.parentFile, png.nameWithoutExtension + ".txt").takeIf { it.isFile }?.readText().orEmpty()
            Sample("real-${png.nameWithoutExtension}", "real", toRgba(bitmap), truth)
        }
    }

    // --- CER ----------------------------------------------------------------------------------------------------------------

    /** Espaços e quebras de linha contam como um espaço só e as pontas somem: layout não é erro de leitura. */
    fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** Distância de edição entre os caracteres (code points). */
    fun editDistance(a: String, b: String): Int {
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        var prev = IntArray(y.size + 1) { it }
        var cur = IntArray(y.size + 1)
        for (i in 1..x.size) {
            cur[0] = i
            for (j in 1..y.size) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[y.size]
    }

    /** Taxa de erro de caracteres de [predicted] contra [expected] (ambos normalizados); 0 = idêntico, pode passar de 1. */
    fun cer(predicted: String, expected: String): Double {
        val e = normalize(expected)
        if (e.isEmpty()) return if (normalize(predicted).isEmpty()) 0.0 else 1.0
        return editDistance(normalize(predicted), e).toDouble() / e.codePointCount(0, e.length)
    }

    // --- modelo real --------------------------------------------------------------------------------------------------------

    private val args get() = InstrumentationRegistry.getArguments()

    /**
     * Quem pode usar o modelo real (opt-in, como o Whisper e o LLM do projeto): `ocrModelDir=<pasta>`, `ocrModelDownload=true`,
     * `ocrModelE2E=true` (o CI liga) ou o pedido EXPLÍCITO da classe do teste em `-e class …` — é o que permite
     * `pool instrumentar com.keyflow.vision.ocr.OcrMeasurementTest`, que não leva argumentos. Sem nenhum deles, o teste vira `assume`.
     */
    fun optedIn(requester: Class<*>): Boolean =
        args.getString("ocrModelDir") != null || args.getString("ocrModelDownload") == "true" || args.getString("ocrModelE2E") == "true" ||
            args.getString("class").orEmpty().split(',').any { it.substringBefore('#') == requester.name }

    /**
     * Os três arquivos do OCR do catálogo, ou `null` (o teste vira `assume`). Fontes, na ordem: `ocrModelDir=<pasta com det.onnx,
     * rec.onnx, dict.txt>` (legível pelo app, ex.: `files/ocr-models` empurrado com `run-as`); senão, se [optedIn], baixa pelo
     * downloader do app (confere tamanho e SHA-256 do catálogo no aparelho) para `files/models-ocr-e2e`.
     */
    fun primaryModel(context: Context, requester: Class<*>): OcrModelFiles? {
        args.getString("ocrModelDir")?.let { return filesIn(File(it)) }
        if (!optedIn(requester)) return null
        val spec = ModelCatalog.OCR_PADDLE_LATIN
        val store = ModelStore(File(context.filesDir, "models-ocr-e2e"))
        if (!store.isInstalled(spec.id)) {
            val downloader = ModelDownloader(
                HttpDownloadGateway(File(context.filesDir, "model-downloads-ocr-e2e.json")),
                File(context.getExternalFilesDir(null), "model-tmp-ocr-e2e"),
                store,
                isMetered = { false },
            )
            assertEquals(StartResult.Started, downloader.start(spec, allowMetered = true))
            val deadline = System.currentTimeMillis() + 10 * 60_000
            while (true) {
                val state = downloader.snapshot(spec.id)?.state
                if (state == DownloadState.FILES_DONE) break
                assertTrue("download falhou: $state", state != DownloadState.FAILED)
                assertTrue("download passou de 10 min", System.currentTimeMillis() < deadline)
                Thread.sleep(500)
            }
            downloader.finish(spec.id)
        }
        assertTrue("o OCR não instalou (SHA-256/tamanho do catálogo?)", store.isInstalled(spec.id))
        return filesIn(store.dirFor(spec.id))
    }

    /**
     * O par alternativo (plano B do design: `deepghs/paddleocr`, `license: other`), SÓ para a medição: `ocrAltDir=<pasta>` com os mesmos
     * três nomes ou, sem ele, baixado aqui (pins abaixo, conferidos por SHA-256) para o cache do teste. Nunca entra no catálogo daqui.
     */
    fun alternativeModel(context: Context): OcrModelFiles? {
        args.getString("ocrAltDir")?.let { return filesIn(File(it)) }
        val dir = File(context.cacheDir, "ocr-alt").apply { mkdirs() }
        return try {
            for ((name, pin) in ALT_PINS) {
                val file = File(dir, name)
                if (file.isFile && sha256(file) == pin.first) continue
                val connection = java.net.URL("$ALT_BASE/${pin.third}").openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                if (sha256(file) != pin.first) {
                    file.delete()
                    return null
                }
            }
            filesIn(dir)
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun sha256(file: File): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private const val ALT_BASE = "https://huggingface.co/deepghs/paddleocr/resolve/e7bd63d9c674c96bd4fdab435af0efb383919c18"

    /** nome local → (SHA-256, bytes, caminho no repositório). Conferidos baixando os arquivos em 2026-10-27. */
    private val ALT_PINS = mapOf(
        "det.onnx" to Triple("30a86f5731181461d08021402766601e4302a9b9b9666be8aff402696339cdff", 4_745_517L, "det/ch_PP-OCRv4_det/model.onnx"),
        "rec.onnx" to Triple("f26feb219f30a5bf6cc8e8e69e5d05dae9114f10c657245747e5629edfacd0ad", 8_989_686L, "rec/latin_PP-OCRv3_rec/model.onnx"),
        "dict.txt" to Triple("8e6d4e3629788c35c31f7e530287d6147b549bb7a265bd6708bb281134429e2c", 468L, "rec/latin_PP-OCRv3_rec/dict.txt"),
    )

    private fun filesIn(dir: File): OcrModelFiles? =
        OcrModelFiles(File(dir, "det.onnx").path, File(dir, "rec.onnx").path, File(dir, "dict.txt").path).takeIf { it.missing().isEmpty() }

    /** O modelo do catálogo como o `:vision` o recebe, apontando para os [files] (a pasta de teste, não a loja real do app). */
    fun visionModel(files: OcrModelFiles, idleUnloadMs: Long = 0, ramEstimateMb: Int = VisionModels.RAM_ESTIMATE_MB) =
        com.keyflow.vision.service.VisionModel(
            modelId = "ocr-e2e", files = files, idleUnloadMs = idleUnloadMs, ramEstimateMb = ramEstimateMb,
            threads = OnnxSession.DEFAULT_THREADS, version = "e2e",
        )

    /** Sessões abertas uma vez e emprestadas a vários motores com parâmetros diferentes (o `close` do motor não as fecha). */
    class SharedSessions(private val files: OcrModelFiles, options: OnnxOptions) : AutoCloseable {
        private val det = OnnxSession.open(files.detector, options)
        private val rec = OnnxSession.open(files.recognizer, options)

        private class Borrowed(private val inner: OnnxSession) : OnnxSession {
            override val inputNames get() = inner.inputNames
            override val outputNames get() = inner.outputNames
            override fun run(inputs: List<Tensor>) = inner.run(inputs)
            override fun close() = Unit
        }

        fun opener(): (String, OnnxOptions) -> OnnxSession = { path, _ -> Borrowed(if (path == files.detector) det else rec) }

        override fun close() {
            det.close()
            rec.close()
        }
    }
}
