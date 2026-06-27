package com.keyflow.ui

import android.Manifest
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.keyflow.R
import com.keyflow.overlay.OverlayService
import org.hamcrest.CoreMatchers.containsString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupActivityTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        context.stopService(OverlayService.intent(context))
    }

    @After
    fun tearDown() {
        context.stopService(OverlayService.intent(context))
    }

    @Test
    fun savesConfigFromForm() {
        ActivityScenario.launch(SetupActivity::class.java).use {
            onView(withId(R.id.keyflow_endpoint_input))
                .perform(scrollTo(), replaceText("https://example.test/v1/audio/transcriptions"), closeSoftKeyboard())
            onView(withId(R.id.keyflow_auth_header_name_input))
                .perform(scrollTo(), replaceText("X-API-Key"), closeSoftKeyboard())
            onView(withId(R.id.keyflow_auth_header_value_input))
                .perform(scrollTo(), replaceText("secret-token"), closeSoftKeyboard())
            onView(withId(R.id.keyflow_model_input))
                .perform(scrollTo(), replaceText("whisper-large-v3"), closeSoftKeyboard())
            onView(withId(R.id.keyflow_language_input))
                .perform(scrollTo(), replaceText("pt"), closeSoftKeyboard())
            onView(withId(R.id.keyflow_push_to_talk_radio))
                .perform(scrollTo(), click())

            onView(withId(R.id.keyflow_save_config_button))
                .perform(scrollTo(), click())

            onView(withId(R.id.keyflow_config_status))
                .check(matches(withText("Configuração salva")))
        }
    }

    @Test
    fun checklistReflectsGrantedPermissions() {
        ActivityScenario.launch(SetupActivity::class.java).use {
            onView(withId(R.id.keyflow_permission_microphone))
                .check(matches(withText(containsString("concedido"))))
            onView(withId(R.id.keyflow_permission_overlay))
                .check(matches(withText(containsString("concedido"))))
            onView(withId(R.id.keyflow_permission_keyboard))
                .check(matches(withText(containsString("pendente"))))
        }
    }

    @Test
    fun overlayToggleStartsAndStopsForegroundService() {
        ActivityScenario.launch(SetupActivity::class.java).use {
            onView(withId(R.id.keyflow_overlay_toggle))
                .perform(scrollTo(), click())
            waitUntil { OverlayService.isRunningForUi }

            onView(withId(R.id.keyflow_overlay_status))
                .check(matches(withText("Overlay ativo")))

            onView(withId(R.id.keyflow_overlay_toggle))
                .perform(scrollTo(), click())
            waitUntil { !OverlayService.isRunningForUi }
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        error("Condition was not met before timeout")
    }

    private fun shell(command: String) {
        val fd: ParcelFileDescriptor = InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        fd.close()
    }
}
