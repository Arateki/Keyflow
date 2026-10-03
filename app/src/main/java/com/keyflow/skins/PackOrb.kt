package com.keyflow.skins

import android.graphics.Bitmap
import android.os.Process
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A orbe de um pacote de skin (SKP-ORB): os frames SVG de cada estado, em loop, escolhidos por `floor(tempoMs · fps / 1000) mod n`
 * ([OrbTiming.frameIndex]) — o mesmo tempo dá o mesmo frame, então o relógio congelado das prévias e dos goldens continua valendo
 * (SKP-ORB-03). Estado sem frames toca os de `idle` ([SkinPack.framesOf], SKP-ORB-02).
 *
 * **O desenho nunca espera um render** (SKP-ORB-05). Cada frame vira um `Bitmap` do tamanho exato da orbe, guardado num cache LRU com
 * teto de bytes ([CACHE_BYTES], ~16 MB, dividido por todas as orbes do processo). [frameFor] devolve o bitmap pronto; se ele ainda não
 * existe, devolve o último frame mostrado do mesmo estado (ou outro qualquer do estado) e pede o render a uma thread de fundo — junto
 * com os próximos frames (um quarto de segundo de animação), para a thread ficar à frente do relógio mesmo quando o estado inteiro não cabe no cache.
 * O pedido do frame de agora passa na frente de tudo (e promove um já enfileirado), e o que a animação já deixou para trás é descartado.
 * [prepare] pré-aquece o estado inteiro ao entrar nele. Quando um frame novo fica pronto, [version] sobe: o desenho lê esse valor, então
 * o Compose redesenha sozinho (importa com o relógio parado, em que ninguém mais pediria um quadro).
 *
 * Com [deterministic] (relógio injetado: prévias, galeria, testes) o frame que falta é renderizado **na hora**, na thread que desenha — é
 * a única maneira de o mesmo pixel sair sempre; a orbe ao vivo (relógio real) nunca entra nesse caminho.
 *
 * **Pacotes que pedem mais** (SKP-ORB-06): um pacote que cabe nos limites de antes (fps ≤ 60 e até 64 frames por estado) usa o caminho e os 16 MB
 * de sempre, sem nenhum desvio. Só o que passa deles ([extended]: fps acima de 60 ou mais de 64 frames num estado) ganha um orçamento que cresce
 * até o que o estado que toca, no lado de agora, precisa ([OrbMemoryPolicy]) — limitado pelo teto absoluto e pelo que o aparelho aguenta
 * ([OrbDeviceMemory]) — e que volta aos 16 MB quando a orbe sai do estado, muda de tamanho ou fecha.
 */
class PackOrb private constructor(private val pack: SkinPack) {

    /** O id da pasta do pacote. */
    val packId: String get() = pack.id

    /** Cor do tempo e dos glifos desenhados pelo app no centro da orbe (`orb.centerInk`), ARGB. */
    val centerInk: Int get() = pack.manifest.orb.centerInk

    private val versionState = mutableIntStateOf(0)

    /** Sobe quando um frame fica pronto em segundo plano. O desenho lê isto para ser redesenhado (estado do Compose). */
    val version: Int get() = versionState.intValue

    @Volatile
    private var closed = false

    private val lastShown = ConcurrentHashMap<OrbPackState, Bitmap>()

    // Onde a animação está em cada estado (índice do frame de agora): o que a thread de fundo usa para largar pedidos que já ficaram para trás.
    private val cursor = ConcurrentHashMap<OrbPackState, Int>()

    // Frames que falharam ao renderizar: não se tenta de novo a cada quadro.
    private val failed = ConcurrentHashMap.newKeySet<FrameKey>()

    private data class FrameKey(val owner: Int, val state: OrbPackState, val index: Int, val side: Int)

    private val ownerId = nextOwner.getAndIncrement()

