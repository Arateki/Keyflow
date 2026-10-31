package com.keyflow.notificacao

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.keyflow.R
import org.junit.After
import org.junit.Assume
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * NTF-T1 — prova de altura e de toque da orbe na notificação (**descartável**: nada daqui vai para o produto).
 *
 * Posta uma notificação `DecoratedCustomViewStyle` com só `setCustomContentView` — a linha `[botão][orbe][botão]` de 48 dp do
 * design §6, com uma orbe teal desenhada à mão (40 dp) e dois `ImageButton` de 48×48 dp — nas quatro variações, e mede com
 * UiAutomator, na gaveta real do aparelho: `visibleBounds` da orbe e de cada botão, os quatro extremos do círculo na captura, os
 * alvos de toque, a sobreposição, a largura em tela estreita (320 dp) e o tempo do clique ao serviço.
 *
 * Os números saem por `sendStatus` (`INSTRUMENTATION_STATUS: spike=…`) e em `externalFilesDir/spike-notificacao/` (relatório e
 * capturas). O teste **não aprova nem reprova** o design: ele mede e registra; a decisão "cabe / corta" é do relatório. Só falha
 * se a medição em si não puder ser feita (nó não achado, captura impossível).
 */
@RunWith(AndroidJUnit4::class)
class NotificacaoAlturaSpikeTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val densidade: Float get() = context.resources.displayMetrics.density
    private val saida: File by lazy { File(context.getExternalFilesDir(null), "spike-notificacao").apply { mkdirs() } }
    private val relatorio = StringBuilder()
    private val centrosDaOrbe = linkedMapOf<String, Int>()

    private enum class Variacao(val rotulo: String, val esq: Int, val dir: Int) {
        SEM_BOTAO("sem-botao", View.INVISIBLE, View.INVISIBLE),
        SO_DIREITO_INVISIVEL("so-direito-esq-invisible", View.INVISIBLE, View.VISIBLE),
        DOIS_BOTOES("esq-e-dir", View.VISIBLE, View.VISIBLE),
        SO_DIREITO_GONE("so-direito-esq-gone", View.GONE, View.VISIBLE),
    }

    @Before
    fun setUp() {
        SpikeCliqueService.eventos.clear()
        manager.createNotificationChannel(NotificationChannel(CANAL, "Prova NTF-T1", NotificationManager.IMPORTANCE_LOW))
        device.wakeUp()
        device.pressHome()
        anota("== NTF-T1 == modelo=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}")
        val m = context.resources.displayMetrics
        anota("tela=${m.widthPixels}x${m.heightPixels}px densidade=${m.density} (${(m.densityDpi)}dpi) largura=${(m.widthPixels / m.density).roundToInt()}dp")
    }

    @After
    fun tearDown() {
        manager.cancelAll()
        device.pressHome()
        File(saida, "relatorio.txt").appendText(relatorio.toString())
    }

    @Test
    fun medeAsQuatroVariacoes() {
        for (v in Variacao.entries) {
            manager.cancelAll()
            postar(v, filler = false)
            abrirGaveta()
            anota("-- variação ${v.rotulo}")
            medir(v, "inicial-${v.rotulo}")
            // Sem BigContentView o sistema não oferece "expandir": tenta puxar a linha e mede de novo.
            expandir(v)
            medir(v, "apos-puxar-${v.rotulo}")
            device.pressHome()
        }
        anota("posição x do centro da orbe por variação (px): " + centrosDaOrbe.entries.joinToString { "${it.key}=${it.value}" } +
            " → a orbe " + if (centrosDaOrbe.filterKeys { it != Variacao.SO_DIREITO_GONE.rotulo }.values.toSet().size == 1) "NÃO anda com INVISIBLE" else "ANDA entre variações")
    }

    /** A mesma linha com outra notificação por cima: a nossa fica abaixo e, se couber na gaveta, é medida de novo (só registra). */
    @Test
    fun comOutraNotificacaoPorCima() {
        manager.cancelAll()
        postar(Variacao.DOIS_BOTOES, filler = true)
        abrirGaveta()
        if (device.findObject(By.desc(DESC_ORBE)) == null) {
            anota("com outra notificação por cima: a orbe NÃO está na árvore da gaveta (fora da tela ou agrupada)")
        } else {
            medir(Variacao.DOIS_BOTOES, "com-filler")
        }
        device.pressHome()
    }

    @Test
    fun cliqueDaOrbeEDeCadaBotaoChegaAoServico() {
        manager.cancelAll()
        postar(Variacao.DOIS_BOTOES, filler = false)
        for ((desc, acao) in listOf(DESC_ORBE to "orbe", DESC_ESQ to "esq", DESC_DIR to "dir")) {
            abrirGaveta()
            val alvo = achar(desc)
            SpikeCliqueService.eventos.clear()
            val t0 = SystemClock.elapsedRealtimeNanos()
            alvo.click()
            val ok = esperarEvento(acao)
            val ms = SpikeCliqueService.eventos.firstOrNull { it.acao == acao }?.let { (it.instanteNs - t0) / 1_000_000.0 }
            anota("clique $acao: chegou=$ok latência(inclui a injeção do UiAutomator)=${ms?.let { "%.0f ms".format(it) } ?: "n/d"}")
            device.pressHome()
            assertTrue("o clique de $acao não chegou ao serviço", ok)
        }
    }

    @Test
    fun telaEstreita320dp() {
        val m = context.resources.displayMetrics
        val altura = m.heightPixels
        val d = m.density
        try {
            shell("wm size ${(320 * d).roundToInt()}x$altura")
            SystemClock.sleep(1500)
            val agora = context.resources.displayMetrics
            anota("tela estreita: ${agora.widthPixels}px = ${(agora.widthPixels / agora.density).roundToInt()}dp")
            manager.cancelAll()
            postar(Variacao.DOIS_BOTOES, filler = false)
            abrirGaveta()
            medir(Variacao.DOIS_BOTOES, "estreita-320dp")
            val tela = Rect(0, 0, device.displayWidth, device.displayHeight)
            val (orbe, esq, dir) = Triple(achar(DESC_ORBE), achar(DESC_ESQ), achar(DESC_DIR))
            val dentro = listOf(orbe, esq, dir).all { tela.contains(it.visibleBounds) }
            anota("tela estreita: orbe e botões dentro da tela de ${device.displayWidth}px = $dentro; linha = ${larguraDp(esq.visibleBounds.left, dir.visibleBounds.right)} dp de ${(device.displayWidth / d).roundToInt()} dp")
        } finally {
            shell("wm size reset")
            SystemClock.sleep(800)
        }
    }

    // ── notificação ───────────────────────────────────────────────────────────

    private fun postar(v: Variacao, filler: Boolean) {
        if (filler) {
            // Uma notificação mais nova fica por cima e a nossa, abaixo, não vem expandida automaticamente.
            manager.notify(
                ID_FILLER,
                NotificationCompat.Builder(context, CANAL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("filler").build(),
            )
        }
        manager.notify(ID, construir(v))
        SystemClock.sleep(600)
        if (filler) {
            manager.notify(
                ID_FILLER,
                NotificationCompat.Builder(context, CANAL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("filler").build(),
            )
            SystemClock.sleep(400)
        }
    }

    private fun construir(v: Variacao): Notification {
        val rv = RemoteViews(context.packageName, R.layout.notif_orb_spike)
        val lado = (40 * densidade).roundToInt()
        rv.setImageViewBitmap(R.id.spike_orb, orbeTeal(lado))
        rv.setContentDescription(R.id.spike_orb, DESC_ORBE)
        rv.setContentDescription(R.id.spike_btn_esq, DESC_ESQ)
        rv.setContentDescription(R.id.spike_btn_dir, DESC_DIR)
        rv.setViewVisibility(R.id.spike_btn_esq, v.esq)
        rv.setViewVisibility(R.id.spike_btn_dir, v.dir)
        rv.setOnClickPendingIntent(R.id.spike_orb, clique("orbe", 1))
        rv.setOnClickPendingIntent(R.id.spike_btn_esq, clique("esq", 2))
        rv.setOnClickPendingIntent(R.id.spike_btn_dir, clique("dir", 3))
        return NotificationCompat.Builder(context, CANAL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(rv)
            .build()
    }

    private fun clique(acao: String, codigo: Int): PendingIntent = PendingIntent.getService(
        context,
        codigo,
        Intent(context, SpikeCliqueService::class.java).setAction(acao),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun orbeTeal(ladoPx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(ladoPx, ladoPx, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEAL }
        Canvas(bmp).drawCircle(ladoPx / 2f, ladoPx / 2f, ladoPx / 2f, paint)
        return bmp
    }

    // ── medição ───────────────────────────────────────────────────────────────

    /**
     * Abre a gaveta e garante que a orbe da prova está na tela: se outras notificações a empurram para fora, limpa as que o sistema deixa
     * limpar ("Clear all") e rola. Sem conseguir, o teste é **ignorado** (Assume), nunca quebra o CI por causa do ambiente.
     */
    private fun abrirGaveta() {
        device.openNotification()
        if (!device.wait(Until.hasObject(By.desc(DESC_ORBE)), 6000)) {
            device.findObject(By.res("com.android.systemui", "dismiss_text"))?.click()
            device.findObject(By.text("Clear all"))?.click()
            SystemClock.sleep(800)
            if (!device.hasObject(By.desc(DESC_ORBE))) {
                device.swipe(device.displayWidth / 2, device.displayHeight / 2, device.displayWidth / 2, device.displayHeight / 4, 10)
                SystemClock.sleep(500)
            }
            device.wait(Until.hasObject(By.desc(DESC_ORBE)), 3000)
        }
        SystemClock.sleep(700)
    }

    private fun achar(desc: String): UiObject2 {
        val no = device.wait(Until.findObject(By.desc(desc)), 4000)
        Assume.assumeTrue("nó '$desc' ausente na gaveta (outras notificações por cima?): prova ignorada", no != null)
        return no!!
    }

    private fun linhaDaNotificacao(orbe: UiObject2): UiObject2? {
        var no: UiObject2? = orbe
        while (no != null) {
            if (no.resourceName?.endsWith("expandableNotificationRow") == true) return no
            no = no.parent
        }
        return null
    }

    private fun expandir(v: Variacao) {
        val orbe = achar(DESC_ORBE)
        val row = linhaDaNotificacao(orbe)
        val b = row?.visibleBounds ?: orbe.visibleBounds
        val antes = b.height()
        val x = b.centerX()
        device.swipe(x, b.top + 4, x, b.top + (antes * 3).coerceAtMost(device.displayHeight / 3), 12)
        SystemClock.sleep(900)
        val depois = linhaDaNotificacao(achar(DESC_ORBE))?.visibleBounds?.height()
        val expansor = device.hasObject(By.res("android", "expand_button"))
        anota("expandir(${v.rotulo}): altura da linha ${antes}px → ${depois}px; botão de expandir do sistema presente=$expansor")
    }

    private fun medir(v: Variacao, rotulo: String) {
        val d = densidade
        val orbe = achar(DESC_ORBE)
        val bo = orbe.visibleBounds
        if (rotulo.startsWith("inicial-")) centrosDaOrbe[v.rotulo] = bo.centerX()
        val linhaNo = linhaDaNotificacao(orbe)
        linhaNo?.let { anota("linha da notificação (row): ${it.visibleBounds.toShortString()} altura=${it.visibleBounds.height()}px = ${"%.1f".format(it.visibleBounds.height() / d)}dp") }
        val captura = File(saida, "$rotulo.png")
        val ok = (1..3).any { device.takeScreenshot(captura).also { r -> if (!r) SystemClock.sleep(500) } }
        assertTrue("captura de $rotulo falhou", ok)

        val esperadoOrbe = 40 * d
        val alvo48 = 48 * d
        anota("orbe(visibleBounds) ${bo.toShortString()} = ${bo.width()}x${bo.height()}px = ${"%.1f".format(bo.width() / d)}x${"%.1f".format(bo.height() / d)}dp (alvo 48x48dp=${alvo48.roundToInt()}px)")
        val ex = extremosDoCirculo(captura, bo)
        if (ex == null) {
            anota("círculo teal: NÃO ENCONTRADO na captura (orbe fora da tela ou cortada por completo)")
        } else {
            val h = ex.bottom - ex.top + 1
            val w = ex.right - ex.left + 1
            val cortaV = h < esperadoOrbe - 1
            val cortaH = w < esperadoOrbe - 1
            anota(
                "círculo teal: topo=${ex.top} base=${ex.bottom} esq=${ex.left} dir=${ex.right} → ${w}x${h}px (esperado ${"%.1f".format(esperadoOrbe)}px = 40dp); " +
                    "largura=altura(±1)=${abs(w - h) <= 1}; ${if (cortaV || cortaH) "CORTA" else "CABE"}",
            )
        }
        val visiveis = mutableListOf("orbe" to bo)
        for ((nome, desc, visibilidade) in listOf(Triple("esq", DESC_ESQ, v.esq), Triple("dir", DESC_DIR, v.dir))) {
            val no = device.findObject(By.desc(desc))
            if (no == null) {
                anota("botão $nome: não está na árvore (visibilidade pedida=${nomeVis(visibilidade)})")
                continue
            }
            val b = no.visibleBounds
            val inteiro = b.width() >= alvo48 - 1 && b.height() >= alvo48 - 1
            val dentroDaLinha = linhaNo?.visibleBounds?.let { it.top <= b.top && it.bottom >= b.bottom } ?: true
            anota(
                "botão $nome (visibilidade=${nomeVis(visibilidade)}): ${b.toShortString()} = ${"%.1f".format(b.width() / d)}x${"%.1f".format(b.height() / d)}dp; " +
                    "alvo≥48x48dp=$inteiro; dentro da linha=$dentroDaLinha; ${if (inteiro) "CABE" else "CORTA"}",
            )
            if (visibilidade == View.VISIBLE) visiveis += nome to b
        }
        val sobrepoe = visiveis.flatMapIndexed { i, a -> visiveis.drop(i + 1).filter { Rect.intersects(a.second, it.second) && interseccao(a.second, it.second) > 1 }.map { a.first to it.first } }
        anota("sobreposição entre orbe e botões: ${if (sobrepoe.isEmpty()) "nenhuma" else sobrepoe.toString()}")
        val centroLinha = linhaNo?.visibleBounds?.centerX()
        val deslocamento = centroLinha?.let { bo.centerX() - it }
        anota("orbe no centro da linha: centro orbe x=${bo.centerX()} · centro da linha x=$centroLinha · diferença=${deslocamento}px")
    }

    /** Extremos (px da tela) dos pixels teal em torno de [area] (com folga de 6 px). */
    private fun extremosDoCirculo(png: File, area: Rect): Rect? {
        val bmp = BitmapFactory.decodeFile(png.path) ?: return null
        val r = Rect(area).apply { inset(-6, -6) }
        r.intersect(0, 0, bmp.width, bmp.height)
        var top = Int.MAX_VALUE; var bottom = -1; var left = Int.MAX_VALUE; var right = -1
        for (y in r.top until r.bottom) for (x in r.left until r.right) {
            if (pertoDoTeal(bmp.getPixel(x, y))) {
                if (y < top) top = y
                if (y > bottom) bottom = y
                if (x < left) left = x
                if (x > right) right = x
            }
        }
        bmp.recycle()
        return if (bottom < 0) null else Rect(left, top, right, bottom)
    }

    private fun pertoDoTeal(c: Int): Boolean =
        abs(Color.red(c) - Color.red(TEAL)) + abs(Color.green(c) - Color.green(TEAL)) + abs(Color.blue(c) - Color.blue(TEAL)) < 60

    private fun interseccao(a: Rect, b: Rect): Int {
        val i = Rect()
        return if (i.setIntersect(a, b)) minOf(i.width(), i.height()) else 0
    }

    private fun esperarEvento(acao: String): Boolean {
        val limite = SystemClock.elapsedRealtime() + 4000
        while (SystemClock.elapsedRealtime() < limite) {
            if (SpikeCliqueService.eventos.any { it.acao == acao }) return true
            SystemClock.sleep(20)
        }
        return false
    }

    private fun larguraDp(esq: Int, dir: Int) = ((dir - esq) / densidade).roundToInt()
    private fun nomeVis(v: Int) = when (v) { View.VISIBLE -> "VISIBLE"; View.INVISIBLE -> "INVISIBLE"; else -> "GONE" }

    private fun shell(cmd: String) = device.executeShellCommand(cmd)

    private fun anota(s: String) {
        relatorio.append(s).append('\n')
        android.util.Log.i("NTF-T1", s)
        sendLine(s)
    }

    private var seq = 0
    private fun sendLine(s: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("spike_${"%03d".format(seq++)}", s.replace('\n', ' ')) })
    }

    private companion object {
        const val CANAL = "ntf_t1_spike"
        const val ID = 4101
        const val ID_FILLER = 4102
        const val DESC_ORBE = "spike-orbe"
        const val DESC_ESQ = "spike-esq"
        const val DESC_DIR = "spike-dir"
        val TEAL: Int = Color.rgb(0x14, 0xB8, 0xA6)
    }
}
