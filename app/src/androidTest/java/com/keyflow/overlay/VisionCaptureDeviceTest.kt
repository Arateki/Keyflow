package com.keyflow.overlay

import android.Manifest
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.os.Bundle
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.keyflow.ui.gallery.GalleryActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Prova opt-in: autorização real, handles sobre tela viva, recorte, revogação e epílogo do FGS. */
@RunWith(AndroidJUnit4::class)
class VisionCaptureDeviceTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @Test fun capturaRegiaoNoEmulador() {
        val args = InstrumentationRegistry.getArguments().getString("class").orEmpty()
        assumeTrue(args.split(',').any { it.endsWith("VisionCaptureDeviceTest#capturaRegiaoNoEmulador") })
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").close()
        val result = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val types = mutableListOf<Int>()
        val microphone = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val combined = microphone or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        var w = 0; var h = 0
        var png = byteArrayOf()
        var projection: MediaProjection? = null
        OverlayService.visionProjectionStartedForTest = { projection = it }
        OverlayService.visionProjectionTypeForTest = { types += it; if (it == microphone) closed.countDown() }
        OverlayService.visionCaptureResultForTest = { bitmap ->
            w = bitmap.width; h = bitmap.height
            png = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
            result.countDown()
        }
        val scenario = ActivityScenario.launch<GalleryActivity>(Intent(context, GalleryActivity::class.java).putExtra("route", "config-visao"))
        fun requestAndAccept() {
            OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_VISION))
            val chooser = device.wait(Until.findObject(By.text(Pattern.compile("(?i).*single app.*|.*um aplicativo.*|.*um app.*"))), 2_000)
            chooser?.click()
            if (chooser != null) device.wait(Until.findObject(By.text(Pattern.compile("(?i).*entire screen.*|.*tela inteira.*"))), 2_000)?.click()
            val accept = device.wait(Until.findObject(By.text(Pattern.compile("(?i).*start now.*|.*iniciar agora.*"))), 15_000)
            assertNotNull("novo diálogo de consentimento não apareceu", accept)
            accept!!.click()
            assertTrue("seletor não apareceu", device.wait(Until.hasObject(By.desc("Selecione a área da tela.")), 15_000))
        }
        fun evidence(name: String, bytes: ByteArray) {
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nKEYFLOW_VISION_$name:${Base64.encodeToString(bytes, Base64.NO_WRAP)}\n") })
        }
        try {
            requestAndAccept()
            val bounds = device.findObject(By.desc("Selecione a área da tela.")).visibleBounds
            // Arrasta o handle superior esquerdo; a confirmação deve entregar exatamente esse recorte.
            val left = bounds.width() / 5
            val top = bounds.height() / 5
            val newLeft = left + 40
            val newTop = top + 60
            assertTrue(device.swipe(bounds.left + left, bounds.top + top, bounds.left + newLeft, bounds.top + newTop, 10))
            val screenshot = File(context.cacheDir, "vision-selector.png")
            assertTrue(device.takeScreenshot(screenshot))
            evidence("SELETOR", screenshot.readBytes())
            screenshot.delete()
            device.click(bounds.centerX(), bounds.bottom - 35)
            assertTrue("bitmap recortado não chegou", result.await(30, TimeUnit.SECONDS))
            assertEquals(bounds.width() * 4 / 5 - newLeft, w)
            assertEquals(bounds.height() * 4 / 5 - newTop, h)
            assertTrue("FGS não removeu mediaProjection", closed.await(10, TimeUnit.SECONDS))
            assertEquals(listOf(combined, microphone), types)
            evidence("BITMAP", png)
            // Uma segunda sessão exige autorização nova; revogar a projeção fecha a seleção e o tipo.
            types.clear()
            requestAndAccept()
            assertNotNull(projection)
            instrumentation.runOnMainSync { projection!!.stop() }
            assertTrue("revogação não fechou o seletor", device.wait(Until.gone(By.desc("Selecione a área da tela.")), 15_000))
            instrumentation.waitForIdleSync()
            assertEquals(listOf(combined, microphone), types)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nVisão: bitmap ${w}x${h}; handle +40,+60; FGS $combined → $microphone; segunda autorização e revogação confirmadas.\n")
            })
        } finally {
            OverlayService.visionCaptureResultForTest = null
            OverlayService.visionProjectionStartedForTest = null
            OverlayService.visionProjectionTypeForTest = null
            OverlayService.stop(context)
            scenario.close()
        }
    }
}
