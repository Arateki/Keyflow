package com.keyflow.vision.service

import com.keyflow.vision.ocr.OcrEngine
import com.keyflow.vision.ocr.OcrFailure
import com.keyflow.vision.ocr.OcrResult
import com.keyflow.vision.ocr.OcrStats
import com.keyflow.vision.ocr.RgbaImage
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

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
 * outra thread e só mexe num contador atômico. O arquivo do recorte NÃO é apagado aqui: quem o escreveu o apaga no epílogo do fluxo (CAP-04).
 */
internal class VisionServiceCore(
    private val engineFor: (VisionModel) -> OcrEngine,
    private val availableMb: () -> Long?,
    private val scheduler: IdleScheduler,
    private val executor: Executor,
    /** Confere o recorte SEM lê-lo (teto de pixels e tamanho do arquivo): roda antes da guarda de RAM e do `readCrop`. */
    private val checkCrop: (path: String, width: Int, height: Int) -> Unit = { path, w, h -> RgbaImage.checkRaw(File(path), w, h) },
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

    /**
     * Cancelamento por ficha: cada [recognize] ganha um número na ordem de chegada (na thread de quem chama) e [cancel] cancela TODAS as
     * fichas já entregues — as que ainda esperam o executor (o trabalho nem começa e nada é entregue) e a que está rodando. Um
     * `cancelled` único, zerado quando o pedido começa, apagava o cancelamento recebido enquanto o pedido ainda estava na fila.
     */
    private val tickets = AtomicLong()
    private val cancelledUpTo = AtomicLong()

    private fun isCancelled(ticket: Long) = ticket <= cancelledUpTo.get()

    fun recognize(model: VisionModel, cropPath: String, width: Int, height: Int, listener: Listener) {
        val ticket = tickets.incrementAndGet()
        executor.execute { process(ticket, model, cropPath, width, height, listener) }
    }

    /**
     * Cancela os pedidos já entregues: os da fila não rodam nem chamam o callback e o que está rodando para na próxima checagem do
     * motor (a ficha vai ao motor como token, [OcrEngine.recognize]); o pedido que chegar depois não é afetado.
     */
    fun cancel() {
        cancelledUpTo.accumulateAndGet(tickets.get()) { a, b -> maxOf(a, b) }
    }

    /** Descarrega na fila do executor: nunca solta o motor no meio de um reconhecimento. */
    fun shutdown() {
        executor.execute {
            idleTimer?.cancel()
            idleTimer = null
            unloadCurrent()
        }
    }

    private fun process(ticket: Long, model: VisionModel, cropPath: String, width: Int, height: Int, listener: Listener) {
        // Cancelado enquanto esperava na fila: não toca em motor, arquivo nem callback.
        if (isCancelled(ticket)) return
        busy = true
        idleTimer?.cancel()
        idleTimer = null
        useToken++
        try {
            if (model.files.missing().isNotEmpty()) {
                if (!isCancelled(ticket)) listener.onResult(OcrResult.Unavailable)
                return
            }
            // 1) confere o recorte sem lê-lo; 2) guarda de RAM; 3) só então lê os bytes.
            try {
                checkCrop(cropPath, width, height)
            } catch (e: IllegalArgumentException) {
                if (!isCancelled(ticket)) listener.onResult(OcrResult.Failed(OcrFailure.BAD_INPUT, e.message.orEmpty()))
                return
            }
            val active = ensureEngine(model)
            if (active == null) {
                if (!isCancelled(ticket)) {
                    listener.onResult(
                        OcrResult.Failed(
                            OcrFailure.OOM,
                            "memória insuficiente para o OCR: ${availableMb()} MB livres, estimativa de ${model.ramEstimateMb} MB",
                        ),
                    )
                }
                return
            }
            val image = try {
                readCrop(cropPath, width, height)
            } catch (e: IllegalArgumentException) {
                if (!isCancelled(ticket)) listener.onResult(OcrResult.Failed(OcrFailure.BAD_INPUT, e.message.orEmpty()))
                return
            } catch (e: java.io.IOException) {
                if (!isCancelled(ticket)) listener.onResult(OcrResult.Failed(OcrFailure.BAD_INPUT, "não li o recorte: ${e.message}"))
                return
            }
            if (isCancelled(ticket)) return
            resetPeak()
            // O token vai ao motor: um cancel() que chegar a qualquer momento daqui em diante é visto na próxima checagem dele (e um que
            // chegou durante o resetPeak, antes da primeira, derruba o pedido sem nenhuma inferência).
            val result = active.recognize(image) { isCancelled(ticket) }
            if (isCancelled(ticket)) return
            listener.onStats(active.lastStats.copy(peakRssKb = peakRssKb()))
            listener.onResult(result)
        } catch (e: OutOfMemoryError) {
            unloadCurrent()
            if (!isCancelled(ticket)) listener.onResult(OcrResult.Failed(OcrFailure.OOM, "memória esgotada no OCR"))
        } catch (e: Exception) {
            if (!isCancelled(ticket)) listener.onResult(OcrResult.Failed(OcrFailure.ENGINE, e.message ?: e.toString()))
        } finally {
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
