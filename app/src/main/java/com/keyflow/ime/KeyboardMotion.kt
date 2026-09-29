package com.keyflow.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * O movimento de uma skin do teclado (a falha do Glitch). O teclado é desenhado por código, então pode animar — mas
 * não o tempo todo: o movimento é feito de **eventos** curtos (uma rajada de falha de ~0,2 s a cada ~3,6 s) e **nada
 * invalida a view entre eles**, então em repouso o custo é zero. Durante um evento roda a uns 30 quadros por segundo,
 * e cada quadro só reaproveita as listas de desenho já gravadas dos filhos (a falha é feita desenhando os filhos de
 * novo, em fatias deslocadas) — não redesenha teclas nem recalcula layout.
 *
 * Não roda com "reduzir animações" (escala 0), com a economia de bateria ligada, nem com o teclado escondido.
 */
internal interface KeyboardMotion {
    /** Há um evento em andamento (a view deve passar por [draw] em vez de desenhar os filhos direto). */
    val isActive: Boolean

    fun start()

    fun stop()

    /** Um dedo tocou o teclado: o Glitch responde com uma micro-falha. */
    fun onTouchDown()

    /** Desenha o quadro do evento; [drawChildren] desenha os filhos da view uma vez no canvas dado. */
    fun draw(canvas: Canvas, width: Int, height: Int, drawChildren: (Canvas) -> Unit)
}

/** As regras de quando o teclado pode se mover. */
internal object MotionPolicy {
    fun allowed(context: Context): Boolean {
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val powerSave = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        return scale > 0f && !powerSave
    }
}

/**
 * O calendário das falhas do teclado Glitch, função pura do tempo (o mesmo instante dá sempre o mesmo quadro): a cada
 * período há uma rajada, num ponto do período que muda de uma para outra (não parece metrônomo) e com duração
 * também variável; a intensidade sobe e desce em triângulo.
 */
internal object GlitchTimeline {
    const val PERIOD_MS = 3600f

    /** Uma faixa horizontal do teclado que desliza de lado: [top]/[bottom] são frações da altura; [dxDp], o deslocamento. */
    class Band(val top: Float, val bottom: Float, val dxDp: Float)

    fun burstStart(k: Int): Float = k * PERIOD_MS + PERIOD_MS * (0.20f + 0.55f * random(k, 0))

    fun burstLength(k: Int): Float = 170f + 190f * random(k, 1)

    fun burstIndex(t: Float): Int = floor(t / PERIOD_MS).toInt()

    /** Intensidade da falha no instante [t] (ms): 0 fora das rajadas, 1 no pico de uma. */
    fun intensity(t: Float): Float {
        val k = burstIndex(t)
        val u = (t - burstStart(k)) / burstLength(k)
        return if (u in 0f..1f) 1f - abs(2f * u - 1f) else 0f
    }

    /** Quando começa a próxima rajada depois de [t] (ms). */
    fun nextBurstStart(t: Float): Float {
        val k = burstIndex(t)
        val start = burstStart(k)
        return if (start > t) start else burstStart(k + 1)
    }

    /** O instante do pico de uma rajada (o meio dela) — a galeria congela o teclado aqui. */
    fun peakOf(k: Int): Float = burstStart(k) + burstLength(k) / 2f

    /**
     * As faixas deslocadas da rajada [k] com intensidade [g]: de 1 a 3, em posições e larguras que vêm da rajada (a
     * mesma rajada dá sempre as mesmas faixas), de cima para baixo e sem se sobrepor.
     */
    fun bands(k: Int, g: Float): List<Band> {
        if (g <= 0.02f) return emptyList()
        val raw = (0 until 1 + (g * 2.99f).toInt()).map { i ->
            val mid = random(k, i + 2)
            val half = 0.03f + 0.07f * random(k, i + 9)
            val dx = (2.5f + 7f * g) * (if (random(k, i + 17) > 0.5f) 1f else -1f)
            Band((mid - half).coerceAtLeast(0f), (mid + half).coerceAtMost(1f), dx)
        }.sortedBy { it.top }
        val out = ArrayList<Band>(raw.size)
        var edge = 0f
        for (band in raw) {
            val top = maxOf(band.top, edge)
            if (band.bottom > top) {
                out.add(Band(top, band.bottom, band.dxDp))
                edge = band.bottom
            }
        }
        return out
    }

    /** Número pseudo-aleatório estável em 0..1 a partir de dois inteiros. */
    fun random(a: Int, b: Int): Float {
        val x = sin(a * 12.9898f + b * 78.233f) * 43758.547f
        return x - floor(x)
    }
}

/**
 * A falha do teclado Glitch ao vivo. [applyEffect] recebe a intensidade (0..1) a cada quadro do evento — quem a usa
 * (as teclas: o deslocamento das cores do rótulo) precisa invalidar-se; ao fim do evento recebe 0 uma última vez.
 * Com [fixedTimeMs] o movimento não roda: desenha o quadro congelado nesse instante (galeria).
 */