    /**
     * O pacote pede mais do que o formato aceitava antes dos 120 fps (SKP-ORB-06)? Só então valem o orçamento adaptativo, o aquecimento do estado
     * inteiro e a fila maior; quem cabe nos limites de antes nunca passa por nenhum deles. Conta o ritmo e os frames de quem de fato toca em cada estado.
     */
    private val extended: Boolean = OrbPackState.entries.any { pack.fpsOf(it) > LEGACY_MAX_FPS || pack.framesOf(it).size > LEGACY_MAX_FRAMES_PER_STATE }

    private class Playback(val state: OrbPackState, val side: Int)

    // O estado e o lado que esta orbe tocou por último ([prepare]): o que o orçamento protege ao encolher e o que o aquecimento ainda quer.
    @Volatile
    private var active: Playback? = null

    /**
     * Só para os testes: chamado a cada frame renderizado, **na thread que o renderizou** (a de fundo; ou a de quem chamou, no caminho
     * determinístico) e antes de ele entrar no cache — quem a usa vê a ordem em que os frames chegam e pode segurar a thread de fundo para
     * montar a fila que quiser. Nulo fora dos testes (custa uma leitura volátil por render). Não muda nada do que a orbe faz.
     */
    @Volatile
    internal var onRendered: ((state: OrbPackState, index: Int) -> Unit)? = null

    /**
     * O bitmap do frame de [state] em [timeMs] para uma orbe de [sidePx] px de lado, ou `null` se nada do estado está pronto (a orbe
     * fica vazia por um instante — só na primeiríssima vez, antes do pré-aquecimento chegar).
     */
    fun frameFor(state: OrbPackState, timeMs: Float, sidePx: Int, deterministic: Boolean): Bitmap? {
        if (closed || sidePx <= 0) return null
        val files = pack.framesOf(state)
        if (files.isEmpty()) return null
        val effective = pack.effectiveState(state)
        val index = OrbTiming.frameIndex(timeMs, pack.fpsOf(state), files.size)
        val key = FrameKey(ownerId, effective, index, sidePx)

        cache.get(key)?.let { return remember(effective, it) }
        if (deterministic) {
            val rendered = render(key, files[index].let { pack.readSvg(it) })
            if (rendered != null) {
                cache.put(key, rendered)
                return remember(effective, rendered)
            }
            return lastShown[effective]
        }
        // Ao vivo: pede o frame de agora (urgente) e os próximos, e mostra o que já tem do estado.
        if (extended) noteDemand(sidePx, files.size)
        cursor[effective] = index
        request(key, URGENT)
        val ahead = lookaheadFor(pack.fpsOf(state), files.size)
        for (step in 1..ahead) request(key.copy(index = (index + step) % files.size), AHEAD)
        return lastShown[effective]?.takeIf { it.width == sidePx } ?: anyCached(effective, sidePx)
    }

    /**
     * Quantos frames à frente do atual a thread de fundo mantém prontos: um quarto de segundo da animação. Até 60 fps (o que o formato aceitava) são
     * entre 3 e 12, como sempre; acima disso, o quarto de segundo inteiro (`fps / 4`: 15 frames a 60+, 30 a 120 fps) — o teto de 12 faria 100 ms de
     * folga a 120 fps.
     */
    private fun lookaheadFor(fps: Int, frameCount: Int): Int {
        val quarterSecond = fps / 4
        val wanted = if (fps <= LEGACY_MAX_FPS) quarterSecond.coerceIn(MIN_LOOKAHEAD, MAX_LOOKAHEAD) else quarterSecond
        return wanted.coerceAtMost(frameCount - 1)
    }

    /**
     * Pré-aquece os frames de [state] (e só os que cabem na metade do cache) para a orbe de [sidePx] px: chamar ao entrar no estado e ao mudar o
     * tamanho. Num pacote que pede mais ([extended]) isto também reavalia o orçamento (cresce até o que o estado precisa; encolhe se o estado novo é
     * leve) e, se o estado cabe inteiro no orçamento, aquece o estado inteiro.
     */
    fun prepare(state: OrbPackState, sidePx: Int) {
        if (closed || sidePx <= 0) return
        val files = pack.framesOf(state)
        if (files.isEmpty()) return
        val effective = pack.effectiveState(state)
        val frameBytes = sidePx.toLong() * sidePx * BYTES_PER_PIXEL
        active = Playback(effective, sidePx)
        val budget = if (extended) noteDemand(sidePx, files.size, revise = true) else CACHE_BYTES
        val fit = if (extended && files.size * frameBytes <= budget) files.size else (budget / 2 / frameBytes).toInt().coerceAtLeast(1)
        for (index in 0 until minOf(files.size, fit)) request(FrameKey(ownerId, effective, index, sidePx), WARM)
    }

