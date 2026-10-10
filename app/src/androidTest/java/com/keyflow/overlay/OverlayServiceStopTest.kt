package com.keyflow.overlay

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Desligar o Modo Sobrepor antes de o [OverlayService] chegar ao primeiro plano. Com `stopService` direto, o Android
 * derrubava o app inteiro ("Bringing down service while still waiting for start foreground" →
 * ForegroundServiceDidNotStartInTimeException) — no CI de 2026-10-09 isso levou junto o resto do shard. Aqui a corrida
 * é provocada sem depender de carga: ligar e desligar na MESMA tarefa da thread principal, onde o serviço ainda não
 * pode ter nascido.
 */
@RunWith(AndroidJUnit4::class)
class OverlayServiceStopTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        OverlayService.stop(context)
        waitUntil("o serviço de um teste anterior não parou") { !serviceRunning() }
    }

    @After
    fun tearDown() {
        OverlayService.stop(context)
    }

    @Test
    fun turningOffBeforeTheServiceReachesTheForegroundStopsItWithoutCrashing() {
        instrumentation.runOnMainSync {
            OverlayService.start(context, OverlayService.startOverlayIntent(context))
            OverlayService.stop(context)
        }

        // O serviço chega ao primeiro plano e se encerra sozinho, sem orbe; o processo segue vivo (o teste continua).
        waitUntil("o serviço não se encerrou depois do stop") { !serviceRunning() }
        SystemClock.sleep(SETTLE_MS)
        assertFalse("o serviço voltou a rodar", serviceRunning())
        assertFalse("a orbe chegou a subir", OverlayService.isRunningForUi)
    }

    @Test
    fun turningOnAgainAfterAQuickOffKeepsTheOverlay() {
        instrumentation.runOnMainSync {
            OverlayService.start(context, OverlayService.startOverlayIntent(context))
            OverlayService.stop(context)
            OverlayService.start(context, OverlayService.startOverlayIntent(context))
        }

        waitUntil("o Modo Sobrepor religado não subiu") { OverlayService.isRunningForUi }
        OverlayService.stop(context)
        waitUntil("o serviço não parou com o serviço já em primeiro plano") { !serviceRunning() }
    }

    @Suppress("DEPRECATION") // getRunningServices segue listando os serviços do próprio app.
    private fun serviceRunning(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == OverlayService::class.java.name }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError(message)
    }

    private fun shell(command: String) {
        val fd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val SETTLE_MS = 2_000L
    }
}
