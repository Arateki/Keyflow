package com.keyflow.admob

import org.junit.Assert.*
import org.junit.Test

class AdMobIdsTest {
    private val valid = mapOf(
        AdMobIds.APP to "ca-app-pub-0000000000000000~0000000000",
        AdMobIds.BANNER to "ca-app-pub-0000000000000000/0000000001",
        AdMobIds.INTERSTITIAL to "ca-app-pub-0000000000000000/0000000002",
    )

    @Test fun `debug ignora IDs reais e funciona sem configuração`() {
        val debug = AdMobIds.select(true, emptyMap())
        assertEquals(debug, AdMobIds.select(true, valid))
        assertTrue(debug.values.all { it.contains(AdMobIds.TEST_PUBLISHER) })
    }

    @Test fun `release usa os três IDs configurados`() {
        assertEquals(valid, AdMobIds.select(false, valid))
    }

    @Test fun `release trata valor nulo como ausente e debug continua em teste`() {
        for (key in valid.keys) {
            val configured = valid + (key to null)
            val error = assertThrows(IllegalArgumentException::class.java) {
                AdMobIds.select(false, configured)
            }
            assertTrue(error.message!!.contains(key))
            assertEquals(AdMobIds.select(true, emptyMap()), AdMobIds.select(true, configured))
        }
    }

    @Test fun `release rejeita cada ID ausente malformado ou de teste`() {
        for (key in valid.keys) {
            val testId = AdMobIds.select(true, emptyMap()).getValue(key)
            for (invalid in listOf("", " ", "ca-app-pub-123/123", "x${valid.getValue(key)}", testId,
                if (key == AdMobIds.APP) valid.getValue(key).replace('~', '/') else valid.getValue(key).replace('/', '~'))) {
                val error = assertThrows(IllegalArgumentException::class.java) {
                    AdMobIds.select(false, valid + (key to invalid))
                }
                assertTrue(error.message!!.contains(key))
            }
            assertThrows(IllegalArgumentException::class.java) {
                AdMobIds.select(false, valid - key)
            }
        }
    }
}