    /**
     * Registra quanto esta orbe precisa agora — os bytes do estado que toca, no lado de agora — e reavalia o orçamento do cache quando o pedido
     * mudou (ou sempre, com [revise]: ao entrar no estado o teto do aparelho pode ter mudado). Devolve o orçamento resultante. Só os pacotes que pedem
     * mais chegam aqui ([extended]).
     */
    private fun noteDemand(sidePx: Int, frames: Int, revise: Boolean = false): Long {
        val bytes = OrbMemoryPolicy.stateBytes(sidePx, frames)
        if (demands.put(ownerId, bytes) != bytes || revise) reviseBudget()
        return cache.maxBytes
    }

    /** Solta o que esta orbe guardou no cache e para de renderizar para ela. */
    fun close() {
        closed = true
        owners.remove(ownerId)
        lastShown.clear()
        cache.removeWhere { it.owner == ownerId }
        if (demands.remove(ownerId) != null) reviseBudget()
    }

    private fun remember(state: OrbPackState, bitmap: Bitmap): Bitmap {
        lastShown[state] = bitmap
        return bitmap
    }

    /** Qualquer frame do estado já pronto no tamanho certo (o de menor índice): melhor que a orbe sumir enquanto o certo não chega. */
    private fun anyCached(state: OrbPackState, sidePx: Int): Bitmap? {
        val count = pack.framesOf(state).size
        for (index in 0 until count) cache.get(FrameKey(ownerId, state, index, sidePx))?.let { return remember(state, it) }
        return null
    }

    private fun request(key: FrameKey, priority: Int) {
        if (key in failed || cache.contains(key)) return
        synchronized(pending) {
            val existing = pending[key]
            if (existing != null) {
                // Já pedido (na fila ou sendo renderizado agora): só sobe de prioridade — o pedido do frame de agora passa na frente.
                if (existing.priority <= priority || !queue.remove(existing)) return
            } else if (priority != URGENT && queue.size >= if (extended) EXTENDED_MAX_QUEUE else MAX_QUEUE) {
                return
            }
            val task = Task(this, key, priority, sequence.incrementAndGet())
            pending[key] = task
            queue.add(task)
        }
        ensureWorker()
    }

    private fun render(key: FrameKey, svgText: String?): Bitmap? {
        if (svgText == null) {
            failed.add(key)
            return null
        }
        val bitmap = try {
            SvgRender.toBitmap(SvgRender.parse(svgText), key.side, key.side, SvgFit.MEET)
        } catch (e: Exception) {
            Log.w(TAG, "${pack.id}: frame ${key.state.key} #${key.index + 1} não desenha (${e.message})")
            failed.add(key)
            return null
        } catch (e: OutOfMemoryError) {
            // Sem memória para um bitmap: nunca derruba a orbe. O frame fica sem render (o anterior continua na tela) e o orçamento volta aos 16 MB.
            Log.w(TAG, "${pack.id}: sem memória para o frame ${key.state.key} #${key.index + 1} (${key.side} px) — o orçamento volta ao básico")
            OrbDeviceMemory.onMemoryPressure()
            return null
        }
        onRendered?.invoke(key.state, key.index)
        return bitmap
    }

    private class Task(val owner: PackOrb, val key: FrameKey, val priority: Int, val sequence: Long)

