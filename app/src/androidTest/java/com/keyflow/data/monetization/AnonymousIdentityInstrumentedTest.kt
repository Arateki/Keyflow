package com.keyflow.data.monetization

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class AnonymousIdentityInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "identity_instrumented_test"
    private val blockStore = RecordingBlockStore()

    @After
    fun cleanup() {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun identity_remains_stable_between_instances_and_is_backed_up() = runTest {
        val first = AnonymousIdentity(context, blockStore, prefsName) {
            "550e8400-e29b-41d4-a716-446655440000"
        }.get()
        val second = AnonymousIdentity(context, blockStore, prefsName) {
            error("a recriação não deve gerar outro ID")
        }.get()

        assertEquals(first, second)
        assertEquals(listOf(first), blockStore.writes)

        assertBackupPolicy(com.keyflow.R.xml.backup_rules)
        assertBackupPolicy(com.keyflow.R.xml.data_extraction_rules)
    }

    private fun assertBackupPolicy(resourceId: Int) {
        assertFalse(hasBackupEntry(resourceId, "identity.xml", "include"))
        assertFalse(hasBackupEntry(resourceId, "identity.xml", "exclude"))
        assertFalse(hasBackupEntry(resourceId, "keyflow_settings.preferences_pb", "exclude"))
        assertTrue(hasBackupEntry(resourceId, "keyflow_auth.xml", "exclude"))
        assertFalse(hasBackupTag(resourceId, "include"))
    }

    private fun hasBackupEntry(resourceId: Int, expectedPath: String, expectedTag: String): Boolean {
        val parser = context.resources.getXml(resourceId)
        var found = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == expectedTag) {
                val path = parser.getAttributeValue(null, "path")
                if (path == expectedPath) found = true
            }
        }
        parser.close()
        return found
    }

    private fun hasBackupTag(resourceId: Int, expectedTag: String): Boolean {
        val parser = context.resources.getXml(resourceId)
        var found = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == expectedTag) found = true
        }
        parser.close()
        return found
    }

    private class RecordingBlockStore : IdentityBlockStore {
        val writes = mutableListOf<String>()
        override suspend fun read(): String? = writes.lastOrNull()
        override suspend fun write(identity: String) {
            writes += identity
        }
    }
}
