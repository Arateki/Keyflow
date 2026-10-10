package com.keyflow.skins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.keyflow.skins.SkinPackFixtures.manifest
import com.keyflow.skins.SkinPackFixtures.svg
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SKP-ORB-06: o orçamento de memória do cache da orbe. O pacote que cabe nos limites de antes dos 120 fps (fps ≤ 60, até 64 frames por estado)
 * fica nos 16 MB de sempre, com o mesmo lookahead; o pacote que PEDE mais (fps acima de 60 ou mais de 64 frames) ganha um orçamento que cresce
 * até o que o estado que toca precisa — limitado pelo teto do aparelho — e que volta aos 16 MB ao sair do estado, mudar de tamanho ou fechar.
 * O teto do aparelho é trocado por [OrbDeviceMemory.setCeilingForTest] (48 MB, o do emulador de API 34), para o resultado não depender de onde roda.
 */
class PackOrbBudgetTest {

    private lateinit var base: File
    private lateinit var store: SkinPackStore
    private lateinit var context: Context
    private val opened = mutableListOf<PackOrb>()
    private val mb = 1024L * 1024
    private var realRefreshHz = 0f

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        base = SkinPackFixtures.tempRoot(context.cacheDir)
        store = SkinPackStore(File(base, "skins"))
        OrbDeviceMemory.setCeilingForTest(48 * mb)
        // Tela desconhecida (0): o lookahead pede todo frame seguinte. Os testes da tela mais lenta que a animação fixam a taxa que querem.
        realRefreshHz = OrbDisplay.refreshHz
        OrbDisplay.setRefreshHzForTest(0f)
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        // Devolve o teto que o aparelho de verdade dá, para as outras classes do processo não herdarem o do teste.
        OrbDeviceMemory.setCeilingForTest(OrbDeviceMemory.ceilingOf(context))
        OrbDisplay.setRefreshHzForTest(realRefreshHz)
        base.deleteRecursively()
    }

    /** Instala um pacote só de orbe com [frames] frames por estado (cada frame uma cor sólida, só para existir) e abre a orbe. */
    private fun open(name: String, frames: Map<String, Int>, fps: Int = 24, extraManifest: String = ""): PackOrb {
        val entries = mutableListOf("manifest.json" to manifest(name, ""","orb":{"fps":$fps}$extraManifest"""))
        for ((state, count) in frames) for (i in 1..count) entries += "orb_${state}_${i.toString().padStart(3, '0')}.svg" to svg("%06X".format((i * 2654435) and 0xFFFFFF))
        val result = store.import(SkinPackFixtures.zip(entries).inputStream())
        assertTrue(result.report.errors.toString(), result.installed)
        return PackOrb.open(store.load(result.packId!!)!!)!!.also { opened += it }
    }

    private fun waitUntil(message: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError(message)
    }

    private fun frameBytes(side: Int) = side.toLong() * side * 4

    // ── quem cabe nos limites de antes não muda de orçamento ─────────────────

    @Test
    fun aPackWithinTheOldLimitsKeepsTheBasicBudgetEvenWhenItsStateDoesNotFit() {
        // 64 frames a 24 fps (os limites de antes) a 280 px = 19,1 MB: não cabe nos 16 MB, e mesmo com 48 MB de teto no aparelho o orçamento NÃO cresce.
        val orb = open("Antigo pesado", mapOf("idle" to 64))
        orb.prepare(OrbPackState.IDLE, 280)
        waitUntil("o aquecimento não rendeu nada") { PackOrb.cacheFrames > 10 }
        assertEquals(PackOrb.CACHE_BYTES, PackOrb.budgetBytes)
        assertTrue("o cache passou dos 16 MB: ${PackOrb.cacheBytes}", PackOrb.cacheBytes <= PackOrb.CACHE_BYTES)
    }

    @Test
    fun aPackAtSixtyFpsAndSixtyFourFramesIsStillAnOldPack() {
        val orb = open("Na borda", mapOf("idle" to 64), fps = 60)
        orb.prepare(OrbPackState.IDLE, 280)
        waitUntil("o aquecimento não rendeu nada") { PackOrb.cacheFrames > 10 }
        assertEquals(PackOrb.CACHE_BYTES, PackOrb.budgetBytes)
    }

    @Test
    fun aHighFpsPackWhoseStateFitsTheBasicBudgetStaysOnIt() {
        val orb = open("Rápido leve", mapOf("idle" to 24), fps = 120)
        orb.prepare(OrbPackState.IDLE, 195) // 24 × 152 100 = 3,5 MB
        waitUntil("o estado não foi aquecido") { PackOrb.cacheFrames >= 24 }
        assertEquals(PackOrb.CACHE_BYTES, PackOrb.budgetBytes)
        assertEquals(24 * frameBytes(195), PackOrb.cacheBytes)
    }

    // ── quem pede mais ganha mais, até o teto ────────────────────────────────

    @Test
    fun aPackThatAsksForMoreGrowsTheBudgetToItsStateAndWarmsItWhole() {
        val orb = open("Pesado", mapOf("idle" to 120), fps = 120)
        orb.prepare(OrbPackState.IDLE, 195) // 120 × 152 100 = 17,4 MB: passa dos 16
        val demand = 120 * frameBytes(195)
        assertEquals("o orçamento cresce até o estado inteiro", demand, PackOrb.budgetBytes)
        waitUntil("o estado inteiro não foi aquecido") { PackOrb.cacheFrames >= 120 }
        assertEquals("o cache guarda os 120 frames, e a memória é a conta lado² × 4 × n", demand, PackOrb.cacheBytes)
    }

    @Test
    fun theBudgetStopsAtTheDeviceCeilingAndTheStateStillPlaysWithoutCrashing() {
        OrbDeviceMemory.setCeilingForTest(20 * mb)
        val orb = open("Além do teto", mapOf("idle" to 240), fps = 120)
        orb.prepare(OrbPackState.IDLE, 195) // 240 × 152 100 = 34,8 MB, mas o aparelho só deixa 20
        assertEquals(20 * mb, PackOrb.budgetBytes)
        waitUntil("nada foi renderizado") { PackOrb.cacheFrames > 20 }
        // Ao vivo, durante um segundo de relógio: nunca vazio depois do primeiro frame, nunca passa do teto, nunca lança.
        var shown = 0
        val start = System.nanoTime()
        while (System.nanoTime() - start < 1_000_000_000L) {
            val t = (System.nanoTime() - start) / 1e6f
            if (orb.frameFor(OrbPackState.IDLE, t, 195, deterministic = false) != null) shown++
            assertTrue("o cache passou do teto do aparelho: ${PackOrb.cacheBytes}", PackOrb.cacheBytes <= 20 * mb)
            Thread.sleep(8)
        }
        assertTrue("a orbe tinha o que mostrar", shown > 0)
        assertEquals(20 * mb, PackOrb.budgetBytes)
    }

    @Test
    fun aLowRamDeviceKeepsTheBasicBudgetForAHeavyPack() {
        OrbDeviceMemory.setCeilingForTest(OrbMemoryPolicy.deviceCeiling(memoryClassMb = 512, lowRam = true))
        val orb = open("Pouca RAM", mapOf("idle" to 120), fps = 120)
        orb.prepare(OrbPackState.IDLE, 195)
        assertEquals(PackOrb.CACHE_BYTES, PackOrb.budgetBytes)
        waitUntil("nada foi renderizado") { PackOrb.cacheFrames > 20 }
        assertTrue(PackOrb.cacheBytes <= PackOrb.CACHE_BYTES)
    }

    @Test
    fun theDeviceCeilingComesFromTheRealActivityManager() {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val expected = OrbMemoryPolicy.deviceCeiling(manager.memoryClass, manager.isLowRamDevice)
        assertEquals(expected, OrbDeviceMemory.ceilingOf(context))
        assertTrue(expected in OrbMemoryPolicy.BASE_BYTES..OrbMemoryPolicy.ABSOLUTE_MAX_BYTES)
    }

    // ── o excesso é solto ────────────────────────────────────────────────────

    @Test
    fun resizingTheOrbReevaluatesTheBudgetAndReleasesTheExcess() {
        val orb = open("Slider", mapOf("idle" to 120), fps = 120)
        orb.prepare(OrbPackState.IDLE, 195)
        waitUntil("o estado inteiro não foi aquecido") { PackOrb.cacheFrames >= 120 }
        assertTrue(PackOrb.cacheBytes > PackOrb.CACHE_BYTES)
        // O slider desce para 110 px: 120 × 48 400 = 5,5 MB, cabe nos 16. O orçamento volta e o excesso sai ANTES de a chamada voltar.
        orb.prepare(OrbPackState.IDLE, 110)
        assertEquals(PackOrb.CACHE_BYTES, PackOrb.budgetBytes)
        assertTrue("o excesso do lado antigo continuou no cache: ${PackOrb.cacheBytes}", PackOrb.cacheBytes <= PackOrb.CACHE_BYTES)
        // E sobe de novo: 120 × 193 600 (220 px) = 22,2 MB.
        orb.prepare(OrbPackState.IDLE, 220)
        assertEquals(120 * frameBytes(220), PackOrb.budgetBytes)
    }

    @Test
    fun leavingAHeavyStateReleasesTheExcessAndKeepsTheNewState() {
        val orb = open("Sai do estado", mapOf("listening" to 120, "idle" to 6), fps = 120)
        orb.prepare(OrbPackState.LISTENING, 195)
        waitUntil("o estado pesado não foi aquecido") { PackOrb.cacheFrames >= 120 }
        assertTrue(PackOrb.cacheBytes > PackOrb.CACHE_BYTES)
        orb.prepare(OrbPackState.IDLE, 195)
        assertEquals(PackOrb.CACHE_BYTES, PackOrb.budgetBytes)
        assertTrue("o excesso do estado que acabou continuou no cache: ${PackOrb.cacheBytes}", PackOrb.cacheBytes <= PackOrb.CACHE_BYTES)
        waitUntil("o estado novo não foi aquecido") { orb.frameFor(OrbPackState.IDLE, 0f, 195, deterministic = false) != null }
    }

    @Test
    fun closingThePackReleasesTheBudgetAndItsFrames() {
        val before = PackOrb.cacheBytes
        val orb = open("Fecha", mapOf("idle" to 120), fps = 120)
        orb.prepare(OrbPackState.IDLE, 195)
        waitUntil("o estado inteiro não foi aquecido") { PackOrb.cacheFrames >= 120 }
        assertTrue(PackOrb.budgetBytes > PackOrb.CACHE_BYTES)
        orb.close()
        assertEquals(PackOrb.CACHE_BYTES, PackOrb.budgetBytes)
        assertEquals(before, PackOrb.cacheBytes)
    }

    @Test
    fun aMemoryWarningFromTheSystemDropsTheExtraBudgetAtOnce() {
        val orb = open("Aperto", mapOf("idle" to 120), fps = 120)
        orb.prepare(OrbPackState.IDLE, 195)
        waitUntil("o estado inteiro não foi aquecido") { PackOrb.cacheFrames >= 120 }
        OrbDeviceMemory.onMemoryPressure()
        assertEquals(PackOrb.CACHE_BYTES, PackOrb.budgetBytes)
        assertTrue("o excesso continuou: ${PackOrb.cacheBytes}", PackOrb.cacheBytes <= PackOrb.CACHE_BYTES)
        // Sob aviso, um prepare novo não faz o orçamento voltar a crescer (o teto fica nos 16 MB por 30 s).
        orb.prepare(OrbPackState.IDLE, 195)
        assertEquals(PackOrb.CACHE_BYTES, PackOrb.budgetBytes)
    }

    @Test
    fun twoHeavyOrbsShareOneCacheAndTheBiggerDemandSetsTheBudget() {
        val small = open("Menor", mapOf("idle" to 100), fps = 120)
        val big = open("Maior", mapOf("idle" to 200), fps = 120)
        small.prepare(OrbPackState.IDLE, 156) // 100 × 97 344 = 9,3 MB: cabe
        assertEquals(PackOrb.CACHE_BYTES, PackOrb.budgetBytes)
        big.prepare(OrbPackState.IDLE, 156) // 200 × 97 344 = 18,6 MB
        assertEquals(200 * frameBytes(156), PackOrb.budgetBytes)
        big.close()
        assertEquals(PackOrb.CACHE_BYTES, PackOrb.budgetBytes)
    }

    // ── o lookahead de um quarto de segundo, em cada ritmo ────────────────────

    /** Pede o frame 0 ao vivo (sem aquecer) e devolve os índices que a thread de fundo renderizou até a fila esvaziar. */
    private fun liveRequest(fps: Int, frames: Int): List<Int> {
        val orb = open("Ritmo $fps", mapOf("idle" to frames), fps = fps)
        val rendered = CopyOnWriteArrayList<Int>()
        orb.onRendered = { _, index -> rendered += index }
        orb.frameFor(OrbPackState.IDLE, 0f, 64, deterministic = false)
        // A fila esvaziou quando o número de renders para de crescer entre duas leituras (150 ms de folga: um render leva ~2 ms).
        var previous = -1
        waitUntil("a fila não esvaziou") {
            Thread.sleep(150)
            val now = rendered.size
            val settled = now > 0 && now == previous
            previous = now
            settled
        }
        return rendered.sorted()
    }

    @Test
    fun theLookaheadIsAQuarterOfASecondAtEveryPaceAndTheOldOnesDidNotChange() {
        // Até 60 fps: fps/4 entre 3 e 12 (o frame de agora mais os da frente) — exatamente o de antes.
        assertEquals((0..3).toList(), liveRequest(fps = 12, frames = 40))
        assertEquals((0..6).toList(), liveRequest(fps = 24, frames = 40))
        assertEquals((0..12).toList(), liveRequest(fps = 48, frames = 40))
        assertEquals("a 60 fps o teto de 12 de sempre", (0..12).toList(), liveRequest(fps = 60, frames = 40))
        // Acima de 60: o quarto de segundo inteiro.
        assertEquals((0..22).toList(), liveRequest(fps = 90, frames = 100))
        assertEquals("a 120 fps são 30 frames à frente", (0..30).toList(), liveRequest(fps = 120, frames = 150))
    }

    @Test
    fun aScreenSlowerThanTheAnimationOnlyGetsTheFramesItShows() {
        // 120 fps numa tela de 60 Hz: um frame sim, outro não. O frame de agora e só os que a tela vai mostrar nos próximos 30 frames são renderizados.
        OrbDisplay.setRefreshHzForTest(60f)
        val rendered = liveRequest(fps = 120, frames = 150)
        val expected = (listOf(0) + OrbTiming.framesShownAhead(0f, 120, 150, 60f, 30)).sorted()
        assertEquals(expected, rendered)
        assertTrue("só metade dos frames da janela: $rendered", rendered.size <= 17)
        assertTrue("e todos de índice par (a fase do relógio em t=0): $rendered", rendered.all { it % 2 == 0 })
        // A 90 Hz, três de cada quatro.
        OrbDisplay.setRefreshHzForTest(90f)
        val at90 = liveRequest(fps = 120, frames = 150)
        assertTrue("a 90 Hz o 3 mod 4 fica de fora: $at90", at90.none { it % 4 == 3 })
        assertTrue("e vem mais que a 60 Hz: ${at90.size} contra ${rendered.size}", at90.size > rendered.size)
    }

    @Test
    fun aScreenSlowerThanTheAnimationWarmsOnlyWhatItShowsAndOnlyAtTheFirstDraw() {
        OrbDisplay.setRefreshHzForTest(60f)
        val orb = open("Amostrado", mapOf("idle" to 150), fps = 120)
        val rendered = CopyOnWriteArrayList<Int>()
        orb.onRendered = { _, index -> rendered += index }
        orb.prepare(OrbPackState.IDLE, 64)
        Thread.sleep(400)
        assertTrue("sem desenho, nada é aquecido às cegas: $rendered", rendered.isEmpty())
        orb.frameFor(OrbPackState.IDLE, 0f, 64, deterministic = false) // o primeiro frame que falta aquece a volta
        var previous = -1
        waitUntil("o aquecimento não assentou") {
            Thread.sleep(200)
            val now = rendered.size
            val settled = now > 20 && now == previous
            previous = now
            settled
        }
        val shown = rendered.sorted()
        assertTrue("uma volta de 150 frames a 120 fps numa tela de 60 Hz tem ~75 frames visíveis: ${shown.size}", shown.size in 73..76)
        assertTrue("todos de índice par: $shown", shown.all { it % 2 == 0 })
        // E o frame seguinte que a tela pede (o 2, no desenho de 16,7 ms) já está pronto.
        assertNotNull(orb.frameFor(OrbPackState.IDLE, 16.7f, 64, deterministic = false))
    }

    @Test
    fun theDisplayRateOnlyChangesWhatPacksThatAskForMoreRequest() {
        // Um pacote dos limites de antes (60 fps) pede o mesmo lookahead com a tela em 30, 60 ou 90 Hz: o caminho dele não olha a tela.
        for (hz in listOf(0f, 30f, 60f, 90f)) {
            OrbDisplay.setRefreshHzForTest(hz)
            assertEquals("tela a $hz Hz", (0..12).toList(), liveRequest(fps = 60, frames = 40))
        }
    }

    @Test
    fun aStateWithFewerFramesThanTheLookaheadOnlyRendersWhatExists() {
        assertEquals((0..9).toList(), liveRequest(fps = 120, frames = 10))
    }

    // ── o aquecimento de quem pede mais não atrapalha o estado seguinte ───────

    @Test
    fun aWarmUpOfTheStateThatJustEndedDoesNotDelayTheNewState() {
        val orb = open("Troca", mapOf("idle" to 1, "listening" to 200, "transcribing" to 4), fps = 120)
        val arrivals = CopyOnWriteArrayList<OrbPackState>()
        orb.onRendered = { state, _ -> arrivals += state }
        orb.prepare(OrbPackState.LISTENING, 64) // 200 pedidos de aquecimento
        waitUntil("o aquecimento não começou") { arrivals.isNotEmpty() }
        orb.prepare(OrbPackState.TRANSCRIBING, 64) // o estado mudou: o que falta do outro não vale mais
        waitUntil("o estado novo não foi aquecido") { arrivals.count { it == OrbPackState.TRANSCRIBING } >= 4 }
        val listeningAfter = arrivals.subList(arrivals.indexOf(OrbPackState.TRANSCRIBING), arrivals.size).count { it == OrbPackState.LISTENING }
        assertTrue("só o que já estava em andamento do estado anterior pode chegar depois do novo começar: $listeningAfter", listeningAfter <= 2)
        assertNotNull(orb.frameFor(OrbPackState.TRANSCRIBING, 0f, 64, deterministic = false))
    }

    @Test
    fun theWholeWarmUpOfAHeavyStateIsQueuedNotJustTheFirstHundredAndTwentyEight() {
        val orb = open("Fila grande", mapOf("idle" to 240), fps = 120)
        orb.prepare(OrbPackState.IDLE, 64)
        waitUntil("os 240 frames não foram aquecidos") { PackOrb.cacheFrames >= 240 }
    }

    // ── o aquecimento de quem cabe nos limites de antes é o de sempre ─────────

    @Test
    fun anOldPackWarmsHalfTheBasicCacheAndANewOneWarmsItsWholeStateWhenItFits() {
        // 40 frames a 280 px = 12 MB: cabe nos 16. O pacote antigo aquece só a metade do cache (16 MB / 2 / 313 600 = 26 frames), como sempre;
        // o que pede mais (aqui, só pelo ritmo) e cabe aquece o estado inteiro.
        val old = open("Antigo", mapOf("idle" to 40), fps = 24)
        old.prepare(OrbPackState.IDLE, 280)
        waitUntil("o aquecimento do antigo não chegou a 26 frames") { PackOrb.cacheFrames >= 26 }
        Thread.sleep(500)
        assertEquals("o antigo aquece 26 frames, não 40", 26, PackOrb.cacheFrames)
        old.close()
        val new = open("Novo", mapOf("idle" to 40), fps = 120)
        new.prepare(OrbPackState.IDLE, 280)
        waitUntil("o aquecimento do novo não chegou a 40 frames") { PackOrb.cacheFrames >= 40 }
        assertEquals(PackOrb.CACHE_BYTES, PackOrb.budgetBytes)
    }
}
