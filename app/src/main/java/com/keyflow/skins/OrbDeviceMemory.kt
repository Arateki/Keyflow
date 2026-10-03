package com.keyflow.skins

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.util.Log

/**
 * Quanto cache de bitmaps este aparelho aguenta para a orbe de pacote (SKP-ORB-06): o teto até onde o orçamento do [PackOrb] pode crescer.
 *
 * Pergunta uma vez ao sistema ([install], pelo [SkinPackStore.of], que é por onde o app, o IME e o overlay abrem os pacotes):
 * [ActivityManager.getMemoryClass] (o limite "de bom comportamento" por app, em MB) e [ActivityManager.isLowRamDevice]; a conta está em
 * [OrbMemoryPolicy.deviceCeiling]. **Antes de instalar, e em qualquer aparelho de pouca RAM, o teto é o orçamento de sempre (16 MB)** — o padrão
 * é o conservador.
 *
 * E escuta o sistema: com a memória apertada ([ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW] ou pior, ou [android.app.Application.onLowMemory]) o
 * teto volta aos 16 MB por [PRESSURE_MS] e o [PackOrb] larga o excesso na hora — a orbe nunca briga com o sistema pela memória. O nível
 * `TRIM_MEMORY_UI_HIDDEN` (a interface do app saiu da tela) NÃO conta: é o que acontece sempre que o usuário vai para outro app com a orbe
 * flutuando por cima, justamente o uso normal.
 */
internal object OrbDeviceMemory {
    private const val TAG = "SKP"

    /** Quanto tempo o teto fica nos 16 MB depois de um aviso de pouca memória do sistema. */
    const val PRESSURE_MS = 30_000L

    @Volatile
    private var deviceCeiling = OrbMemoryPolicy.BASE_BYTES

    @Volatile
    private var pressureUntil = 0L

    private var installed = false

    /** O teto de agora: o do aparelho, ou os 16 MB de sempre enquanto o sistema pede memória de volta. */
    val ceilingBytes: Long
        get() = if (SystemClock.elapsedRealtime() < pressureUntil) OrbMemoryPolicy.BASE_BYTES else deviceCeiling

    /** Lê a classe de memória do aparelho e passa a escutar os avisos do sistema. Idempotente e barato: só a primeira chamada faz algo. */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext ?: context
        deviceCeiling = ceilingOf(app)
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            // Os níveis RUNNING_* são obsoletos desde o Android 15 (o sistema deixa de mandá-los), mas as versões anteriores — o Android 13 do A32 — ainda os entregam.
            @Suppress("DEPRECATION")
            override fun onTrimMemory(level: Int) {
                if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                    onMemoryPressure()
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() = onMemoryPressure()
        })
    }

    /** O teto que o aparelho deste [context] aguenta, lido do sistema: a classe de memória e a marca de pouca RAM (ver [OrbMemoryPolicy.deviceCeiling]). */
    fun ceilingOf(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return OrbMemoryPolicy.BASE_BYTES
        val memoryClass = manager.memoryClass
        val lowRam = manager.isLowRamDevice
        val ceiling = OrbMemoryPolicy.deviceCeiling(memoryClass, lowRam)
        Log.i(TAG, "orbe de pacote: classe de memória $memoryClass MB, pouca RAM=$lowRam → o orçamento do cache pode ir até ${ceiling / (1024 * 1024)} MB (base ${OrbMemoryPolicy.BASE_BYTES / (1024 * 1024)} MB)")
        return ceiling
    }

    /** O sistema quer memória de volta: o teto fica nos 16 MB por [PRESSURE_MS] e o cache solta o excesso agora. */
    fun onMemoryPressure() {
        pressureUntil = SystemClock.elapsedRealtime() + PRESSURE_MS
        PackOrb.reviseBudget()
    }

    /** Só para os testes: troca o teto do aparelho (e tira o aviso de pouca memória, se houver). */
    fun setCeilingForTest(bytes: Long) {
        deviceCeiling = bytes
        pressureUntil = 0L
        PackOrb.reviseBudget()
    }
}
