package com.keyflow.data.monetization

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteConfigRepositoryInstrumentedTest {
    private lateinit var cache: SharedPreferencesRemoteConfigCache

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("keyflow_remote_documents", 0).edit().clear().commit()
        cache = SharedPreferencesRemoteConfigCache(context)
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("keyflow_remote_documents", 0).edit().clear().commit()
    }

    @Test
    fun cache_persiste_corpo_e_etag_no_disco() {
        cache.write("config", RemoteCacheEntry("{\"killSwitch\":false}", "\"v1\""))

        val saved = cache.read("config")
        assertNotNull(saved)
        assertEquals("{\"killSwitch\":false}", saved?.body)
        assertEquals("\"v1\"", saved?.etag)
    }
}
