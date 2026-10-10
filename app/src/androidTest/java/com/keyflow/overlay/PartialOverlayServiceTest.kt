package com.keyflow.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.core.TranscriptionSource
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.ui.ImeProbeActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * NM-T17/T18 com o [OverlayService] real: transcrição parcial ao pausar, pela origem Custom apontada
 * para um servidor HTTP no próprio aparelho (`localhost`, o único host em texto puro liberado) — cada
 * trecho enviado recebe a próxima resposta do roteiro. Dirige pelas ações da notificação (como o
 * [OverlayServiceCancelTest]) e lê as janelas do parcial pelo UiAutomator.
 */
@RunWith(AndroidJUnit4::class)
class PartialOverlayServiceTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val repository by lazy { SettingsRepository(context) }
    private lateinit var saved: Settings

    private val script = listOf("primeira parte", "segunda parte", "fim")
    private val requests = AtomicInteger(0)
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val text = script[requests.getAndIncrement().coerceAtMost(script.lastIndex)]
                return MockResponse().setBody("""{"text":"$text"}""")
            }
        }
        start()
    }

    @Before
    fun setUp() {
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        runBlocking {
            saved = repository.settingsFlow().first()
            repository.saveSettings(
                saved.copy(
                    source = TranscriptionSource.CUSTOM,
                    customEndpoint = server.url("/v1/audio/transcriptions").newBuilder().host("localhost").build().toString(),
                    customHeaderValue = "Bearer teste",
                    partialOnPause = true,
                    partialViewEdit = true,
                    // A entrega vai para o inserter do teste: não depende do que outro teste deixou salvo.
                    autoPasteInField = true,
                ),
            )
        }
        OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_RECORD))
        waitUntilState(ButtonState.RECORDING)
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { KeyFlowController.clearInserter() }
        OverlayService.stop(context)
        runBlocking { repository.saveSettings(saved) }
        server.shutdown()
    }

    @Test
    fun pausingShowsTheBadgeAndEditsSurviveNewSegmentsUntilTheSend() {
        speakThenPause()
        assertNotNull("indicador do parcial não apareceu", device.wait(Until.findObject(By.textContains("parcial · 2 palavras")), TIMEOUT))
        assertEquals(1, requests.get())

        // Toque no indicador → visualização; toque no texto → input editável (PAR-05).
        device.findObject(By.textContains("parcial ·")).click()
        device.wait(Until.findObject(By.text("primeira parte")), TIMEOUT)!!.click()
        val input = device.wait(Until.findObject(By.clazz("android.widget.EditText").text("primeira parte")), TIMEOUT)
        assertNotNull("input do parcial não abriu", input)
        input!!.text = "Primeira parte, editada"

        // Retoma com o input aberto e pausa de novo: o trecho novo entra depois da edição (PAR-06).
        sendAction(RecordingNotification.ACTION_PAUSE_RESUME)
        waitUntilState(ButtonState.RECORDING)
        speakThenPause()
        assertNotNull(
            "o trecho novo não apareceu no input junto da edição",
            device.wait(Until.findObject(By.clazz("android.widget.EditText").text("Primeira parte, editada segunda parte")), TIMEOUT),
        )

        // Sair da edição volta ao indicador com o conteúdo (PAR-06).
        device.findObject(By.desc("Fechar transcrição parcial")).click()
        assertNotNull(device.wait(Until.findObject(By.textContains("parcial · 5 palavras")), TIMEOUT))

        // Envio: o texto inteiro, com a edição, sai uma vez pelo fluxo normal e as janelas somem (PAR-07).
        var delivered: String? = null
        instrumentation.runOnMainSync { KeyFlowController.registerInserter { delivered = it; true } }
        sendAction(RecordingNotification.ACTION_STOP_PASTE)
        waitUntil("texto não foi entregue", TIMEOUT) { delivered != null }
        assertEquals("Primeira parte, editada segunda parte", delivered)
        assertTrue(device.wait(Until.gone(By.textContains("parcial ·")), TIMEOUT))

        // Smoke: depois do input focável, um campo comum segue funcionando.
        context.startActivity(Intent(context, ImeProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val probe = device.wait(Until.findObject(By.desc(ImeProbeActivity.FIELD_DESC)), TIMEOUT)
        assertNotNull(probe)
        probe!!.text = "segue funcionando"
        assertNotNull(device.wait(Until.findObject(By.text("segue funcionando")), TIMEOUT))
        device.pressBack()
    }

    @Test
    fun cancelHidesThePartialAndUndoBringsItBack() {
        speakThenPause()
        assertNotNull(device.wait(Until.findObject(By.textContains("parcial · 2 palavras")), TIMEOUT))

        sendAction(RecordingNotification.ACTION_CANCEL)
        waitUntilState(ButtonState.CANCELLING)
        assertTrue(device.wait(Until.gone(By.textContains("parcial ·")), TIMEOUT))

        // PAR-08: desfazer devolve a sessão com o buffer.
        sendAction(OverlayService.ACTION_UNDO_CANCEL)
        waitUntilState(ButtonState.PAUSED)
        assertNotNull(device.wait(Until.findObject(By.textContains("parcial · 2 palavras")), TIMEOUT))
    }

    @Test
    fun cancelThatExpiresEndsThePartialSession() {
        speakThenPause()
        assertNotNull(device.wait(Until.findObject(By.textContains("parcial · 2 palavras")), TIMEOUT))

        sendAction(RecordingNotification.ACTION_CANCEL)
        waitUntilState(ButtonState.IDLE_NO_FIELD, ButtonState.IDLE_READY, timeoutMs = 8_000)

        assertTrue(device.wait(Until.gone(By.textContains("parcial ·")), TIMEOUT))
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Grava um trecho acima do mínimo e pausa — o trecho fechado vai ao servidor. */
    private fun speakThenPause() {
        Thread.sleep(1_200)
        sendAction(RecordingNotification.ACTION_PAUSE_RESUME)
        waitUntilState(ButtonState.PAUSED)
    }

    private fun sendAction(action: String) {
        context.startService(OverlayService.actionIntent(context, action))
    }

    private fun waitUntilState(vararg expected: ButtonState, timeoutMs: Long = TIMEOUT) {
        waitUntil("esperando ${expected.toList()}, estado atual: ${KeyFlowController.buttonState.value}", timeoutMs) {
            KeyFlowController.buttonState.value in expected
        }
    }

    private fun waitUntil(message: String, timeoutMs: Long = TIMEOUT, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(message)
    }

    private fun shell(command: String) {
        val fd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
