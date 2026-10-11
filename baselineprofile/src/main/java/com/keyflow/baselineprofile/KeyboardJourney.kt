package com.keyflow.baselineprofile

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** O app medido e o seu teclado. */
const val TARGET = "com.keyflow"
private const val IME = "$TARGET/.ime.KeyFlowKeyboardService"

/**
 * Liga o KeyFlow como teclado e espera o Android confirmar. Chame DEPOIS do `killProcess()`: o force-stop do pacote do teclado
 * ativo faz o Android cair para o Gboard — com o `ime set` antes, metade das voltas abria o teclado errado (2026-10-10). Depois
 * de (re)instalar, o Android leva um instante para registrar o serviço: espera aparecer.
 */
fun MacrobenchmarkScope.useKeyFlowKeyboard() {
    val deadline = System.currentTimeMillis() + 15_000
    while (IME !in device.executeShellCommand("ime list -a -s") && System.currentTimeMillis() < deadline) Thread.sleep(200)
    device.executeShellCommand("ime enable $IME")
    device.executeShellCommand("ime set $IME")
    while (currentIme() != IME && System.currentTimeMillis() < deadline) Thread.sleep(200)
    check(currentIme() == IME) { "o KeyFlow não ficou como teclado ativo (ativo: ${currentIme()})" }
}

private fun MacrobenchmarkScope.currentIme() = device.executeShellCommand("settings get secure default_input_method").trim()

/** Abre a [FieldActivity] (no APK de benchmark, fora do processo do KeyFlow) e espera o campo. */
fun MacrobenchmarkScope.openField() {
    val context = InstrumentationRegistry.getInstrumentation().context
    val intent = Intent(context, FieldActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    check(device.wait(Until.hasObject(By.desc(FieldActivity.FIELD_DESC)), 10_000)) { "o campo do benchmark não apareceu" }
}

/**
 * Toca no campo e espera a janela do teclado assentar no `dumpsys input` (mesmo frame em 2 leituras seguidas). Falha se a janela
 * que abriu não é do KeyFlow — medir o Gboard por engano não pode passar calado.
 */
fun MacrobenchmarkScope.tapFieldAndAwaitKeyboard(): ImeFrame {
    device.findObject(By.desc(FieldActivity.FIELD_DESC)).click()
    val frame = awaitKeyboardWindow(device)
    val keyflowUid = Regex("""uid:(\d+)""").find(
        device.executeShellCommand("cmd package list packages -U $TARGET").lineSequence().firstOrNull { it.startsWith("package:$TARGET ") }.orEmpty(),
    )?.groupValues?.get(1)?.toInt()
    check(frame.ownerUid == null || frame.ownerUid == keyflowUid) { "o teclado que abriu não é o KeyFlow (dono uid ${frame.ownerUid}, KeyFlow $keyflowUid)" }
    return frame
}

/** Fecha o teclado (voltar) e espera a janela dele sumir. */
fun MacrobenchmarkScope.closeKeyboard() {
    device.pressBack()
    val deadline = System.currentTimeMillis() + 5_000
    while (imeFrame(device) != null && System.currentTimeMillis() < deadline) Thread.sleep(100)
}

/** Toca em pontos espalhados pela área do teclado (as teclas são desenhadas num Canvas, sem nós de acessibilidade). */
fun MacrobenchmarkScope.typeSomething(frame: ImeFrame) {
    val rows = listOf(0.45f, 0.62f, 0.78f)
    for (row in rows) {
        for (column in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
            device.click(frame.left + ((frame.right - frame.left) * column).toInt(), frame.top + ((frame.bottom - frame.top) * row).toInt())
        }
    }
    device.waitForIdle()
}

/** A janela do teclado no `dumpsys input`: o frame e o uid de quem a abriu (null se o formato não traz). */
data class ImeFrame(val left: Int, val top: Int, val right: Int, val bottom: Int, val ownerUid: Int? = null)

private fun awaitKeyboardWindow(device: UiDevice): ImeFrame {
    val deadline = System.currentTimeMillis() + 15_000
    var last: ImeFrame? = null
    while (System.currentTimeMillis() < deadline) {
        val now = imeFrame(device)
        // Assentado: no pé da tela, com altura de teclado, igual à leitura anterior (a frio a janela passa por tamanhos de
        // transição — ver scripts/ime-placement.py).
        if (now != null && now == last && now.bottom - now.top > 200 && now.bottom >= device.displayHeight - 200) return now
        last = now
        Thread.sleep(100)
    }
    error("o teclado não assentou em 15 s (último frame: $last)")
}

private val FRAME = Regex("""frame=\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
private val OWNER = Regex("""ownerUid=(\d+)""")

private fun imeFrame(device: UiDevice): ImeFrame? {
    val line = device.executeShellCommand("dumpsys input").lineSequence()
        .firstOrNull { it.contains(" InputMethod'") && it.contains("frame=") && !it.contains("frame=[0,0][0,0]") } ?: return null
    val (_, l, t, r, b) = FRAME.find(line)?.groupValues ?: return null
    return ImeFrame(l.toInt(), t.toInt(), r.toInt(), b.toInt(), OWNER.find(line)?.groupValues?.get(1)?.toInt())
}
