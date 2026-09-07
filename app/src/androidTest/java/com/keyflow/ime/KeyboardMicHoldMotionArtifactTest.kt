package com.keyflow.ime

import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.keyflow.ComposeSmokeActivity
import com.keyflow.core.ButtonState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Gera artefatos visuais do gesto real de "segurar o mic pra mudar o estado da gravação" do teclado
 * padrão (pausar/retomar, cancelar, desfazer — KBDCTL-01..04) para revisão humana.
 *
 * Mesma técnica do [com.keyflow.overlay.OrbMotionArtifactTest]: injeta [MotionEvent] reais via
 * `instrumentation.sendPointerSync` (o seletor usa `event.rawX/rawY`, então precisa de coordenadas de
 * tela de verdade — não dá pra chamar `view.onTouchEvent` direto como a orbe faz), espera o estado
 * observável (`selectorOpenForTest`/`highlightedIndexForTest`) chegar e só então captura frames de tela
 * cheia. PNGs e metadados ficam em `externalFilesDir/keyboard-mic-hold-gifs`, para o script host montar
 * os GIFs.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardMicHoldMotionArtifactTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val outRoot: File by lazy {
        File(context.getExternalFilesDir(null), "keyboard-mic-hold-gifs").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private var scenario: ActivityScenario<ComposeSmokeActivity>? = null
    private var downTime = 0L

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun generateKeyboardMicHoldMotionArtifacts() {
        writeMetadata()

        captureHoldScenario(slug = "mic-hold-pause", initialState = ButtonState.RECORDING, expectedAction = MicAction.PAUSE_RESUME)
        captureHoldScenario(slug = "mic-hold-resume", initialState = ButtonState.PAUSED, expectedAction = MicAction.PAUSE_RESUME)
        captureHoldScenario(slug = "mic-hold-cancel-recording", initialState = ButtonState.RECORDING, expectedAction = MicAction.CANCEL)
        captureHoldScenario(slug = "mic-hold-cancel-paused", initialState = ButtonState.PAUSED, expectedAction = MicAction.CANCEL)
        captureHoldScenario(slug = "mic-hold-cancel-sending", initialState = ButtonState.SENDING, expectedAction = MicAction.CANCEL)
        captureHoldScenario(slug = "mic-hold-undo-cancelling", initialState = ButtonState.CANCELLING, expectedAction = MicAction.UNDO)
        captureAbortScenario(slug = "mic-hold-abort", initialState = ButtonState.RECORDING)
    }

    /** Segura o mic, desliza até o chip alvo e solta em cima dele — a ação é aplicada (KBDCTL-02/03/04). */
    private fun captureHoldScenario(slug: String, initialState: ButtonState, expectedAction: MicAction) {
        val dir = File(outRoot, slug).apply { mkdirs() }
        val chosenActions = mutableListOf<MicAction>()

        launchHarness(initialState = initialState, onMicAction = { chosenActions += it }) { view ->
            var frame = 0
            capture(dir, frame++, "initial")

            val micRect = screenRect(view.micViewForTest!!)
            val micX = micRect.centerX()
            val micY = micRect.centerY()

            down(micX, micY)
            capture(dir, frame++, "pressed")
            waitUntil("$slug não abriu o seletor") { view.selectorOpenForTest }
            waitUntil("$slug sem retângulos do popup") {
                view.popupRectsForTest.size == view.popupActionsForTest.size && view.popupRectsForTest.isNotEmpty()
            }
            capture(dir, frame++, "hold-open")

            val targetIndex = view.popupActionsForTest.indexOf(expectedAction)
            assertTrue("$slug não oferece $expectedAction (ações: ${view.popupActionsForTest})", targetIndex >= 0)
            val targetRect = view.popupRectsForTest[targetIndex]
            val targetX = targetRect.centerX()
            val targetY = targetRect.centerY()

            for (step in 1..10) {
                val pct = step / 10f
                move(lerp(micX, targetX, pct), lerp(micY, targetY, pct))
                val label = if (step == 10) "target-$expectedAction" else "approach-%02d".format(step * 10)
                capture(dir, frame++, label)
            }
            waitUntil("$slug não destacou $expectedAction") { view.highlightedIndexForTest == targetIndex }
            capture(dir, frame++, "target-hold-1")
            capture(dir, frame++, "target-hold-2")

            up(targetX, targetY)
            waitUntil("$slug não fechou o seletor") { !view.selectorOpenForTest }
            capture(dir, frame++, "released")
            capture(dir, frame++, "released-settled")
        }

        assertTrue("$slug deveria emitir $expectedAction, emitiu $chosenActions", chosenActions.contains(expectedAction))
        writeScenarioFile(dir, slug, initialState, expectedAction.toString(), chosenActions.map { it.toString() })
    }

    /** Segura, desliza até CANCEL, mas volta o dedo pro mic antes de soltar — cancelamento rápido (Yan, 2026-07-01). */
    private fun captureAbortScenario(slug: String, initialState: ButtonState) {
        val dir = File(outRoot, slug).apply { mkdirs() }
        val chosenActions = mutableListOf<MicAction>()

        launchHarness(initialState = initialState, onMicAction = { chosenActions += it }) { view ->
            var frame = 0
            capture(dir, frame++, "initial")

            val micRect = screenRect(view.micViewForTest!!)
            val micX = micRect.centerX()
            val micY = micRect.centerY()

            down(micX, micY)
            capture(dir, frame++, "pressed")
            waitUntil("$slug não abriu o seletor") { view.selectorOpenForTest }
            waitUntil("$slug sem retângulos do popup") {
                view.popupRectsForTest.size == view.popupActionsForTest.size && view.popupRectsForTest.isNotEmpty()
            }
            capture(dir, frame++, "hold-open")

            val targetIndex = view.popupActionsForTest.indexOf(MicAction.CANCEL)
            assertTrue("$slug não oferece CANCEL (ações: ${view.popupActionsForTest})", targetIndex >= 0)
            val targetRect = view.popupRectsForTest[targetIndex]
            val targetX = targetRect.centerX()
            val targetY = targetRect.centerY()

            for (step in 1..5) {
                val pct = step / 5f
                move(lerp(micX, targetX, pct), lerp(micY, targetY, pct))
                capture(dir, frame++, "approach-%02d".format(step * 10))
            }
            waitUntil("$slug não destacou CANCEL antes de recuar") { view.highlightedIndexForTest == targetIndex }
            capture(dir, frame++, "target-hold")

            for (step in 1..5) {
                val pct = step / 5f
                move(lerp(targetX, micX, pct), lerp(targetY, micY, pct))
                capture(dir, frame++, "retreat-%02d".format(step * 10))
            }
            waitUntil("$slug não desgrudou o highlight ao voltar pro mic") { view.highlightedIndexForTest == -1 }
            capture(dir, frame++, "aborted")

            up(micX, micY)
            waitUntil("$slug não fechou o seletor") { !view.selectorOpenForTest }
            capture(dir, frame++, "released-no-action")
            capture(dir, frame++, "released-settled")
        }

        assertTrue("$slug não deveria emitir nenhuma ação, emitiu $chosenActions", chosenActions.isEmpty())
        writeScenarioFile(dir, slug, initialState, "none (abort)", chosenActions.map { it.toString() })
    }

    private fun lerp(from: Int, to: Int, pct: Float): Int = from + ((to - from) * pct).toInt()

    private fun writeScenarioFile(
        dir: File,
        slug: String,
        initialState: ButtonState,
        expectedAction: String,
        chosenActions: List<String>,
    ) {
        File(dir, "scenario.txt").writeText(
            listOf(
                "slug=$slug",
                "initialState=$initialState",
                "expectedAction=$expectedAction",
                "chosenActions=${chosenActions.joinToString(",")}",
            ).joinToString("\n") + "\n",
        )
    }

    private fun launchHarness(
        initialState: ButtonState,
        onMicAction: (MicAction) -> Unit,
        block: (KeyFlowKeyboardView) -> Unit,
    ) {
        val launched = ActivityScenario.launch(ComposeSmokeActivity::class.java)
        scenario = launched
        lateinit var view: KeyFlowKeyboardView
        launched.onActivity { activity ->
            view = KeyFlowKeyboardView(
                context = activity,
                onKey = {},
                onMic = {},
                onMicAction = onMicAction,
                onHide = {},
                frozenSeconds = FROZEN_SECONDS,
                // Captura o hold do mic da barra ANTIGA (TextView + seletor por popup). Com as barras
                // como padrão (ROL-02) o gesto equivalente vive no MicSelectorController da BarView —
                // regravar os GIFs de lá é tarefa da remoção da barra antiga.
                useBars = false,
            )
            activity.setContentView(
                FrameLayout(activity).apply {
                    setBackgroundColor(Color.rgb(244, 236, 207))
                    addView(
                        view,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM,
                        ),
                    )
                },
            )
            view.updateMicState(initialState)
        }
        waitUntil("mic não foi medido") { view.micViewForTest?.let { it.width > 0 && it.height > 0 } == true }
        block(view)
        launched.close()
        scenario = null
    }

    private fun screenRect(view: View): Rect {
        val loc = IntArray(2).also { view.getLocationOnScreen(it) }
        return Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
    }

    private fun capture(dir: File, index: Int, label: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(80)
        val file = File(dir, "frame-%02d-%s.png".format(index, label))
        assertTrue("falha ao capturar ${file.name}", device.takeScreenshot(file))
    }

    private fun down(x: Int, y: Int) {
        downTime = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
    }

    private fun move(x: Int, y: Int) {
        inject(MotionEvent.ACTION_MOVE, x, y, downTime)
    }

    private fun up(x: Int, y: Int) {
        inject(MotionEvent.ACTION_UP, x, y, downTime)
    }

    private fun inject(action: Int, x: Int, y: Int, downTime: Long, eventTime: Long = SystemClock.uptimeMillis()) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x.toFloat(), y.toFloat(), 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    private fun waitUntil(message: String, timeoutMs: Long = 3000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(message)
    }

    private fun writeMetadata() {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        File(outRoot, "metadata.json").writeText(
            """
            {
              "capturedAtUtc": "$now",
              "packageName": "${context.packageName}",
              "manufacturer": "${json(Build.MANUFACTURER)}",
              "brand": "${json(Build.BRAND)}",
              "model": "${json(Build.MODEL)}",
              "device": "${json(Build.DEVICE)}",
              "product": "${json(Build.PRODUCT)}",
              "androidRelease": "${json(Build.VERSION.RELEASE)}",
              "sdkInt": ${Build.VERSION.SDK_INT},
              "buildIncremental": "${json(Build.VERSION.INCREMENTAL)}",
              "securityPatch": "${json(Build.VERSION.SECURITY_PATCH)}",
              "fingerprint": "${json(Build.FINGERPRINT)}",
              "display": "${json(shell("wm size"))}",
              "density": "${json(shell("wm density"))}",
              "qemu": "${json(shell("getprop ro.kernel.qemu"))}",
              "hardware": "${json(shell("getprop ro.hardware"))}",
              "windowAnimationScale": "${json(shell("settings get global window_animation_scale"))}",
              "transitionAnimationScale": "${json(shell("settings get global transition_animation_scale"))}",
              "animatorDurationScale": "${json(shell("settings get global animator_duration_scale"))}",
              "oneUiVersion": "${json(shell("getprop ro.build.version.oneui"))}",
              "semPlatformInt": "${json(shell("getprop ro.build.version.sep"))}",
              "isLikelyEmulator": ${isLikelyEmulator()}
            }
            """.trimIndent() + "\n",
        )
    }

    private fun shell(command: String): String {
        val fd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return try {
            FileInputStream(fd.fileDescriptor).bufferedReader().readText().trim()
        } finally {
            fd.close()
        }
    }

    private fun json(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun isLikelyEmulator(): Boolean {
        val qemu = shell("getprop ro.kernel.qemu") == "1"
        val fields = listOf(Build.FINGERPRINT, Build.MODEL, Build.DEVICE, Build.PRODUCT, Build.HARDWARE)
            .joinToString(" ")
            .lowercase(Locale.US)
        return qemu || fields.contains("emulator") || fields.contains("generic") ||
            fields.contains("ranchu") || fields.contains("goldfish") || fields.contains("gphone")
    }

    private companion object {
        const val FROZEN_SECONDS = 8
    }
}
