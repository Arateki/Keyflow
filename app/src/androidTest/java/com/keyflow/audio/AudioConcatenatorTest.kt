package com.keyflow.audio

import android.Manifest
import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AudioConcatenatorTest {

    private lateinit var context: Context
    private lateinit var outputDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
        outputDir = File(context.cacheDir, "audio-concat-test/${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        outputDir.deleteRecursively()
    }

    private fun record(durationMs: Long): File {
        val recorder = AudioRecorder(context = context, outputDir = outputDir)
        recorder.start()
        Thread.sleep(durationMs)
        return requireNotNull(recorder.stop()) { "gravação de teste veio nula" }
    }

    private fun durationMsOf(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    @Test
    fun concatenatesTwoSegmentsIntoOnePlayableFile() {
        val first = record(700)
        val second = record(500)

        val merged = AudioConcatenator.concat(listOf(first, second), outputDir)

        assertTrue(merged.exists())
        assertTrue(merged.length() > 0)
        val mergedDuration = durationMsOf(merged)
        val expected = durationMsOf(first) + durationMsOf(second)
        // Tolerância generosa: a estimativa de deslocamento entre segmentos não é sample-accurate.
        assertTrue(
            "duração fundida ($mergedDuration ms) deveria ficar perto da soma dos segmentos ($expected ms)",
            mergedDuration in (expected - 500)..(expected + 500),
        )
    }

    @Test
    fun concatenatesThreeSegments() {
        val segments = listOf(record(700), record(700), record(700))

        val merged = AudioConcatenator.concat(segments, outputDir)

        assertNotNull(merged)
        assertTrue(merged.length() > 0)
        val mergedDuration = durationMsOf(merged)
        val expected = segments.sumOf { durationMsOf(it) }
        assertTrue(
            "duração fundida ($mergedDuration ms) deveria ficar perto da soma dos 3 segmentos ($expected ms)",
            mergedDuration in (expected - 700)..(expected + 700),
        )
    }
}