internal class GlitchMotion(
    private val view: View,
    private val density: Float,
    private val fixedTimeMs: Float?,
    private val applyEffect: (Float) -> Unit,
) : KeyboardMotion {

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint()
    // O fundo do Glitch por baixo de cada faixa deslocada: sem ele, a borda que a faixa deixa livre mostra o app de trás.
    private val basePaint = Paint().apply { color = BASE }
    private var running = false
    private var epoch = 0L

    private var effect = 0f
    private var burst = 0
    private var activeUntil = 0L
    private var pulseUntil = 0L
    private var touches = 0

    override var isActive: Boolean = false
        private set

    private val beginBurst = Runnable {
        val k = GlitchTimeline.burstIndex(nowT())
        activate(k, until = uptime() + GlitchTimeline.burstLength(k).toLong() + FRAME_MS)
    }
    private val tick = Runnable { onTick() }

    private fun uptime() = SystemClock.uptimeMillis()

    private fun nowT(): Float = (uptime() - epoch).toFloat()

    override fun start() {
        if (fixedTimeMs != null) {
            burst = GlitchTimeline.burstIndex(fixedTimeMs)
            val g = GlitchTimeline.intensity(fixedTimeMs)
            effect = g
            isActive = g > 0f
            applyEffect(g)
            return
        }
        if (running || !MotionPolicy.allowed(view.context)) return
        running = true
        epoch = uptime()
        scheduleNextBurst()
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(beginBurst)
        handler.removeCallbacks(tick)
        if (isActive || effect != 0f) {
            isActive = false
            effect = 0f
            applyEffect(0f)
        }
    }

    override fun onTouchDown() {
        if (!running) return
        touches++
        val now = uptime()
        pulseUntil = now + PULSE_MS
        if (!isActive) activate(PULSE_BASE + touches, until = pulseUntil + FRAME_MS)
        else activeUntil = maxOf(activeUntil, pulseUntil + FRAME_MS)
    }

    private fun scheduleNextBurst() {
        val t = nowT()
        handler.removeCallbacks(beginBurst)
        handler.postDelayed(beginBurst, (GlitchTimeline.nextBurstStart(t) - t).toLong().coerceAtLeast(0L))
    }

    private fun activate(index: Int, until: Long) {
        burst = index
        activeUntil = until
        isActive = true
        handler.removeCallbacks(tick)
        onTick()
    }

    private fun onTick() {
        val now = uptime()
        if (!running) return
        val timeline = if (burst >= PULSE_BASE) 0f else GlitchTimeline.intensity(nowT())
        val pulse = if (pulseUntil > now) (pulseUntil - now).toFloat() / PULSE_MS * PULSE_PEAK else 0f
        val g = maxOf(timeline, pulse)
        if (now >= activeUntil && g <= 0f) {
            isActive = false
            effect = 0f
            applyEffect(0f)
            view.invalidate()
            scheduleNextBurst()
            return
        }
        effect = g
        applyEffect(g)
        view.invalidate()
        handler.postDelayed(tick, FRAME_MS)
    }

    override fun draw(canvas: Canvas, width: Int, height: Int, drawChildren: (Canvas) -> Unit) {
        val g = effect
        val bands = if (isActive && g > 0.02f) GlitchTimeline.bands(burst, g) else emptyList()
        if (bands.isEmpty()) {
            drawChildren(canvas)
            return
        }
        // O teclado inteiro em fatias horizontais; as das faixas deslizam de lado.
        fun segment(top: Float, bottom: Float, dx: Float) {
            canvas.save()
            canvas.clipRect(0f, top, width.toFloat(), bottom)
            if (dx != 0f) canvas.drawRect(0f, top, width.toFloat(), bottom, basePaint)
            canvas.translate(dx, 0f)
            drawChildren(canvas)
            canvas.restore()
        }
        var y = 0f
        for (band in bands) {
            val top = band.top * height
            val bottom = band.bottom * height
            if (top > y) segment(y, top, 0f)
            segment(top, bottom, band.dxDp * density)
            y = bottom
        }
        segment(y, height.toFloat(), 0f)
        drawGhosts(canvas, width, height, bands, g)
    }

    /** Os fios de cor nas bordas de cada faixa deslocada (o canal que não alinhou) e uns blocos de "pixel estourado". */
    private fun drawGhosts(canvas: Canvas, width: Int, height: Int, bands: List<GlitchTimeline.Band>, g: Float) {
        val w = width.toFloat()
        val line = 1.2f * density
        for ((i, band) in bands.withIndex()) {
            val shift = band.dxDp * density * 0.6f
            paint.color = withAlpha(CYAN, 0.5f * g)
            canvas.drawRect(-shift, band.top * height, w - shift, band.top * height + line, paint)
            paint.color = withAlpha(RED, 0.5f * g)
            canvas.drawRect(shift, band.bottom * height - line, w + shift, band.bottom * height, paint)
            val mid = (band.top + band.bottom) / 2f * height
            paint.color = withAlpha(if (i % 2 == 0) CYAN else RED, 0.6f * g)
            val bx = w * GlitchTimeline.random(burst, i + 31)
            canvas.drawRect(bx, mid, bx + 8f * density, mid + 2.6f * density, paint)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int = (((alpha.coerceIn(0f, 1f) * 255f).toInt()) shl 24) or (color and 0x00FFFFFF)

    private companion object {
        const val FRAME_MS = 32L
        const val PULSE_MS = 150L
        const val PULSE_PEAK = 0.55f
        const val PULSE_BASE = 100_000
        const val CYAN = 0x25F4EE
        const val RED = 0xFE2C55
        const val BASE = 0xFF0B0B0C.toInt()
    }
}
