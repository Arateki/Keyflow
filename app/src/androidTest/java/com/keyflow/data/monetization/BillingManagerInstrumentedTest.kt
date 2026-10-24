package com.keyflow.data.monetization

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BillingManagerInstrumentedTest {
    @Test
    fun play_billing_conecta_ou_degrada_sem_crash() = runTest {
        val manager = BillingManager(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(15_000) { manager.connect() }
            }
            assertTrue(manager.state.value is BillingState.Ready || manager.state.value is BillingState.Unavailable)
        } finally {
            manager.close()
        }
    }
}
