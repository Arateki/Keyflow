package com.keyflow.legal

import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Test

class LegalUrlsTest {

    @Test
    fun portugueseUsesLocalizedEndpoints() {
        assertEquals("https://keyflow.arateki.com/privacidade", LegalUrls.privacyPolicy(AppLanguage.PT))
        assertEquals("https://keyflow.arateki.com/termos", LegalUrls.termsOfUse(AppLanguage.PT))
    }

    @Test
    fun englishUsesEnglishEndpoints() {
        assertEquals("https://keyflow.arateki.com/privacy", LegalUrls.privacyPolicy(AppLanguage.EN))
        assertEquals("https://keyflow.arateki.com/terms", LegalUrls.termsOfUse(AppLanguage.EN))
    }

    @Test
    fun spanishUsesEnglishEndpoints() {
        assertEquals("https://keyflow.arateki.com/privacy", LegalUrls.privacyPolicy(AppLanguage.ES))
        assertEquals("https://keyflow.arateki.com/terms", LegalUrls.termsOfUse(AppLanguage.ES))
    }

    @Test
    fun defaultParameterTracksCurrentLocale() {
        val previousPinned = AppLocale.pinned
        try {
            AppLocale.pinned = AppLanguage.PT
            assertEquals("https://keyflow.arateki.com/privacidade", LegalUrls.privacyPolicy())
            assertEquals("https://keyflow.arateki.com/termos", LegalUrls.termsOfUse())

            AppLocale.pinned = AppLanguage.EN
            assertEquals("https://keyflow.arateki.com/privacy", LegalUrls.privacyPolicy())
            assertEquals("https://keyflow.arateki.com/terms", LegalUrls.termsOfUse())

            AppLocale.pinned = AppLanguage.ES
            assertEquals("https://keyflow.arateki.com/privacy", LegalUrls.privacyPolicy())
            assertEquals("https://keyflow.arateki.com/terms", LegalUrls.termsOfUse())
        } finally {
            AppLocale.pinned = previousPinned
        }
    }

    @Test
    fun singleBaseUrlConstantIsUsed() {
        assertEquals("https://keyflow.arateki.com", LegalUrls.BASE_URL)
        val declaredConstants = LegalUrls::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        assertEquals(1, declaredConstants.size)
        assertEquals("BASE_URL", declaredConstants.single().name)
    }
}
