package com.keyflow.data.monetization

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class EntitlementsRepositoryInstrumentedTest {
    @Test
    fun cache_datastore_sobrevive_a_uma_nova_repository() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "entitlements-test-${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        val cache = DataStoreEntitlementsCache(dataStore)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = EntitlementsRepository(
            context = null,
            account = { KeyFlowAccount(27, adsOff = true, skinsUnlocked = false) },
            localUnlock = MutableStateFlow(false),
            cache = cache,
            scope = scope,
        )
        assertEquals(Entitlements(27, adsOff = true, skinsUnlocked = false), first.refresh())
        first.close()

        val offline = EntitlementsRepository(
            context = null,
            account = { error("API fora") },
            localUnlock = MutableStateFlow(false),
            cache = cache,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        assertEquals(Entitlements(27, adsOff = true, skinsUnlocked = false), offline.refresh())
        offline.close()
        file.delete()
    }
}