    /**
     * Este pedido ainda serve? Os de "à frente" que a animação já passou são largados; os de pré-aquecimento valem sempre — salvo num pacote que
     * pede mais, em que um estado de até 240 frames na fila não pode atrasar o do estado seguinte: ali só vale o aquecimento do estado e do lado de agora.
     */
    private fun isStillWanted(task: Task): Boolean {
        if (closed) return false
        if (task.priority >= WARM) return !extended || active.let { it == null || (it.state == task.key.state && it.side == task.key.side) }
        val key = task.key
        val count = pack.framesOf(key.state).size
        val now = cursor[key.state] ?: return true
        val distance = Math.floorMod(key.index - now, count)
        // À frente do cursor (até o alcance do lookahead, mais uma folga) ou o próprio frame de agora; "atrás" é o que já passou.
        return distance <= lookaheadFor(pack.fpsOf(key.state), count) + 2
    }

    /** Renderiza o frame de [task] fora da thread de desenho e avisa o Compose. */
    private fun work(task: Task) {
        val key = task.key
        if (!isStillWanted(task) || cache.contains(key)) return
        val files = pack.framesOf(key.state)
        val file = files.getOrNull(key.index) ?: return
        val bitmap = render(key, pack.readSvg(file)) ?: return
        if (closed) return
        cache.put(key, bitmap)
        versionState.intValue++
    }

