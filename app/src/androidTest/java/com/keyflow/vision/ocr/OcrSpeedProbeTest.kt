package com.keyflow.vision.ocr

import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.onnx.FloatTensor
import com.keyflow.onnx.OnnxOptions
import com.keyflow.onnx.OnnxSession
import java.io.File
import java.util.Random
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sonda de velocidade do ONNX Runtime com o PaddleOCR (VC-T6): o tempo de UMA inferência do reconhecedor e do detector com tensores
 * aleatórios do tamanho do uso real, variando threads e o alocador em arena, e o RSS do processo depois. Serve para escolher o
 * [OnnxOptions] do `:vision`. Opt-in só por pedido explícito da classe (`pool instrumentar com.keyflow.vision.ocr.OcrSpeedProbeTest`).
 */
@RunWith(AndroidJUnit4::class)
class OcrSpeedProbeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun log(line: String) {
        Log.i("OcrProbe", line)
        instrumentation.sendStatus(0, Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n$line") })
    }

    private fun rssKb(): Long = runCatching {
        File("/proc/self/status").useLines { l -> l.first { it.startsWith("VmRSS:") }.removePrefix("VmRSS:").trim().removeSuffix("kB").trim().toLong() }
    }.getOrDefault(-1L)

    private fun tensor(vararg shape: Long): FloatTensor {
        val n = shape.fold(1L) { a, b -> a * b }.toInt()
        val r = Random(7)
        return FloatTensor(shape, FloatArray(n) { r.nextFloat() * 2f - 1f })
    }

    private fun median(session: OnnxSession, input: FloatTensor, runs: Int = 5): Long {
        session.run(listOf(input)) // aquecimento
        val times = (1..runs).map {
            val t = System.nanoTime()
            session.run(listOf(input))
            (System.nanoTime() - t) / 1_000_000
        }.sorted()
        return times[times.size / 2]
    }

    @Test
    fun sonda() {
        assumeTrue(
            "opt-in: peça esta classe",
            InstrumentationRegistry.getArguments().getString("class").orEmpty().split(',')
                .any { it.substringBefore('#') == OcrSpeedProbeTest::class.java.name },
        )
        val files = OcrTestSupport.primaryModel(context, OcrSpeedProbeTest::class.java)
        assumeNotNull("sem o modelo", files)
        if (files == null) return
        log("== sonda de velocidade · ${android.os.Build.MODEL} · ${android.os.Build.SUPPORTED_ABIS.first()} · ${Runtime.getRuntime().availableProcessors()} núcleos · RSS inicial ${rssKb()} kB")
        for (arena in listOf(false, true)) for (threads in listOf(1, 2, 4)) {
            val options = OnnxOptions(threads = threads, cpuArena = arena)
            val rec = OnnxSession.open(files.recognizer, options)
            val det = OnnxSession.open(files.detector, options)
            try {
                val rec1 = median(rec, tensor(1, 3, 48, 480))
                val rec1long = median(rec, tensor(1, 3, 48, 1200))
                val rec6 = median(rec, tensor(6, 3, 48, 600))
                val d = median(det, tensor(1, 3, 960, 736), runs = 3)
                log("  arena=$arena threads=$threads: rec 1x48x480 ${rec1} ms · rec 1x48x1200 ${rec1long} ms · rec 6x48x600 ${rec6} ms · det 960x736 ${d} ms · RSS ${rssKb()} kB")
            } finally {
                rec.close()
                det.close()
            }
        }
    }
}
