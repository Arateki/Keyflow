package com.keyflow.data.models

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Estado da rede para quem baixa: sem rede, rede medida (dados móveis) ou livre. */
enum class NetState { OK, METERED, NONE }

fun interface NetworkProbe {
    fun state(): NetState
}

/** O que a fila avisa para fora: progresso (notificação), fim da fila e arquivo que terminou (instalar). */
interface DownloadListener {
    /** [waiting] não nulo: parado esperando algo (rede, Wi-Fi, nova tentativa). */
    fun onProgress(title: String, modelId: String, done: Long, total: Long, waiting: PauseReason?) {}
    fun onIdle() {}

    /** Um arquivo do modelo [modelId] terminou, bem ou mal — hora de reconciliar. */
    fun onFileFinished(modelId: String) {}
}

/**
 * Fila de downloads do app, no lugar do DownloadManager (mesmo contrato do [DownloadGateway], então o
 * [ModelDownloader] não muda). Motivo da troca: o DownloadManager tenta o IPv6 primeiro e espera cada
 * tentativa estourar — em rede com IPv6 quebrado, minutos em 0 bytes (medido na do Yan, 2026-09-27).
 * Aqui o [HttpFileFetcher] tenta o IPv4 primeiro.
 *
 * Um arquivo por vez, numa thread própria. A fila fica em [stateFile]: se o processo morrer, a próxima
 * instância retoma do `.part` que ficou (Range). Puro (JVM): rede e avisos entram por [network] e
 * [listener] — no app, `ConnectivityManager` e o `ModelDownloadService` (notificação).
 *
 * O modelo de cada pedido é a pasta do destino (`model-tmp/<id do modelo>/arquivo`).
 */
