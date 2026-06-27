package com.keyflow.audio

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AudioRecorderTest {

    private lateinit var context: Context
    private lateinit var outputDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
        outputDir = File(context.cacheDir, "audio-recorder-test/${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        outputDir.deleteRecursively()
    }

    @Test
    fun recordsNonEmptyM4aFile() {
        val recorder = AudioRecorder(context = context, outputDir = outputDir)

        recorder.start()
        Thread.sleep(700)
        val file = recorder.stop()

        assertNotNull(file)
        assertTrue(file!!.exists())
        assertTrue(file.length() > 0)
        assertTrue(file.name.endsWith(".m4a"))
    }

    @Test
    fun discardsVeryShortClip() {
        val recorder = AudioRecorder(context = context, outputDir = outputDir)

        recorder.start()
        Thread.sleep(50)
        val file = recorder.stop()

        assertNull(file)
    }
}
