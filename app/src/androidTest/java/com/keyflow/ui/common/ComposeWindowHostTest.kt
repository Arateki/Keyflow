package com.keyflow.ui.common

import android.content.Context
import android.graphics.PixelFormat
import android.view.WindowManager
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.overlay.OverlayWindowConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica o contrato do [ComposeWindowHost] numa janela real `TYPE_APPLICATION_OVERLAY`:
 * os três tree-owners ficam ligados, o `ComposeView` compõe, o ciclo vai a RESUMED no attach
 * e a DESTROYED no detach (sem deixar a janela pendurada).
 */
@RunWith(AndroidJUnit4::class)
class ComposeWindowHostTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private var added = false

    @Before
    fun setUp() {
        // Janela de overlay exige o app-op concedido (GrantPermissionRule é instável no API 34).
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        windowManager = context.getSystemService(WindowManager::class.java)
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            if (added) runCatching { windowManager.removeView(composeView) }
            added = false
        }
    }

    @Test
    fun attachProvidesTreeOwnersAndComposesThenDetachDestroys() {
        val host = ComposeWindowHost()
        var composed = false

        instrumentation.runOnMainSync {
            val view = ComposeView(context).apply {
                setContent {
                    SideEffect { composed = true }
                    Text("host-ok")
                }
            }
            composeView = view

            // attach ANTES de addView: os owners precisam estar prontos quando a view entra na janela.
            host.attach(view)

            assertEquals(host, view.findViewTreeLifecycleOwner())
            assertEquals(host, view.findViewTreeSavedStateRegistryOwner())
            assertEquals(host, view.findViewTreeViewModelStoreOwner())
            assertEquals(Lifecycle.State.RESUMED, host.lifecycle.currentState)

            windowManager.addView(view, overlayParams())
            added = true
        }

        // A composição roda em frames seguintes — aguardar a main ficar idle e o flag virar.
        waitUntil("composeView nunca compôs") { composed }

        // Detach encerra o ciclo (sem leak) e remove a janela.
        instrumentation.runOnMainSync {
            windowManager.removeView(composeView)
            added = false
            host.detach()
        }

        assertEquals(Lifecycle.State.DESTROYED, host.lifecycle.currentState)
        assertTrue(!host.isAttached)
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        val config = OverlayWindowConfig.forSdk()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            config.type,
            config.flags,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = config.gravity }
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

    private fun shell(command: String) {
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        try {
            java.io.FileInputStream(fd.fileDescriptor).readBytes()
        } finally {
            fd.close()
        }
    }
}