class HttpDownloadGateway(
    private val stateFile: File,
    private val fetcher: HttpFileFetcher = HttpFileFetcher(),
    private val network: NetworkProbe = NetworkProbe { NetState.OK },
    private val listener: DownloadListener = object : DownloadListener {},
    private val waitMs: Long = 3_000,
    private val maxAttempts: Int = 6,
) : DownloadGateway {

    private class Job(
        val id: Long,
        val url: String,
        val destination: File,
        val title: String,
        val allowMetered: Boolean,
        var status: RowStatus = RowStatus.PENDING,
        var pauseReason: PauseReason? = null,
        var attempts: Int = 0,
    ) {
        @Volatile var removed = false
        val part: File get() = File(destination.path + PART_SUFFIX)
        val modelId: String get() = destination.parentFile?.name.orEmpty()
    }

    private val lock = Object()
    private val jobs = LinkedHashMap<Long, Job>()
    private var nextId = 1L
    private var worker: Thread? = null
    @Volatile private var shutdown = false

    init {
        load()
        synchronized(lock) { if (jobs.values.any { it.isWaiting() }) ensureWorker() }
    }

    override fun enqueue(url: String, destination: File, title: String, allowMetered: Boolean): Long =
        synchronized(lock) {
            val job = Job(nextId++, url, destination, title, allowMetered)
            jobs[job.id] = job
            save()
            ensureWorker()
            lock.notifyAll()
            job.id
        }

    override fun query(ids: List<Long>): Map<Long, DownloadRow> = synchronized(lock) {
        // Quem consulta é a UI aberta: bom momento para retomar uma fila que o processo largou.
        if (jobs.values.any { it.isWaiting() }) ensureWorker()
        ids.associateWith { id ->
            val job = jobs[id] ?: return@associateWith DownloadRow(id, RowStatus.FAILED, 0)
            val bytes = when (job.status) {
                RowStatus.SUCCESSFUL -> job.destination.length()
                else -> job.part.takeIf { it.isFile }?.length() ?: 0L
            }
            DownloadRow(id, job.status, bytes, job.pauseReason.takeIf { job.status == RowStatus.PAUSED })
        }
    }

    override fun remove(ids: List<Long>) {
        val removed = synchronized(lock) {
            ids.mapNotNull { jobs.remove(it) }.onEach { it.removed = true }.also {
                save()
                lock.notifyAll()
            }
        }
        removed.forEach { job ->
            job.part.delete()
            job.destination.delete()
        }
    }

    /** Para a thread sem apagar nada — como o processo morrendo (só para testes). */
    internal fun shutdownForTest() {
        shutdown = true
        synchronized(lock) { lock.notifyAll() }
        worker?.join(5_000)
    }

    private fun Job.isWaiting() = status == RowStatus.PENDING || status == RowStatus.PAUSED || status == RowStatus.RUNNING

    private fun ensureWorker() {
        if (shutdown || worker?.isAlive == true) return
        worker = Thread(::work, "keyflow-model-download").apply {
            isDaemon = true
            start()
        }
    }

    private fun work() {
        while (!shutdown) {
            val job = synchronized(lock) { jobs.values.firstOrNull { it.isWaiting() } }
            if (job == null) {
                listener.onIdle()
                synchronized(lock) {
                    // Só encerra se nada chegou enquanto avisava; senão segue.
                    if (jobs.values.none { it.isWaiting() }) {
                        worker = null
                        return
                    }
                }
                continue
            }
            val net = network.state()
            val waitingFor = when {
                net == NetState.NONE -> PauseReason.WAITING_FOR_NETWORK
                net == NetState.METERED && !job.allowMetered -> PauseReason.WAITING_FOR_WIFI
                else -> null
            }
            if (waitingFor != null) {
                setStatus(job, RowStatus.PAUSED, waitingFor)
                listener.onProgress(job.title, job.modelId, job.part.length(), -1, waitingFor)
                pause(waitMs)
                continue
            }
            setStatus(job, RowStatus.RUNNING, null)
            val before = job.part.length()
            val result = fetcher.fetch(job.url, job.part, isCancelled = { job.removed || shutdown }) { done, total ->
                listener.onProgress(job.title, job.modelId, done, total, null)
            }
            when (result) {
                FetchResult.Done -> {
                    job.destination.delete()
                    if (job.part.renameTo(job.destination)) {
                        setStatus(job, RowStatus.SUCCESSFUL, null)
                    } else {
                        setStatus(job, RowStatus.FAILED, null)
                    }
                    listener.onFileFinished(job.modelId)
                }
                FetchResult.Cancelled -> if (job.removed) job.part.delete() // desligando: fica para a próxima
                is FetchResult.Failed -> {
                    setStatus(job, RowStatus.FAILED, null)
                    listener.onFileFinished(job.modelId)
                }
                is FetchResult.Retry -> {
                    // Tentativa que avançou não conta: só desiste de quem não sai do lugar.
                    job.attempts = if (job.part.length() > before) 0 else job.attempts + 1
                    if (job.attempts >= maxAttempts) {
                        setStatus(job, RowStatus.FAILED, null)
                        listener.onFileFinished(job.modelId)
                    } else {
                        setStatus(job, RowStatus.PAUSED, PauseReason.WAITING_TO_RETRY)
                        listener.onProgress(job.title, job.modelId, job.part.length(), -1, PauseReason.WAITING_TO_RETRY)
                        pause(waitMs * (1L shl (job.attempts - 1).coerceIn(0, 4)))
                    }
                }
            }
        }
    }

    private fun setStatus(job: Job, status: RowStatus, reason: PauseReason?) = synchronized(lock) {
        if (job.removed) return@synchronized
        val changed = job.status != status || job.pauseReason != reason
        job.status = status
        job.pauseReason = reason
        if (changed) save()
    }

    /** Espera [ms], acordando antes se a fila mudar (pedido novo ou removido). */
    private fun pause(ms: Long) = synchronized(lock) {
        if (!shutdown) lock.wait(ms)
    }

    private fun load() {
        if (!stateFile.isFile) return
        val json = runCatching { JSONObject(stateFile.readText()) }.getOrNull() ?: return
        nextId = json.optLong("nextId", 1L)
        val list = json.optJSONArray("jobs") ?: return
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val status = runCatching { RowStatus.valueOf(o.getString("status")) }.getOrDefault(RowStatus.PENDING)
            val job = Job(
                id = o.getLong("id"),
                url = o.getString("url"),
                destination = File(o.getString("destination")),
                title = o.optString("title"),
                allowMetered = o.optBoolean("allowMetered"),
                // Estava baixando quando o processo morreu: volta para a fila, retomando do `.part`.
                status = if (status == RowStatus.RUNNING) RowStatus.PENDING else status,
                attempts = o.optInt("attempts"),
            )
            jobs[job.id] = job
        }
    }

    private fun save() {
        val list = JSONArray()
        jobs.values.forEach { job ->
            list.put(
                JSONObject()
                    .put("id", job.id)
                    .put("url", job.url)
                    .put("destination", job.destination.path)
                    .put("title", job.title)
                    .put("allowMetered", job.allowMetered)
                    .put("status", job.status.name)
                    .put("attempts", job.attempts),
            )
        }
        stateFile.parentFile?.mkdirs()
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(JSONObject().put("nextId", nextId).put("jobs", list).toString())
        tmp.renameTo(stateFile)
    }

    companion object {
        const val PART_SUFFIX = ".part"
    }
}