    companion object {
        private const val TAG = "SKP"

        /** O orçamento básico do cache de frames, para o processo inteiro (SKP-ORB-05): o de todo pacote que cabe nos limites de antes. */
        const val CACHE_BYTES = OrbMemoryPolicy.BASE_BYTES

        /** Os limites que o formato tinha antes dos 120 fps: quem passa de um deles pede mais ([extended]). */
        private const val LEGACY_MAX_FPS = 60
        private const val LEGACY_MAX_FRAMES_PER_STATE = 64

        private const val MIN_LOOKAHEAD = 3
        private const val MAX_LOOKAHEAD = 12
        private const val BYTES_PER_PIXEL = OrbMemoryPolicy.BYTES_PER_PIXEL
        private const val MAX_QUEUE = 128

        /** A fila de quem pede mais: cabe o aquecimento de um estado de 240 frames mais o quarto de segundo à frente (30 frames) e folga. */
        private const val EXTENDED_MAX_QUEUE = 512

        // Prioridades (menor = antes): o frame de agora, os próximos e, por último, o pré-aquecimento do estado inteiro.
        private const val URGENT = 0
        private const val AHEAD = 1
        private const val WARM = 2

        private val nextOwner = java.util.concurrent.atomic.AtomicInteger(1)
        private val sequence = AtomicLong()

        // O cache de frames escolhe quem sai pela posição da animação (ver [victimOf]), não pelo menos usado.
        private val cache = ByteBudgetLru<FrameKey, Bitmap>(CACHE_BYTES, ::victimOf) { it.allocationByteCount }

        // O que cada orbe de um pacote que pede mais precisa agora (bytes do estado que toca, no lado de agora), por dono. Vazio = orçamento básico.
        private val demands = ConcurrentHashMap<Int, Long>()
        private val budgetLock = Any()

        /** O orçamento de agora: 16 MB, ou o que a orbe mais exigente pede, até o teto do aparelho ([OrbMemoryPolicy.budgetFor]). */
        val budgetBytes: Long get() = cache.maxBytes

        /**
         * Recalcula o orçamento do cache a partir do que as orbes pedem e do teto do aparelho, e solta já o excesso se ele encolheu: primeiro os frames
         * que não estão tocando, depois os mais longe à frente do cursor. Chamado quando o pedido muda (estado, lado, fechar) e quando o sistema aperta.
         */
        internal fun reviseBudget() {
            synchronized(budgetLock) {
                val demand = demands.values.maxOrNull() ?: 0L
                cache.setMaxBytes(OrbMemoryPolicy.budgetFor(demand, OrbDeviceMemory.ceilingBytes), ::victimOnShrink)
            }
        }

        /** Quem sai quando o orçamento encolhe: o menos usado entre os frames que não são do estado e do lado que a orbe toca; se só restam esses, o mais longe à frente do cursor. */
        private fun victimOnShrink(keys: Iterable<FrameKey>): FrameKey? {
            var farthest: FrameKey? = null
            var farthestDistance = -1
            for (candidate in keys) {
                val owner = owners[candidate.owner] ?: return candidate
                val playing = owner.active
                if (playing == null || playing.state != candidate.state || playing.side != candidate.side) return candidate
                val now = owner.cursor[candidate.state] ?: continue
                val distance = Math.floorMod(candidate.index - now, owner.pack.framesOf(candidate.state).size.coerceAtLeast(1))
                if (distance > farthestDistance) {
                    farthestDistance = distance
                    farthest = candidate
                }
            }
            return farthest
        }

        // As orbes abertas, por dono: o cache precisa saber em que frame cada animação está para escolher quem despejar.
        private val owners = ConcurrentHashMap<Int, PackOrb>()

        /**
         * Quem sai do cache quando estoura o teto. Frames de outra orbe, estado ou tamanho (não estão tocando agora) saem primeiro, do menos
         * usado para o mais usado. Se só restam frames da animação que acabou de receber [justInserted], sai o **mais longe à frente do cursor**
         * da volta — o que acabou de passar, que será o último a ser mostrado de novo. O LRU faria o contrário e, num loop maior que o cache,
         * erraria SEMPRE: o menos usado é exatamente o próximo a aparecer. Assim, com C lugares para N frames, cada volta só renderiza N−C+1.
         */
        private fun victimOf(keys: Iterable<FrameKey>, justInserted: FrameKey): FrameKey? {
            var farthest: FrameKey? = null
            var farthestDistance = -1
            val owner = owners[justInserted.owner]
            val now = owner?.cursor?.get(justInserted.state)
            val count = owner?.pack?.framesOf(justInserted.state)?.size ?: 0
            for (candidate in keys) {
                if (candidate == justInserted) continue
                val samePlayback = candidate.owner == justInserted.owner && candidate.state == justInserted.state && candidate.side == justInserted.side
                if (!samePlayback) return candidate // o menos usado entre os que não estão tocando
                if (now != null && count > 0) {
                    val distance = Math.floorMod(candidate.index - now, count)
                    if (distance > farthestDistance) {
                        farthestDistance = distance
                        farthest = candidate
                    }
                }
            }
            return farthest
        }
        private val queue = PriorityBlockingQueue<Task>(64, compareBy<Task>({ it.priority }, { it.sequence }))

        // Pedidos na fila OU sendo renderizados agora: é o que evita renderizar o mesmo frame duas vezes.
        private val pending = HashMap<FrameKey, Task>()

        @Volatile
        private var worker: Thread? = null

        /** Bytes de bitmap guardados agora no cache de frames (para medir e para os testes). */
        val cacheBytes: Long get() = cache.bytes

        /** Quantos frames há no cache de frames agora. */
        val cacheFrames: Int get() = cache.size

        /** Abre a orbe de [pack], ou `null` se o pacote não tem orbe. Nunca lança. */
        fun open(pack: SkinPack): PackOrb? = if (pack.hasOrb) PackOrb(pack).also { owners[it.ownerId] = it } else null

        /** Abre a orbe do pacote que [skinId] (`pack:<id>`) escolhe, pelo [store]; `null` se não é de pacote, sumiu, quebrou ou não tem orbe. */
        fun open(store: SkinPackStore, skinId: String): PackOrb? = try {
            store.loadBySkinId(skinId)?.let(::open)
        } catch (e: Exception) {
            Log.w(TAG, "orbe de $skinId não abriu: ${e.message}")
            null
        }

        private fun ensureWorker() {
            if (worker?.isAlive == true) return
            synchronized(pending) {
                if (worker?.isAlive == true) return
                worker = thread(name = "keyflow-orb-pack", isDaemon = true) {
                    // Um pouco atrás da thread de UI, mas sem o grupo de segundo plano do sistema (a animação não pode ficar para trás
                    // quando outro app disputa a CPU): é o que mantém a orbe no ritmo do relógio.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)
                    while (true) {
                        val task = queue.take()
                        try {
                            task.owner.work(task)
                        } catch (e: Exception) {
                            Log.w(TAG, "render de fundo falhou: ${e.message}")
                        } finally {
                            synchronized(pending) { if (pending[task.key] === task) pending.remove(task.key) }
                        }
                    }
                }
            }
        }
    }
}
