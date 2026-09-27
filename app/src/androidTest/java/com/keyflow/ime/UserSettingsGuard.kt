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
 *
 * E vale para o estado expandido/recolhido do teclado (`keyflow_kb_ui.expanded`), que o IME PERSISTE
 * entre sessões: recolhido, `setExpanded(false)` tira a grade da hierarquia, e ela nunca chega a ser
 * medida — todo teste que depende da geometria da grade falha com "grade Canvas ainda sem geometria
 * medida". Isso nunca aparece no emulador (pref virgem = expandido); apareceu no A32 do Yan porque ele
 * tinha deixado o teclado recolhido (2026-09-08, 11 falhas). Quem depende da grade chama
 * [forceExpanded] no `@Before`; [restore] devolve o estado que a pessoa deixou.
 *
 * E vale para `show_ime_with_hard_keyboard`, que [capture] força para 1 porque SEM ela não existe
 * teclado nenhum para testar — ver o comentário no próprio [capture].
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
    private var hadExpanded = false
    private var expanded = true
    private var capturedImeWithHardKeyboard = false
    private var imeWithHardKeyboard: String? = null

    /** Chame no `@Before`, antes de qualquer escrita do teste. */
    fun capture() {
        runBlocking {
            bars = repo.barsConfigOnce()
            grids = repo.shortcutGridsOnce()
        }
        defaultIme = shell("settings get secure default_input_method").trim().takeIf { it.isNotBlank() && it != "null" }
        hadExpanded = uiPrefs.contains(KEY_EXPANDED)
        expanded = uiPrefs.getBoolean(KEY_EXPANDED, true)
        // O emulador do Android 17 se declara COM teclado físico — a `Configuration` que chega ao
        // serviço é `qwerty/v/v` mesmo com `hw.keyboard = no` no AVD — e a imagem vem com
        // `show_ime_with_hard_keyboard = 0`. Nessa combinação o `onEvaluateInputViewShown()` padrão do
        // `InputMethodService` responde `false`: o serviço nunca recebe o pedido de exibição
        // (`mShowInputRequested=false`), `onCreateInputView` não roda e não há teclado na tela. Medido em
        // 2026-09-09 comparando com o Gboard no MESMO campo e emulador: ele aparece porque sobrescreve
        // aquele método; o KeyFlow usa o padrão. Era essa a causa das 27 falhas "KeyFlowKeyboardView não
        // anexou" que estavam registradas como limitação do harness. O A32 do Yan já tem a chave em 1, e
        // por isso o gate no aparelho nunca viu o problema.
        imeWithHardKeyboard = shell("settings get secure show_ime_with_hard_keyboard")
            .trim().takeIf { it.isNotBlank() && it != "null" }
        capturedImeWithHardKeyboard = true
        shell("settings put secure show_ime_with_hard_keyboard 1")
    }

    /**
     * Abre o teclado expandido, para que a grade entre na hierarquia e seja medida. Sem [capture] antes,
     * não faz nada — senão o `@After` não teria como devolver o estado real.
     */
    fun forceExpanded() {
        if (!hasCaptured) return
        uiPrefs.edit().putBoolean(KEY_EXPANDED, true).commit()
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
        if (capturedImeWithHardKeyboard) {
            val saved = imeWithHardKeyboard
            if (saved != null) shell("settings put secure show_ime_with_hard_keyboard $saved")
            else shell("settings delete secure show_ime_with_hard_keyboard")
        }
        if (hasCaptured) restoreUiPrefFlag(KEY_EXPANDED, hadExpanded, expanded)
    }

    private val hasCaptured: Boolean get() = bars != null && grids != null

    /** Remove uma chave das prefs do IME apenas se ela não existia antes do teste. */
    fun restoreUiPrefFlag(key: String, hadItBefore: Boolean, previous: Boolean) {
        uiPrefs.edit().apply { if (hadItBefore) putBoolean(key, previous) else remove(key) }.apply()
    }

    private companion object {
        const val UI_PREFS = "keyflow_kb_ui"
        const val KEY_EXPANDED = "expanded"
    }
}
