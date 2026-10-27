package com.keyflow.reasoning.local

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RRUN-02/05/06: o cliente de verdade contra o `:reasoning` de verdade, sem modelo grande — o motor de eco (só debug)
 * devolve as palavras do pedido, uma a cada 30 ms. Cobre o fluxo, o modelo ausente, a morte do processo no meio e o
 * cancelamento chegando ao serviço.
 */
@RunWith(AndroidJUnit4::class)
class LocalReasoningEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // Bind mantido entre as gerações, como em produção: com lingerMs = 0 o serviço seria destruído e a geração seguinte
    // cairia numa instância nova — sem fila — e o teste de cancelamento não provaria nada.
    private val connection = ReasoningConnection(context, lingerMs = 60_000)
    private val echo = LlmModel(ECHO_MODEL_ID, "", "LITERT_LM", idleUnloadMs = 0, ramEstimateMb = 0)

    private fun engine(model: LlmModel? = echo) = LocalReasoningEngine(
        modelId = model?.modelId ?: "ausente",
        modelFor = { model },
        connection = connection,
    )

    @After
    fun tearDown() = connection.close()

    private fun words(n: Int) = (1..n).joinToString(" ") { "p$it" }

    private suspend fun Flow<ReasoningEvent>.all(): List<ReasoningEvent> = withTimeout(20_000) { toList() }

    private fun text(events: List<ReasoningEvent>) = events.filterIsInstance<ReasoningEvent.Delta>().joinToString("") { it.text }

    @Test
    fun streamsEchoDeltasInOrderThenDone() = runBlocking {
        val events = engine().generate(ReasoningRequest(user = "um dois três")).all()
        assertEquals("um dois três", text(events))
        assertEquals(ReasoningEvent.Done, events.last())
        assertTrue(events.dropLast(1).all { it is ReasoningEvent.Delta })
    }

    @Test
    fun aModelWithNoFileOnDiskEmitsModelMissing() = runBlocking {
        val absent = LlmModel("nao-existe", "/data/local/tmp/nao-existe.litertlm", "LITERT_LM", 0, 0)
        val events = engine(absent).generate(ReasoningRequest(user = "oi")).all()
        val error = events.last() as ReasoningEvent.Error
        assertEquals(ReasoningError.MODEL_MISSING, error.kind)
    }

    @Test
    fun aModelTheCatalogDoesNotKnowEmitsModelMissingNotACrash() = runBlocking {
        val events = engine(model = null).generate(ReasoningRequest(user = "oi")).all()
        assertEquals(ReasoningError.MODEL_MISSING, (events.last() as ReasoningEvent.Error).kind)
    }

    @Test
    fun twoGenerationsBackToBackBothComplete() = runBlocking {
        val e = engine()
        val first = e.generate(ReasoningRequest(user = "a b")).all()
        val second = e.generate(ReasoningRequest(user = "c d")).all()
        assertEquals("a b", text(first))
        assertEquals("c d", text(second))
        assertEquals(ReasoningEvent.Done, second.last())
    }

    /** RRUN-05: matar o `:reasoning` no meio vira erro LOCAL na tela, e o próximo uso religa sozinho. */
    @Test
    fun killingTheRemoteProcessMidStreamEmitsLocalAndTheNextCallRebinds() = runBlocking {
        val e = engine()
        val events = mutableListOf<ReasoningEvent>()
        withTimeout(20_000) {
            e.generate(ReasoningRequest(user = words(400))).collect { event ->
                events += event
                if (events.size == 3) killReasoningProcess()
            }
        }
        val error = events.last() as ReasoningEvent.Error
        assertEquals(ReasoningError.LOCAL, error.kind)

        val next = e.generate(ReasoningRequest(user = "de novo")).all()
        assertEquals("de novo", text(next))
        assertEquals(ReasoningEvent.Done, next.last())
    }

    /**
     * RRUN-06: cancelar o coletor tem de chegar ao `:reasoning`. Um eco de ~6 s é cancelado na 1ª palavra; a geração
     * seguinte (3 palavras) só termina rápido se o serviço parou a primeira — senão ela esperaria na fila.
     */
    @Test
    fun cancellingTheCollectorStopsTheRemoteEchoAndTheNextGenerationRunsPromptly() = runBlocking {
        val e = engine()
        e.generate(ReasoningRequest(user = words(200))).first()   // cancela o fluxo depois do 1º trecho

        val started = System.nanoTime()
        val next = e.generate(ReasoningRequest(user = "x y z")).all()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("x y z", text(next))
        assertTrue("a geração cancelada continuou no serviço ($elapsedMs ms)", elapsedMs < 3_000)
    }

    @Test
    fun preCargaPeloBinderTerminaSemDeltasEPreservaAGeracaoReal() = runBlocking {
        val service = connection.acquire()
        val done = java.util.concurrent.CountDownLatch(1)
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            service.preload(echo.toParcel(), object : IReasoningCallback.Stub() {
                override fun onDelta(text: String) { events += "delta" }
                override fun onDone() { events += "pronto"; done.countDown() }
                override fun onError(kind: Int, message: String) { events += "erro:$kind"; done.countDown() }
            })
            assertTrue(done.await(20, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf("pronto"), events)
            val real = engine().generate(ReasoningRequest(user = "fala real")).all()
            assertEquals("fala real", text(real))
            assertEquals(ReasoningEvent.Done, real.last())
        } finally { connection.release() }
    }

    private fun killReasoningProcess() {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pid = manager.runningAppProcesses.orEmpty()
            .firstOrNull { it.processName == "${context.packageName}:reasoning" }?.pid
        assertNotNull("processo :reasoning não encontrado", pid)
        Process.killProcess(pid!!)
    }
}
