package com.keyflow.ime

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * Prazo para a `KeyFlowKeyboardView` anexar depois do `ime enable` + `ime set` e do toque no campo.
 *
 * A primeira vez que o sistema sobe o IME num aparelho recém-ligado inclui criar o processo do serviço e
 * inflar a view — no emulador do CI isso passou dos 5 s que as outras esperas usam, e só o PRIMEIRO
 * teste de teclado da rodada falhava (o seguinte, com o IME já quente, passava). A espera volta assim
 * que a view anexa, então a folga não custa nada quando o IME já está de pé.
 */
internal const val IME_COLD_ATTACH_TIMEOUT_MS = 30_000L

/** De quanto em quanto tempo, sem a view anexada, o probe volta para a frente e o campo é tocado de novo. */
private const val IME_ATTACH_NUDGE_MS = 6_000L

private const val KEYFLOW_IME = "com.keyflow/.ime.KeyFlowKeyboardService"

/**
 * Espera a view do teclado anexar ao campo do `ImeProbeActivity`. Enquanto não anexa, a cada
 * [IME_ATTACH_NUDGE_MS] traz o probe de volta ([relaunch]) e toca no campo de novo.
 *
 * Medido no CI (2026-09-25, emulador recém-ligado): o app do Google restaurou uma janela por cima do
 * probe logo depois de ele abrir; o único toque no campo caiu no vazio, nenhum IME recebeu o campo e o
 * teste esperou 30 s por um teclado que não tinha como aparecer. Tocar de novo reproduz o que o usuário
 * faria e não mascara falha do IME: se o teclado não anexa com o campo em foco, o prazo estoura igual.
 *
 * Antes de tocar de novo, confere se o KeyFlow ainda é o IME selecionado. Medido no A32 (2026-09-27,
 * 1 em 47 trocas): o `UserSettingsGuard.restore()` do teste anterior devolve o teclado Samsung, que sobe
 * a frio e, no fim da inicialização, regrava `default_input_method` com o valor que leu antes. O
 * `ime set` deste teste caiu 2 ms antes dessa gravação, o sistema voltou para o Samsung e nenhum toque
 * no campo trazia o KeyFlow de volta.
 *
 * O `ime enable` também é repetido: logo depois de (re)instalar o app, o sistema ainda não registrou o
 * teclado e recusa os dois comandos ("unrecognized IME ID") — no emulador lento da esteira de
 * instabilidade isso durou mais de 75 s (2026-10-10). Só o `ime set` de novo não bastava: sem o
 * `enable` aceito, o KeyFlow nunca virava o teclado. O toque é tolerante ao campo recriado pelo
 * relançamento (StaleObjectException derrubava a espera inteira).
 */
internal fun awaitKeyboardAttached(device: UiDevice, relaunch: () -> Unit) {
    val deadline = System.currentTimeMillis() + IME_COLD_ATTACH_TIMEOUT_MS
    var nextNudge = System.currentTimeMillis() + IME_ATTACH_NUDGE_MS
    while (System.currentTimeMillis() < deadline) {
        if (KeyFlowKeyboardService.activeViewForTest != null) break
        if (System.currentTimeMillis() >= nextNudge) {
            if (device.executeShellCommand("settings get secure default_input_method").trim() != KEYFLOW_IME) {
                device.executeShellCommand("ime enable $KEYFLOW_IME")
                device.executeShellCommand("ime set $KEYFLOW_IME")
            }
            relaunch()
            device.wait(Until.hasObject(By.desc("ime_probe_field")), IME_ATTACH_NUDGE_MS)
            runCatching { device.findObject(By.desc("ime_probe_field"))?.click() }
            nextNudge = System.currentTimeMillis() + IME_ATTACH_NUDGE_MS
        }
        Thread.sleep(50)
    }
    if (KeyFlowKeyboardService.activeViewForTest == null) throw AssertionError("KeyFlowKeyboardView não anexou")
    awaitImeWindowSettled(device)
}

/** Prazo para a janela do teclado assentar onde a view está ([awaitImeWindowSettled]). */
internal const val IME_SETTLE_TIMEOUT_MS = 10_000L

/**
 * Espera a janela do teclado ficar, para o sistema de entrada, exatamente onde a própria view acha que está — em duas
 * leituras seguidas. Chamar antes de calcular um toque pela posição da view.
 *
 * Esteira de instabilidade (2026-10-10): no emulador sob carga a camada da animação de entrada do teclado chegou a ficar
 * 126 px abaixo (a barra de navegação que o teclado desenha) ou no topo da tela, com o gerenciador de janelas e a view
 * guardando a posição certa — por um instante (campo em outro processo) ou por segundos (campo no processo do teclado).
 * O toque calculado pela view caía fora da grade: na barra, no mic, no campo. A captura na hora da falha mostrou o
 * teclado desenhado fora do lugar, não só a área de toque.
 */
internal fun awaitImeWindowSettled(device: UiDevice, timeoutMs: Long = IME_SETTLE_TIMEOUT_MS) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var stable = 0
    var last = ""
    while (System.currentTimeMillis() < deadline) {
        val expected = keyboardWindowOnScreen()
        val seen = imeInputFrame(device)
        last = "sistema de entrada=$seen, view=$expected"
        if (expected != null && seen == expected) {
            if (++stable >= 2) return
        } else {
            stable = 0
        }
        Thread.sleep(100)
    }
    throw AssertionError("a janela do teclado não assentou onde a view está em ${timeoutMs / 1000} s ($last)")
}

/** A janela do teclado como a view a vê (raiz da hierarquia), no formato do `dumpsys input`: `[l,t][r,b]`. */
private fun keyboardWindowOnScreen(): String? {
    var frame: String? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val root = KeyFlowKeyboardService.activeViewForTest?.rootView ?: return@runOnMainSync
        if (!root.isShown || root.width == 0) return@runOnMainSync
        val loc = IntArray(2).also { root.getLocationOnScreen(it) }
        frame = "[${loc[0]},${loc[1]}][${loc[0] + root.width},${loc[1] + root.height}]"
    }
    return frame
}

/** O frame da janela do IME para o InputDispatcher (`dumpsys input`), ou `null` se ela não está listada. */
internal fun imeInputFrame(device: UiDevice): String? =
    device.executeShellCommand("dumpsys input").lineSequence()
        .firstOrNull { it.contains(" InputMethod'") && it.contains("frame=") }
        ?.let { Regex("frame=(\\[[^]]*]\\[[^]]*])").find(it)?.groupValues?.get(1) }
