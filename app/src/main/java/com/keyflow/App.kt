package com.keyflow

import android.app.Application
import com.keyflow.audio.cleanupStaleRecordings
import com.keyflow.auth.AuthServices
import com.keyflow.data.SettingsRepository
import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import com.keyflow.vision.service.VisionProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

class App : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val authWarmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A autenticação dos provedores de nuvem: um grafo só para o processo (ver [AuthServices]); nada é criado até alguém pedir. */
    val authServices: AuthServices by lazy { AuthServices(this) }

    override fun onCreate() {
        super.onCreate()
        // O :vision recebe tudo pelo AIDL e não abre cofre, Settings nem DataStore (design da Visão §6): este onCreate roda em todo
        // processo do app, então a guarda vem antes de qualquer acesso (VisionRulesTest trava a ordem).
        if (VisionProcess.isCurrent()) return
        // Disco/rede na thread principal e recursos não fechados, só no debug — ver StrictModeGuard.
        if (BuildConfig.DEBUG) StrictModeGuard.install()
        authWarmupScope.launch {
            cleanupStaleRecordings(File(cacheDir, "keyflow-recordings"), System.currentTimeMillis())
        }
        // Cria/abre o cofre fora da main antes da primeira tela consultar credenciais.
        authWarmupScope.launch {
            authServices.store
            SettingsRepository(this@App).secretVault()
        }
        // Idioma da interface para o processo inteiro (app, orbe, notificação, teclado): um lugar só.
        scope.launch {
            SettingsRepository(this@App).appLanguageFlow().distinctUntilChanged().collect { code ->
                AppLocale.language = AppLanguage.of(code)
            }
        }
    }
}
