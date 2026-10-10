package com.keyflow.ime

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
        if (KeyFlowKeyboardService.activeViewForTest != null) return
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
    throw AssertionError("KeyFlowKeyboardView não anexou")
}
