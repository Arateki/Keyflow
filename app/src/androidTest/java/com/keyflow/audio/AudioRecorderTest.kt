package com.keyflow.audio

import android.Manifest
import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun pauseResumeProducesPlayableFile() {
        val recorder = AudioRecorder(context = context, outputDir = outputDir)

        recorder.start()
        Thread.sleep(400)
        recorder.pause()
        assertEquals(RecorderState.PAUSED, recorder.state)
        Thread.sleep(300)
        recorder.resume()
        assertEquals(RecorderState.RECORDING, recorder.state)
        Thread.sleep(400)
        val file = recorder.stop()

        assertNotNull(file)
        assertTrue(file!!.length() > 0)
        // Arquivo reproduzível: o retriever extrai uma duração positiva (REC-06).
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            assertTrue("duração deveria ser > 0, foi $duration", duration > 0)
        } finally {
            retriever.release()
        }
    }

    @Test
    fun stateTransitionsThroughPauseResume() {
        val recorder = AudioRecorder(context = context, outputDir = outputDir)
        assertEquals(RecorderState.IDLE, recorder.state)

        recorder.start()
        assertEquals(RecorderState.RECORDING, recorder.state)
        Thread.sleep(300)
        recorder.pause()
        assertEquals(RecorderState.PAUSED, recorder.state)
        recorder.resume()
        assertEquals(RecorderState.RECORDING, recorder.state)
        Thread.sleep(200)
        recorder.stop()
        assertEquals(RecorderState.IDLE, recorder.state)
    }

    @Test
    fun discardsVeryShortClip() {
        val recorder = AudioRecorder(context = context, outputDir = outputDir)

        recorder.start()
        Thread.sleep(50)
        val file = recorder.stop()

        assertNull(file)
    }

    // ── modo segmentado (PAR-02, NM-T15) ─────────────────────────────────────

    private fun durationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    @Test
    fun segmentedTwoPausesGiveThreePlayableFilesAndTheDurationAccumulates() {
        var now = 0L
        val recorder = AudioRecorder(context = context, outputDir = outputDir, clock = { now })

        recorder.start(segmented = true)
        Thread.sleep(600); now += 600
        val first = recorder.pause()
        assertEquals(RecorderState.PAUSED, recorder.state)
        Thread.sleep(300); now += 300 // pausa não conta
        recorder.resume()
        Thread.sleep(600); now += 600
        val second = recorder.pause()
        recorder.resume()
        Thread.sleep(600); now += 600
        assertEquals(1_800L, recorder.activeDurationMs())
        val third = recorder.stop()

        val files = listOf(first, second, third)
        files.forEach { assertNotNull(it) }
        assertEquals(3, files.toSet().size)
        files.forEach { file ->
            assertTrue("${file!!.name} deveria ter áudio", durationMs(file) > 0)
        }
        assertEquals(RecorderState.IDLE, recorder.state)
    }

    @Test
    fun segmentedStopWhilePausedReturnsNothingNewAndKeepsTheSegments() {
        var now = 0L
        val recorder = AudioRecorder(context = context, outputDir = outputDir, clock = { now })

        recorder.start(segmented = true)
        Thread.sleep(600); now += 600
        val first = recorder.pause()
        val segmentsBeforeStop = recorder.segments

        assertNull(recorder.stop())
        assertEquals(listOf(first), segmentsBeforeStop)
        assertTrue(first!!.exists())
    }

    @Test
    fun segmentedTooShortSegmentIsDropped() {
        var now = 0L
        val recorder = AudioRecorder(context = context, outputDir = outputDir, clock = { now })

        recorder.start(segmented = true)
        Thread.sleep(600); now += 600
        recorder.pause()
        recorder.resume()
        Thread.sleep(100); now += 100 // abaixo do mínimo de 300 ms
        assertNull(recorder.pause())

        assertEquals(1, recorder.segments.size)
        recorder.discard()
    }

    @Test
    fun segmentedDiscardDeletesEverySegment() {
        var now = 0L
        val recorder = AudioRecorder(context = context, outputDir = outputDir, clock = { now })

        recorder.start(segmented = true)
        Thread.sleep(600); now += 600
        val first = recorder.pause()
        recorder.resume()
        Thread.sleep(400); now += 400

        recorder.discard()

        assertTrue(first != null && !first.exists())
        assertTrue(outputDir.listFiles().orEmpty().none { it.name.endsWith(".m4a") })
        assertEquals(RecorderState.IDLE, recorder.state)
    }
}
