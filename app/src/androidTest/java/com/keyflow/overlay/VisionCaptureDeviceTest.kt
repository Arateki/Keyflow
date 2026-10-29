package com.keyflow.overlay

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.keyflow.ui.gallery.GalleryActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Prova opt-in da seleção e da projeção real; o diálogo do sistema é tratado pelo UiAutomator. */
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
        var w = 0; var h = 0
        OverlayService.visionCaptureResultForTest = { bitmap -> w = bitmap.width; h = bitmap.height; result.countDown() }
        val launch = Intent(context, GalleryActivity::class.java).putExtra("route", "config-visao")
        val scenario = ActivityScenario.launch<GalleryActivity>(launch)
        try {
            OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_VISION))
            val chooser = device.wait(Until.findObject(By.textMatches("(?i).*single app.*|.*um aplicativo.*|.*um app.*")), 15_000)
            chooser?.click()
            device.wait(Until.findObject(By.textMatches("(?i).*entire screen.*|.*tela inteira.*")), 2_000)?.click()
            val accept = device.wait(Until.findObject(By.textMatches("(?i).*start now.*|.*iniciar agora.*")), 15_000)
            assertTrue("diálogo de consentimento não apareceu", accept != null)
            accept!!.click()
            // O seletor é uma View Canvas: aguarda sua janela real antes de tocar em Confirmar.
            assertTrue("seletor não apareceu", device.wait(Until.hasObject(By.desc("Selecione a área da tela.")), 15_000))
            device.click(device.displayWidth / 2, device.displayHeight - 35)
            assertTrue("bitmap recortado não chegou", result.await(30, TimeUnit.SECONDS))
            assertTrue("recorte vazio", w > 0 && h > 0)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nVisão: captura e seleção entregaram bitmap ${w}x${h}\n") })
        } finally {
            OverlayService.visionCaptureResultForTest = null
            OverlayService.stop(context)
            scenario.close()
        }
    }
}
