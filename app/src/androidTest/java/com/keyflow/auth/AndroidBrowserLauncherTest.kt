package com.keyflow.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** O `BrowserLauncher` do Android: `ACTION_VIEW` em nova tarefa, só `https`, e sem navegador vira erro de autenticação. */
@RunWith(AndroidJUnit4::class)
class AndroidBrowserLauncherTest {

    private class Recording(base: Context, private val failWith: Exception? = null) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            failWith?.let { throw it }
            started += intent
        }
    }

    private val base: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun opensAnHttpsUrlWithActionViewInANewTask() {
        val context = Recording(base)

        AndroidBrowserLauncher(context).open("https://auth.exemplo.test/authorize?state=abc")

        val intent = context.started.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://auth.exemplo.test/authorize?state=abc", intent.dataString)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun refusesAnythingThatIsNotHttps() {
        val context = Recording(base)
        listOf("http://exemplo.test", "intent://x", "file:///sdcard/x", "javascript:alert(1)", "").forEach { url ->
            try {
                AndroidBrowserLauncher(context).open(url)
                fail("deveria recusar $url")
            } catch (e: AuthException) {
                assertEquals(AuthErrorKind.UNKNOWN, e.kind)
            }
        }
        assertTrue(context.started.isEmpty())
    }

    @Test
    fun noBrowserInstalledIsAnAuthErrorNotACrash() {
        val context = Recording(base, failWith = android.content.ActivityNotFoundException("sem navegador"))
        try {
            AndroidBrowserLauncher(context).open("https://exemplo.test")
            fail("deveria falhar")
        } catch (e: AuthException) {
            assertEquals(AuthErrorKind.UNKNOWN, e.kind)
        }
    }
}
