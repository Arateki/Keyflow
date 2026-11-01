package com.keyflow.notificacao

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import androidx.core.graphics.ColorUtils
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.R
import com.keyflow.core.ButtonState
import com.keyflow.data.Settings
import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import com.keyflow.notificacao.app.TempoDaGravacao
import com.keyflow.notificacao.app.NotificacaoDaOrbe
import com.keyflow.notificacao.app.ProvedorDeAparencia
import com.keyflow.overlay.OverlayService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** NTF-T9: a notificação é a orbe — `RemoteViews`, cliques, botões por estado, versão pública, sem texto ditado (NTF-05..07, 13, 17, 20, 21, 29, 45, 48, 50). */
@RunWith(AndroidJUnit4::class)
class NotificacaoDaOrbeTest {
    private lateinit var context: Context
    private val provedor get() = ProvedorDeAparencia(context, { true })
    private val lado get() = OrcamentoDeBitmaps.ladoPx(context.resources.displayMetrics.density)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppLocale.language = AppLanguage.PT
    }

    @After
    fun tearDown() {
        AppLocale.language = AppLanguage.PT
    }

    private fun construir(
        estado: ButtonState,
        visao: Boolean = false,
        config: ConfigNotificacao = ConfigNotificacao(),
        skin: String = "atelie",
        escuro: Boolean = false,
        tinta: Int? = null,
        tempo: TempoDaGravacao? = null,
    ): Notification {
        val efetiva = efetiva(config, Settings())
        val visual = EstadoVisualDaNotificacao.para(estado, visao, efetiva)
        val p = provedor
        return NotificacaoDaOrbe(context, "keyflow_overlay").construir(
            estado, visao,
            p.obterAgora(skin, visual.aparencia, visual.glifo, lado, escuro).let { if (tinta != null) it.copy(tinta = tinta) else it },
            p.obterAgora(skin, AparenciaDaOrbe.IDLE, null, lado, escuro).let { if (tinta != null) it.copy(tinta = tinta) else it },
            config, efetiva, escuro, tempo = tempo,
        )
    }

    private fun conteudo(n: Notification): View = (n.contentView ?: error("sem contentView")).apply(context, FrameLayout(context))

    private fun publica(n: Notification): View = (n.publicVersion?.contentView ?: error("sem versão pública")).apply(context, FrameLayout(context))

    private fun View.v(id: Int): View = findViewById(id)

    @Test
    fun ehOngoingPrivadaSemBigContentViewEComVersaoPublica() {
        val n = construir(ButtonState.RECORDING)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertNull("sem bigContentView", n.bigContentView)
        assertNotNull(n.contentView)
        assertNotNull(n.publicVersion)
        assertNotNull(n.contentIntent)
        assertNotNull(n.deleteIntent)
    }

    @Test
    fun osBotoesDeCadaEstadoBatemComATabelaEAOrbeFicaNoCentro() {
        val casos = mapOf(
            ButtonState.IDLE_READY to Botoes.NENHUM,
            ButtonState.RECORDING to Botoes(Botao.PAUSAR, Botao.DESCARTAR),
            ButtonState.PAUSED to Botoes(Botao.RETOMAR, Botao.DESCARTAR),
            ButtonState.SENDING to Botoes(null, Botao.DESCARTAR),
            ButtonState.CANCELLING to Botoes(null, Botao.DESFAZER),
            ButtonState.ERROR to Botoes.NENHUM,
            ButtonState.PENDING_PASTE to Botoes.NENHUM,
            ButtonState.IDLE_NO_FIELD to Botoes.NENHUM,
        )
        for ((estado, botoes) in casos) {
            val v = conteudo(construir(estado))
            val esq = v.v(R.id.notif_btn_esq)
            val dir = v.v(R.id.notif_btn_dir)
            assertEquals("$estado esq", if (botoes.esquerda != null) View.VISIBLE else View.INVISIBLE, esq.visibility)
            assertEquals("$estado dir", if (botoes.direita != null) View.VISIBLE else View.INVISIBLE, dir.visibility)
            botoes.esquerda?.let { assertEquals("$estado", tr(BotoesDaNotificacao.descricao(it)), esq.contentDescription.toString()) }
            botoes.direita?.let { assertEquals("$estado", tr(BotoesDaNotificacao.descricao(it)), dir.contentDescription.toString()) }
        }
        val visao = conteudo(construir(ButtonState.RECORDING, visao = true))
        assertEquals(View.INVISIBLE, visao.v(R.id.notif_btn_esq).visibility)
        assertEquals(View.VISIBLE, visao.v(R.id.notif_btn_dir).visibility)
    }

    private fun tr(s: String) = com.keyflow.i18n.tr(s)

    @Test
    fun umCliqueNaOrbeEUmPorBotaoPresenteCadaUmComAAcaoDedicada() {
        val n = construir(ButtonState.PAUSED)
        val v = conteudo(n)
        // os PendingIntents ficam na RemoteViews; o que dá para afirmar daqui: a orbe e os botões presentes são clicáveis e os ausentes não importam
        assertTrue(v.v(R.id.notif_orb).hasOnClickListeners())
        assertTrue(v.v(R.id.notif_btn_esq).hasOnClickListeners())
        assertTrue(v.v(R.id.notif_btn_dir).hasOnClickListeners())
        val ocioso = conteudo(construir(ButtonState.IDLE_READY))
        assertTrue(ocioso.v(R.id.notif_orb).hasOnClickListeners())
        assertFalse("botão ausente não clica", ocioso.v(R.id.notif_btn_esq).hasOnClickListeners())
        assertEquals(OverlayService.ACTION_NOTIF_ORB_TAP, "com.keyflow.overlay.NOTIF_ORB_TAP")
    }

    @Test
    fun tempoDoCoordenadorNaoDependeDosEstadosIntermediariosQueForamDesenhados() {
        val inicio = android.os.SystemClock.elapsedRealtime()-2_000
        val n = construir(ButtonState.RECORDING, tempo = TempoDaGravacao(5_000, inicio))
        val correndo = conteudo(n).v(R.id.notif_time) as android.widget.Chronometer
        assertEquals(inicio-5_000, correndo.base)
        val antes = android.os.SystemClock.elapsedRealtime()
        val pausa = conteudo(construir(ButtonState.PAUSED, tempo = TempoDaGravacao(7_000, inicio)))
            .v(R.id.notif_time_mini) as android.widget.Chronometer
        val depois = android.os.SystemClock.elapsedRealtime()
        assertTrue("pausa não usou o acumulado autoritativo", pausa.base in (antes-7_000)..(depois-7_000))
    }

    @Test
    fun oChronometerContaGravandoECongelaEmPausadoECancelando() {
        val gravando = conteudo(construir(ButtonState.RECORDING))
        assertEquals(View.VISIBLE, gravando.v(R.id.notif_time).visibility)
        assertEquals(View.GONE, gravando.v(R.id.notif_time_mini).visibility)
        for (e in listOf(ButtonState.PAUSED, ButtonState.CANCELLING)) {
            val v = conteudo(construir(e))
            assertEquals("$e", View.GONE, v.v(R.id.notif_time).visibility)
            assertEquals("$e", View.VISIBLE, v.v(R.id.notif_time_mini).visibility)
        }
        for (e in listOf(ButtonState.IDLE_READY, ButtonState.SENDING, ButtonState.ERROR, ButtonState.PENDING_PASTE)) {
            val v = conteudo(construir(e))
            assertEquals("$e", View.GONE, v.v(R.id.notif_time).visibility)
            assertEquals("$e", View.GONE, v.v(R.id.notif_time_mini).visibility)
        }
    }

    @Test
    fun aVersaoPublicaFicaEmRepousoSemBotoesQuandoAOpcaoEstaDesligadaEOEstadoRealQuandoLigada() {
        val desligada = publica(construir(ButtonState.RECORDING, config = ConfigNotificacao(permitirComTelaBloqueada = false)))
        assertEquals(View.INVISIBLE, desligada.v(R.id.notif_btn_esq).visibility)
        assertEquals(View.INVISIBLE, desligada.v(R.id.notif_btn_dir).visibility)
        assertEquals(View.GONE, desligada.v(R.id.notif_time).visibility)
        assertEquals(tr("KeyFlow pronto") + ". " + tr("Toque para gravar"), desligada.v(R.id.notif_orb).contentDescription.toString())
        val ligada = publica(construir(ButtonState.RECORDING, config = ConfigNotificacao(permitirComTelaBloqueada = true)))
        assertEquals(View.VISIBLE, ligada.v(R.id.notif_btn_esq).visibility)
        assertEquals(View.VISIBLE, ligada.v(R.id.notif_time).visibility)
    }

    @Test
    fun aDescricaoPorEstadoSaiTraduzidaNosOitoEstados() {
        for (e in ButtonState.entries) {
            AppLocale.language = AppLanguage.PT
            val pt = conteudo(construir(e)).v(R.id.notif_orb).contentDescription.toString()
            assertTrue("$e sem descrição", pt.isNotBlank())
            AppLocale.language = AppLanguage.EN
            val en = conteudo(construir(e)).v(R.id.notif_orb).contentDescription.toString()
            assertTrue("$e não traduziu ($pt)", en != pt)
        }
    }

    /** Desenha o botão como o sistema o desenharia e devolve a cor média dos pixels opacos do glifo. */
    private fun corDoGlifo(btn: ImageButton): Int {
        val px = (48 * context.resources.displayMetrics.density).toInt()
        btn.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY))
        btn.layout(0, 0, px, px)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        btn.draw(android.graphics.Canvas(bmp))
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        val pixels = IntArray(px * px).also { bmp.getPixels(it, 0, px, 0, 0, px, px) }
        for (p in pixels) if (android.graphics.Color.alpha(p) > 200) {
            r += android.graphics.Color.red(p); g += android.graphics.Color.green(p); b += android.graphics.Color.blue(p); n++
        }
        assertTrue("o glifo do botão não desenhou", n > 0)
        return android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    @Test
    fun oContrasteDosGlifosELegivelEmClaroEEscuroNasSkins() {
        val fundoDoSistema = mapOf(false to 0xFFF2F0F8.toInt(), true to 0xFF1C1B20.toInt())
        for (skin in listOf("atelie", "sistema", "aero", "glitch", "rosegold", "mascot")) {
            for (escuro in listOf(false, true)) {
                val efetiva = efetiva(ConfigNotificacao(), Settings())
                val visual = EstadoVisualDaNotificacao.para(ButtonState.RECORDING, false, efetiva)
                val fundo = provedor.obterAgora(skin, visual.aparencia, visual.glifo, lado, escuro).fundo ?: fundoDoSistema.getValue(escuro)
                val btn = conteudo(construir(ButtonState.RECORDING, skin = skin, escuro = escuro)).v(R.id.notif_btn_esq) as ImageButton
                val cor = corDoGlifo(btn)
                val razao = Contraste.razao(cor, fundo)
                assertTrue("$skin escuro=$escuro: glifo #%06X sobre #%06X tem contraste %.2f (< 3)".format(cor and 0xFFFFFF, fundo and 0xFFFFFF, razao), razao >= Contraste.MINIMO)
            }
        }
    }

    // notification.ink aceita alfa: mede a cor desenhada, não só o RGB solicitado ao filtro.
    @Test
    fun osBotoesComTintaTransparenteESemitransparenteContinuamLegiveis() {
        for (escuro in listOf(false, true)) for (tinta in tintasComAlfa) {
            val v = conteudo(construir(ButtonState.RECORDING, escuro = escuro, tinta = tinta))
            val fundo = (v.background as ColorDrawable).color
            for (id in listOf(R.id.notif_btn_esq, R.id.notif_btn_dir)) {
                val cor = corDoGlifo(v.v(id) as ImageButton)
                assertTrue("botão com alfa: tinta=$tinta escuro=$escuro", ColorUtils.calculateContrast(cor, fundo) >= Contraste.MINIMO)
            }
        }
    }

    @Test
    fun oTempoMiudoComTintaTransparenteESemitransparenteFicaOpacoELegivel() {
        for (escuro in listOf(false, true)) for (tinta in tintasComAlfa) {
            val v = conteudo(construir(ButtonState.PAUSED, escuro = escuro, tinta = tinta))
            val fundo = (v.background as ColorDrawable).color
            val mini = v.v(R.id.notif_time_mini) as TextView
            assertEquals(View.VISIBLE, mini.visibility)
            assertEquals("tempo miúdo opaco", 255, mini.currentTextColor ushr 24)
            assertTrue("tempo miúdo com alfa: tinta=$tinta escuro=$escuro", ColorUtils.calculateContrast(mini.currentTextColor, fundo) >= Contraste.MINIMO)
        }
    }

    private val tintasComAlfa = listOf(0x001B1B1B, 0x00FFFFFF, 0x10000000, 0x80000000.toInt(), 0x80FFFFFF.toInt())

    @Test
    fun aSomaDosBitmapsFicaAbaixoDe1MBEOParcelDaNotificacaoEMedido() {
        val n = construir(ButtonState.RECORDING)
        val parcel = Parcel.obtain()
        n.writeToParcel(parcel, 0)
        val bytes = parcel.dataSize()
        parcel.recycle()
        android.util.Log.i("NTF-T9", "Notification serializada: $bytes bytes (lado=$lado px, densidade=${context.resources.displayMetrics.density})")
        assertTrue("Parcel de $bytes bytes", bytes < 1024 * 1024)
        assertTrue(OrcamentoDeBitmaps.somaDaNotificacao(lado) <= OrcamentoDeBitmaps.TETO_DA_SOMA_BYTES)
    }

    @Test
    fun aMinimaTemCanalProprioSemAcaoSecretaEOngoing() {
        val n = NotificacaoDaOrbe(context, "keyflow_overlay").construirMinima()
        assertEquals(NotificacaoDaOrbe.CANAL_MINIMO, n.channelId)
        assertEquals(Notification.VISIBILITY_SECRET, n.visibility)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNull(n.contentView)
        assertTrue(n.actions.isNullOrEmpty())
    }

    /** NTF-17: nenhum texto fora das chaves `tr` conhecidas (e nada ditado) na descrição, nos extras e na `RemoteViews`. */
    @Test
    fun semTextoForaDasChavesTr() {
        val permitidos = setOf(
            "KeyFlow pronto", "Toque para gravar", "KeyFlow gravando", "Toque para enviar", "KeyFlow pausado", "KeyFlow transcrevendo",
            "Gravação descartada. Toque para descartar agora", "KeyFlow com erro. Toque para dispensar", "Texto pendente. Toque para colar",
            "Ler a tela em andamento", "Toque para encerrar", "Pausar gravação", "Retomar gravação", "Descartar gravação", "Desfazer descarte",
            "Encerrar a leitura da tela",
        )
        for (e in ButtonState.entries) for (visao in listOf(false, true)) {
            val n = construir(e, visao)
            for (raiz in listOf(conteudo(n), publica(n))) varrer(raiz, permitidos)
            for (chave in n.extras.keySet()) {
                val valor = n.extras.get(chave)
                if (valor is CharSequence) assertTrue("extra $chave='$valor'", valor.toString() in permitidos || chave.startsWith("android.template") || chave.startsWith("androidx.core.app.extra"))
            }
        }
    }

    private fun varrer(v: View, permitidos: Set<String>) {
        // O Chronometer gera a própria descrição ("0 seconds"): é o tempo do sistema, nunca texto nosso nem ditado.
        if (v !is android.widget.Chronometer) v.contentDescription?.toString()?.let { d ->
            val partes = d.split(". ").let { p -> if (d in permitidos) listOf(d) else p }
            for (parte in partes) assertTrue("descrição fora das chaves: '$parte'", parte in permitidos || parte.trimEnd('.') in permitidos)
        }
        if (v is TextView && v !is android.widget.Chronometer) assertTrue("texto na view: '${v.text}'", v.text.isNullOrEmpty())
        if (v is ViewGroup) for (i in 0 until v.childCount) varrer(v.getChildAt(i), permitidos)
    }
}
