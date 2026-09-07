package com.keyflow.ime

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.data.SettingsRepository
import kotlinx.coroutines.runBlocking

/**
 * Guarda as configurações REAIS de quem é dono do aparelho enquanto um teste as sobrescreve, e devolve
 * exatamente o que estava lá.
 *
 * Existe porque o `tearDown` destes testes restaurava `BarsConfig.DEFAULT` + lista de grades VAZIA,
 * assumindo que o device estava zerado. No aparelho pessoal do Yan (que é onde o gate manual roda), cada
 * execução apagava as barras e as grades que ele tinha montado — ele só descobriu ao ver o teclado sem
 * os botões que havia configurado (2026-09-07). "Restaurar o default" não é restaurar: é apagar.
 *
 * O mesmo vale para o IME padrão: `ime reset` devolve ao teclado de fábrica, não ao que a pessoa usava.
 */
class UserSettingsGuard(
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext,
    private val shell: (String) -> String = { cmd ->
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(cmd)
            .let { pfd -> java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes().decodeToString() } }
    },
) {
    private val repo get() = SettingsRepository(context)
    private val uiPrefs get() = context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)

    private var bars: BarsConfig? = null
    private var grids: List<ShortcutGrid>? = null
    private var defaultIme: String? = null

    /** Chame no `@Before`, antes de qualquer escrita do teste. */
    fun capture() {
        runBlocking {
            bars = repo.barsConfigOnce()
            grids = repo.shortcutGridsOnce()
        }
        defaultIme = shell("settings get secure default_input_method").trim().takeIf { it.isNotBlank() && it != "null" }
    }

    /** Chame no `@After`. Sem [capture] antes, não faz nada — melhor não escrever do que escrever errado. */
    fun restore() {
        val savedBars = bars
        val savedGrids = grids
        if (savedBars != null && savedGrids != null) {
            runBlocking {
                repo.saveBarsConfig(savedBars)
                repo.saveShortcutGrids(savedGrids)
            }
        }
        defaultIme?.let { shell("ime set $it") }
    }

    /** Remove uma chave das prefs do IME apenas se ela não existia antes do teste. */
    fun restoreUiPrefFlag(key: String, hadItBefore: Boolean, previous: Boolean) {
        uiPrefs.edit().apply { if (hadItBefore) putBoolean(key, previous) else remove(key) }.apply()
    }

    private companion object {
        const val UI_PREFS = "keyflow_kb_ui"
    }
}
