package com.keyflow.ime

import android.content.Context
import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.InsertOutcome
import com.keyflow.core.KeyFlowController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvisibleKeyboardServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetController()
    }

    @After
    fun tearDown() {
        resetController()
    }

    @Test
    fun inputViewHasZeroSize() {
        val view = InvisibleKeyboardService.createInputView(context)

        assertEquals(0, view.layoutParams.width)
        assertEquals(0, view.layoutParams.height)
        assertEquals(0, view.minimumHeight)
    }

    @Test
    fun registeredInserterCommitsTextThroughCurrentInputConnection() {
        val inputConnection = RecordingInputConnection(View(context))

        InvisibleKeyboardService.registerCurrentInputConnection {
            inputConnection
        }

        val outcome = KeyFlowController.insertOrHold("hello")

        assertSame(InsertOutcome.Inserted, outcome)
        assertEquals(listOf("hello"), inputConnection.committed)
        assertTrue(KeyFlowController.targetAvailable.value)
        assertNull(KeyFlowController.pending.value)
    }

    @Test
    fun rejectedInputConnectionCommitKeepsTextPending() {
        InvisibleKeyboardService.registerCurrentInputConnection {
            RecordingInputConnection(View(context), acceptCommit = false)
        }

        val outcome = KeyFlowController.insertOrHold("retry")

        assertSame(InsertOutcome.Held, outcome)
        assertEquals("retry", KeyFlowController.pending.value)
    }

    @Test
    fun nullInputConnectionKeepsTextPending() {
        InvisibleKeyboardService.registerCurrentInputConnection {
            null
        }

        val outcome = KeyFlowController.insertOrHold("later")

        assertSame(InsertOutcome.Held, outcome)
        assertEquals("later", KeyFlowController.pending.value)
    }

    @Test
    fun clearRegistrationMarksTargetUnavailable() {
        InvisibleKeyboardService.registerCurrentInputConnection {
            RecordingInputConnection(View(context))
        }
        assertTrue(KeyFlowController.targetAvailable.value)

        InvisibleKeyboardService.clearRegistration()

        assertFalse(KeyFlowController.targetAvailable.value)
    }

    private fun resetController() {
        KeyFlowController.registerInserter { true }
        KeyFlowController.flushPending()
        KeyFlowController.clearInserter()
    }

    private class RecordingInputConnection(
        targetView: View,
        private val acceptCommit: Boolean = true,
    ) : BaseInputConnection(targetView, false) {
        val committed = mutableListOf<String>()

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            text?.let { committed += it.toString() }
            return acceptCommit
        }
    }
}
