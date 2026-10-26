package com.keyflow.overlay

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.keyflow.ComposeSmokeActivity
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.core.RecordingMode
import com.keyflow.ui.orb.OrbDragKind
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Gera artefatos visuais dos gestos reais da orbe para revisão humana.
 *
 * Diferente dos goldens estáticos `orb-drag-*`, este teste passa pelo caminho de toque real da
 * [OrbButtonView]: injeta [MotionEvent], espera o estado observável chegar e só então captura frames da
 * tela inteira do emulador, incluindo a sobreposição de setas. Os PNGs e metadados ficam em
 * `externalFilesDir/motion-gifs`, para o script host montar GIFs e copiar para `/tmp/keyflow-prints`.
 */
@RunWith(AndroidJUnit4::class)
class OrbMotionArtifactTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // Pulado com `-e motionFrames false` (o gate do CI): o que ele confere dos gestos o OrbButtonViewTest também confere
    // (pausar, retomar, cancelar e abrir a configuração, nos dois modos), e o resto é gerar quadros de GIF. No emulador do
    // CI (swangle) a orbe em arrasto leva 150–200 ms por quadro e redesenha sem parar: o teste passava de 6 min, e com
    // waitForIdleSync nem terminava (2026-10-10). Os scripts verify-*-gifs.sh não passam o argumento e seguem gravando.
    @Before
    fun onlyWhenRecordingFrames() {
        assumeTrue(
            "motionFrames=false: sem quadros para gravar",
            InstrumentationRegistry.getArguments().getString("motionFrames") != "false",
        )
    }

    private val context = instrumentation.targetContext
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val outRoot: File by lazy {
        File(context.getExternalFilesDir(null), "motion-gifs").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private var scenario: ActivityScenario<ComposeSmokeActivity>? = null
    private var harnessRoot: FrameLayout? = null

    @After
    fun tearDown() {
        closeScenario()
    }

    /**
     * Desanexa a orbe (e com ela a composição animada) na thread principal ANTES de fechar a Activity: o
     * `ActivityScenario.close()` chama `waitForIdleSync()` antes de destruí-la, e com a orbe animada anexada a fila só fica
     * ociosa quando acontece. `runOnMainSync` em vez de `onActivity`, que também espera ociosidade.
     */
    private fun closeScenario() {
        val root = harnessRoot
        harnessRoot = null
        if (root != null) instrumentation.runOnMainSync { root.removeAllViews() }
        val launched = scenario
        scenario = null
        launched?.close()
    }

    @Test
    fun generateOrbMotionArtifacts() {
        writeMetadata()

        captureDragScenario(
            slug = "toggle-pause",
            mode = RecordingMode.TOGGLE,
            initialState = ButtonState.RECORDING,
            dx = 0f,
            dy = -DRAG_DISTANCE,
            expectedKind = OrbDragKind.PAUSE,
        )
        captureDragScenario(
            slug = "toggle-resume",
            mode = RecordingMode.TOGGLE,
            initialState = ButtonState.PAUSED,
            dx = 0f,
            dy = -DRAG_DISTANCE,
            expectedKind = OrbDragKind.RESUME,
        )
        captureDragScenario(
            slug = "toggle-cancel",
            mode = RecordingMode.TOGGLE,
            initialState = ButtonState.RECORDING,
            dx = -DRAG_DISTANCE,
            dy = 0f,
            expectedKind = OrbDragKind.CANCEL,
        )
        captureDragScenario(
            slug = "toggle-config",
            mode = RecordingMode.TOGGLE,
            initialState = ButtonState.RECORDING,
            dx = 0f,
            dy = DRAG_DISTANCE,
            expectedKind = OrbDragKind.CONFIG,
        )
        captureDragScenario(
            slug = "ptt-pause",
            mode = RecordingMode.PUSH_TO_TALK,
            initialState = ButtonState.RECORDING,
            dx = 0f,
            dy = -DRAG_DISTANCE,
            expectedKind = OrbDragKind.PAUSE,
        )
        captureDragScenario(
            slug = "ptt-resume",
            mode = RecordingMode.PUSH_TO_TALK,
            initialState = ButtonState.PAUSED,
            dx = 0f,
            dy = -DRAG_DISTANCE,
            expectedKind = OrbDragKind.RESUME,
        )
        captureDragScenario(
            slug = "ptt-cancel",
            mode = RecordingMode.PUSH_TO_TALK,
            initialState = ButtonState.RECORDING,
            dx = -DRAG_DISTANCE,
            dy = 0f,
            expectedKind = OrbDragKind.CANCEL,
        )
        captureDragScenario(
            slug = "ptt-config",
            mode = RecordingMode.PUSH_TO_TALK,
            initialState = ButtonState.RECORDING,
            dx = 0f,
            dy = DRAG_DISTANCE,
            expectedKind = OrbDragKind.CONFIG,
        )
        captureDragScenario(
            slug = "ptt-cancel-sending",
            mode = RecordingMode.PUSH_TO_TALK,
            initialState = ButtonState.SENDING,
            dx = -DRAG_DISTANCE,
            dy = 0f,
            expectedKind = OrbDragKind.CANCEL,
        )
    }

    private fun captureDragScenario(
        slug: String,
        mode: RecordingMode,
        initialState: ButtonState,
        dx: Float,
        dy: Float,
        expectedKind: OrbDragKind,
    ) {
        val dir = File(outRoot, slug).apply { mkdirs() }
        val intents = mutableListOf<GestureIntent>()
        val openedConfig = mutableListOf<Unit>()

        launchHarness(
            mode = mode,
            initialState = initialState,
            onIntent = { intents += it },
            onOpenConfig = { openedConfig += Unit },
        ) { view ->
            var frame = 0
            capture(dir, frame++, "initial")

            down(view)
            capture(dir, frame++, "pressed")
            waitUntil(
                "$slug não travou o modo-arrasto " +
                    "(rendered=${view.renderedState} mode=$mode esperado=$initialState)",
            ) { view.dragLockedForTest }
            capture(dir, frame++, "locked")

            for (step in 1..10) {
                val pct = step / 10f
                move(view, dx = dx * pct, dy = dy * pct)
                if (step == 5) {
                    waitUntil("$slug não resolveu alvo parcial") { view.dragKindForTest == expectedKind }
                }
                val label = if (step == 10) "target-$expectedKind" else "approach-%02d".format(step * 10)
                capture(dir, frame++, label)
            }
            waitUntil("$slug não resolveu alvo final") { view.dragKindForTest == expectedKind }
            capture(dir, frame++, "target-hold-1")
            capture(dir, frame++, "target-hold-2")

            up(view, dx = dx, dy = dy)
            waitUntil("$slug não saiu do modo-arrasto") { !view.dragLockedForTest }
            capture(dir, frame++, "released")
            capture(dir, frame++, "released-settled")
        }

        val expectedIntent = when (expectedKind) {
            OrbDragKind.PAUSE -> GestureIntent.PAUSE
            OrbDragKind.RESUME -> GestureIntent.RESUME
            OrbDragKind.CANCEL -> GestureIntent.CANCEL
            OrbDragKind.CONFIG -> null
        }
        if (expectedIntent != null) {
            assertTrue("$slug deveria emitir $expectedIntent, emitiu $intents", intents.contains(expectedIntent))
        } else {
            assertTrue("$slug deveria abrir config", openedConfig.isNotEmpty())
        }
        File(dir, "scenario.txt").writeText(
            listOf(
                "slug=$slug",
                "mode=$mode",
                "initialState=$initialState",
                "expectedKind=$expectedKind",
                "intents=${intents.joinToString(",")}",
                "openedConfig=${openedConfig.size}",
            ).joinToString("\n") + "\n",
        )
    }

    private fun launchHarness(
        mode: RecordingMode,
        initialState: ButtonState,
        onIntent: (GestureIntent) -> Unit,
        onOpenConfig: () -> Unit,
        block: (OrbButtonView) -> Unit,
    ) {
        val launched = ActivityScenario.launch(ComposeSmokeActivity::class.java)
        scenario = launched
        lateinit var view: OrbButtonView
        launched.onActivity { activity ->
            KeyFlowController.updateRecordingTime(accumulatedMs = FROZEN_SECONDS * 1000L, segmentStartMs = FROZEN_CLOCK_MS)
            lateinit var arrows: DragArrowsView
            view = createView(
                activity = activity,
                mode = mode,
                onIntent = onIntent,
                onOpenConfig = onOpenConfig,
                onDragArrows = { state ->
                    if (state == null) {
                        arrows.visibility = View.INVISIBLE
                    } else {
                        arrows.visibility = View.VISIBLE
                        arrows.setArrows(state)
                    }
                },
            )
            arrows = DragArrowsView(activity).apply { visibility = View.INVISIBLE }
            val root = FrameLayout(activity)
            harnessRoot = root
            activity.setContentView(
                root.apply {
                    setBackgroundColor(Color.rgb(244, 236, 207))
                    addView(
                        view,
                        FrameLayout.LayoutParams(ORB_SIZE, ORB_SIZE, Gravity.CENTER),
                    )
                    addView(
                        arrows,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                },
            )
            view.render(initialState)
        }
        waitUntil("orbe não foi medida") { view.width > 0 && view.height > 0 }
        try {
            block(view)
        } finally {
            closeScenario()
        }
    }

    private fun createView(
        activity: Activity,
        mode: RecordingMode,
        onIntent: (GestureIntent) -> Unit,
        onOpenConfig: () -> Unit,
        onDragArrows: (DragArrowsState?) -> Unit,
    ): OrbButtonView {
        lateinit var view: OrbButtonView
        val controller = RecordingGestureController(
            mode = { mode },
            currentState = { view.renderedState },
        )
        view = OrbButtonView(
            context = activity,
            controller = controller,
            mode = { mode },
            onIntent = onIntent,
            onOpenConfig = onOpenConfig,
            onDragArrows = onDragArrows,
            orbSizePx = ORB_SIZE,
            tapBurstWindowMs = TAP_BURST_WINDOW_MS,
            clock = { FROZEN_CLOCK_MS },
        )
        return view
    }

    private fun capture(dir: File, index: Int, label: String) {
        settle()
        Thread.sleep(80)
        val file = File(dir, "frame-%02d-%s.png".format(index, label))
        assertTrue("falha ao capturar ${file.name}", device.takeScreenshot(file))
    }

    /**
     * Espera, com prazo de [DRAW_TIMEOUT_MS], DOIS commits de quadro encadeados da janela depois do evento já processado
     * pelo `runOnMainSync`, sem esperar a fila ficar ociosa. `waitForIdleSync()` não serve: a orbe é um `ComposeView` com
     * animações infinitas (aura, partículas) e, no swangle, cada quadro leva 150–200 ms; medido no servidor, cada chamada
     * ficava de 10 a 25 s até a fila ociosar (153 quadros por teste). `runOnMainSync` vazio só espera o `Runnable`, e o
     * `takeScreenshot` não espera a composição. `registerFrameCommitCallback` (API 29+) avisa quando o quadro foi
     * renderizado e submetido; o primeiro commit pode ser de um quadro já em curso antes do evento, e o segundo, registrado
     * dentro do primeiro, é de um quadro iniciado depois dele, que enxerga o estado já aplicado na árvore. Não prova
     * pixel a pixel que o compositor já exibiu o quadro; a asserção do estado é feita pelo `waitUntil` que antecede a captura.
     * Abaixo da API 29 cai no `OnDrawListener` (aviso ANTES do desenho) e na folga do `Thread.sleep` de [capture].
     */
    private fun settle() {
        val decor = harnessRoot ?: run {
            instrumentation.runOnMainSync { }
            return
        }
        val done = CountDownLatch(1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            instrumentation.runOnMainSync {
                decor.viewTreeObserver.registerFrameCommitCallback {
                    decor.viewTreeObserver.registerFrameCommitCallback { done.countDown() }
                    decor.invalidate()
                }
                decor.invalidate()
            }
        } else {
            val draws = CountDownLatch(2)
            val listener = ViewTreeObserver.OnDrawListener { draws.countDown() }
            instrumentation.runOnMainSync {
                decor.viewTreeObserver.addOnDrawListener(listener)
                decor.invalidate()
            }
            if (draws.await(DRAW_TIMEOUT_MS, TimeUnit.MILLISECONDS)) done.countDown()
            decor.post { decor.viewTreeObserver.removeOnDrawListener(listener) }
        }
        assertTrue(
            "a janela não confirmou dois quadros em ${DRAW_TIMEOUT_MS}ms",
            done.await(DRAW_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
    }

    private fun down(view: OrbButtonView) {
        instrumentation.runOnMainSync {
            view.onTouchEvent(event(MotionEvent.ACTION_DOWN, START_X, START_Y, time = 0L))
        }
    }

    private fun move(view: OrbButtonView, dx: Float, dy: Float) {
        instrumentation.runOnMainSync {
            view.onTouchEvent(event(MotionEvent.ACTION_MOVE, START_X + dx, START_Y + dy, time = 30L))
        }
    }

    private fun up(view: OrbButtonView, dx: Float, dy: Float) {
        instrumentation.runOnMainSync {
            view.onTouchEvent(event(MotionEvent.ACTION_UP, START_X + dx, START_Y + dy, time = 60L))
        }
    }

    private fun event(action: Int, x: Float, y: Float, time: Long): MotionEvent =
        MotionEvent.obtain(0L, time, action, x, y, 0)

    private fun waitUntil(message: String, timeoutMs: Long = 3000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            settle()
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
        const val ORB_SIZE = 220
        const val DRAW_TIMEOUT_MS = 10_000L
        const val START_X = ORB_SIZE / 2f
        const val START_Y = ORB_SIZE / 2f
        const val DRAG_DISTANCE = 170f
        const val TAP_BURST_WINDOW_MS = 120L
        const val FROZEN_SECONDS = 8L
        const val FROZEN_CLOCK_MS = 80_000L
    }
}
