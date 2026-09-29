package com.keyflow

import android.app.Application
import com.keyflow.data.SettingsRepository
import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class App : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // Idioma da interface para o processo inteiro (app, orbe, notificação, teclado): um lugar só.
        scope.launch {
            SettingsRepository(this@App).appLanguageFlow().distinctUntilChanged().collect { code ->
                AppLocale.language = AppLanguage.of(code)
            }
        }
    }
}
