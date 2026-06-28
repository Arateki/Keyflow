package com.keyflow.overlay

import android.app.Notification
import android.content.Context
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.ButtonState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingNotificationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun buildProducesOngoingNotificationWithCustomView() {
        val notif = RecordingNotification(context, "keyflow_overlay").build(ButtonState.RECORDING)

        assertTrue("deve ser ongoing", notif.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull("deve ter custom big content view", notif.bigContentView)
    }

    @Test
    fun galleryRemoteViewsInflateForRecordingAndPaused() {
        val recording = RecordingNotification(context, "gallery")
            .galleryRemoteViews(ButtonState.RECORDING, frozenSeconds = 12)
            .apply(context, FrameLayout(context))
        assertNotNull(recording)

        val paused = RecordingNotification(context, "gallery")
            .galleryRemoteViews(ButtonState.PAUSED, frozenSeconds = 12)
            .apply(context, FrameLayout(context))
        assertNotNull(paused)
    }

    @Test
    fun pauseLabelAndTitleReflectState() {
        assertEquals("Pausar", RecordingNotification.pauseLabel(ButtonState.RECORDING))
        assertEquals("Retomar", RecordingNotification.pauseLabel(ButtonState.PAUSED))
        assertEquals("● Gravando", RecordingNotification.titleFor(ButtonState.RECORDING))
        assertEquals("⏸ Pausado", RecordingNotification.titleFor(ButtonState.PAUSED))
    }
}
