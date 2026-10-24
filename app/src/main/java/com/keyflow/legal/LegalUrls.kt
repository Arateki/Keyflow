package com.keyflow.legal

import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale

/**
 * Endereços públicos de documentos legais do KeyFlow (V1-5).
 * Fonte única para os links exibidos nas telas de configuração e divulgação.
 */
object LegalUrls {
    const val BASE_URL = "https://keyflow.arateki.com"

    fun privacyPolicy(language: AppLanguage = AppLocale.current): String = when (language) {
        AppLanguage.PT -> "$BASE_URL/privacidade"
        AppLanguage.EN, AppLanguage.ES -> "$BASE_URL/privacy"
    }

    fun termsOfUse(language: AppLanguage = AppLocale.current): String = when (language) {
        AppLanguage.PT -> "$BASE_URL/termos"
        AppLanguage.EN, AppLanguage.ES -> "$BASE_URL/terms"
    }
}
