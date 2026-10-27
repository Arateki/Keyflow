package com.keyflow.vision.service

import com.keyflow.vision.ocr.OcrEngine
import com.keyflow.vision.ocr.OcrFailure
import com.keyflow.vision.ocr.OcrResult
import com.keyflow.vision.ocr.OcrStats
import com.keyflow.vision.ocr.RgbaImage
import java.io.File
import java.util.concurrent.Executor

/** Agenda a descarga por ociosidade (MDL-04). Falso em teste; `ScheduledExecutorService` no serviço. */
internal interface IdleScheduler {
    fun schedule(delayMs: Long, block: () -> Unit): Cancellable
}

internal fun interface Cancellable {
    fun cancel()
}

/**
 * O núcleo do processo `:vision`, sem Android (design §6.5): um reconhecimento por vez, os modelos carregam sob demanda, trocar de
 * modelo descarrega o anterior, a guarda de RAM barra o que não cabe e um motor ocioso é descarregado. Motor, memória, relógio de
 * ociosidade e executor entram por parâmetro — é isso que o torna testável na JVM. Segue o [com.keyflow.reasoning.local.ReasoningServiceCore].
 *
 * O estado (`engine`, `loadedId`, `busy`…) é tocado só na thread do [executor] (de uma thread só); [cancel] é a única entrada de
 * outra thread. O arquivo do recorte NÃO é apagado aqui: quem o escreveu o apaga no epílogo do fluxo (CAP-04).
 */
internal class VisionServiceCore(
    private val engineFor: (VisionModel) -> OcrEngine,
    private val availableMb: () -> Long?,
    private val scheduler: IdleScheduler,
    private val executor: Executor,
    private val readCrop: (path: String, width: Int, height: Int) -> RgbaImage = { path, w, h -> RgbaImage.readRaw(File(path), w, h) },
    /** Pico de RSS do processo (kB) desde o último [resetPeak]; 0 se o sistema não informa. */
    private val peakRssKb: () -> Long = { 0L },
    private val resetPeak: () -> Unit = {},
) {
    interface Listener {
        fun onStats(stats: OcrStats)
        fun onResult(result: OcrResult)
    }

    private var engine: OcrEngine? = null
    private var loadedId: String? = null
    private var loadedVersion: String = ""
    private var busy = false
    private var idleTimer: Cancellable? = null

    /** Sobe a cada uso; um timer de ociosidade só vale se ainda tem o número da última vez que o motor foi usado. */
    private var useToken = 0L

    @Volatile private var cancelled = false
    @Volatile private var running: OcrEngine? = null

    fun recognize(model: VisionModel, cropPath: String, width: Int, height: Int, listener: Listener) {
        executor.execute { process(model, cropPath, width, height, listener) }
    }

    /** Interrompe o reconhecimento em curso. Sem nenhum em curso, só silencia o próximo já enfileirado. */
    fun cancel() {
        cancelled = true
        running?.cancel()
    }

    /** Descarrega na fila do executor: nunca solta o motor no meio de um reconhecimento. */
    fun shutdown() {
        executor.execute {
            idleTimer?.cancel()
            idleTimer = null
            unloadCurrent()
        }
    }

    private fun process(model: VisionModel, cropPath: String, width: Int, height: Int, listener: Listener) {
        cancelled = false
        busy = true
        idleTimer?.cancel()
        idleTimer = null
        useToken++
        try {
            if (model.files.missing().isNotEmpty()) {
                if (!cancelled) listener.onResult(OcrResult.Unavailable)
                return
            }
            val image = try {
                readCrop(cropPath, width, height)
            } catch (e: IllegalArgumentException) {
                if (!cancelled) listener.onResult(OcrResult.Failed(OcrFailure.BAD_INPUT, e.message.orEmpty()))
                return
            } catch (e: java.io.IOException) {
                if (!cancelled) listener.onResult(OcrResult.Failed(OcrFailure.BAD_INPUT, "não li o recorte: ${e.message}"))
                return
            }
            val active = ensureEngine(model)
            if (active == null) {
                if (!cancelled) {
                    listener.onResult(
                        OcrResult.Failed(
                            OcrFailure.OOM,
                            "memória insuficiente para o OCR: ${availableMb()} MB livres, estimativa de ${model.ramEstimateMb} MB",
                        ),
                    )
                }
                return
            }
            if (cancelled) return
            running = active
            resetPeak()
            val result = active.recognize(image)
            if (cancelled) return
            listener.onStats(active.lastStats.copy(peakRssKb = peakRssKb()))
            listener.onResult(result)
        } catch (e: OutOfMemoryError) {
            unloadCurrent()
            if (!cancelled) listener.onResult(OcrResult.Failed(OcrFailure.OOM, "memória esgotada no OCR"))
        } catch (e: Exception) {
            if (!cancelled) listener.onResult(OcrResult.Failed(OcrFailure.ENGINE, e.message ?: e.toString()))
        } finally {
            running = null
            busy = false
            scheduleIdleUnload(model)
        }
    }

    /** O motor para [model], criando-o (e descarregando o anterior) se preciso; `null` se a RAM livre não cobre a estimativa. */
    private fun ensureEngine(model: VisionModel): OcrEngine? {
        engine?.let { if (isLoaded(model)) return it }
        // Libera o anterior antes de medir: a troca não precisa caber junto com ele.
        unloadCurrent()
        val available = availableMb()
        if (!enoughRam(available, model.ramEstimateMb)) return null
        val created = engineFor(model)
        engine = created
        loadedId = model.modelId
        loadedVersion = model.version
        return created
    }

    private fun unloadCurrent() {
        engine?.let { runCatching { it.close() } }
        engine = null
        loadedId = null
        loadedVersion = ""
    }

    private fun isLoaded(model: VisionModel): Boolean = engine != null && loadedId == model.modelId && loadedVersion == model.version

    private fun scheduleIdleUnload(model: VisionModel) {
        if (model.idleUnloadMs <= 0 || engine == null) return
        val token = useToken
        idleTimer = scheduler.schedule(model.idleUnloadMs) { executor.execute { onIdle(token) } }
    }

    private fun onIdle(token: Long) {
        // Timer velho (o motor foi usado de novo) ou reconhecimento em curso: não descarrega.
        if (token != useToken || busy) return
        unloadCurrent()
    }

    companion object {
        /** Sem dado do sistema (nulo) ou estimativa zero não se bloqueia — o `OutOfMemoryError` do caminho ainda é capturado. */
        fun enoughRam(availableMb: Long?, estimateMb: Int): Boolean = estimateMb <= 0 || availableMb == null || availableMb >= estimateMb
    }
}
