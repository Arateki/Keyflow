package com.keyflow.computador.app

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Debug
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Medições no emulador real: não substitui a validação dos teclados/aparelhos físicos. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TerminalSpikeTest {
    @get:Rule val tela = createAndroidComposeRule<TerminalSpikeActivity>()
    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val cores = setOf(0xfa1020, 0x10ef20, 0x2030fa, 0xfae010)

    private fun salvar(nome: String, json: JSONObject) {
        val pasta = File(instr.targetContext.getExternalFilesDir(null), "computador-spike").apply { mkdirs() }
        File(pasta, "$nome.json").writeText(json.toString(2))
        instr.sendStatus(0, Bundle().apply { putString("stream", "CMP_SPIKE $nome ${json}\n") })
    }

    private fun bytesGravados(nome: String) = instr.context.assets.open("computador/gravacoes/$nome").use { it.readBytes() }

    private fun eventos(): List<TerminalSpikeActivity.Saida> {
        instr.waitForIdleSync()
        return mutableListOf<TerminalSpikeActivity.Saida>().also { tela.activity.saidas.drainTo(it) }
    }

    private fun sincronizarEntrada() {
        // Com mouse desligado, esta chamada esvazia a fila do terminal sem emitir bytes.
        tela.runOnIdle { tela.activity.emulador.dispatchMouseMove(0, 0) }
        instr.waitForIdleSync()
    }

    private fun texto(eventos: List<TerminalSpikeActivity.Saida>): String {
        val bytes = ByteArrayOutputStream()
        eventos.forEach { bytes.write(it.bytes) }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun medirImagem(nome: String) {
        assertEquals(0, tela.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        var imagem: Bitmap? = null
        var contagens = emptyMap<Int, Int>()
        tela.runOnIdle {
            // O cursor sólido cobriria os pixels da pequena imagem real do kitten.
            tela.activity.alimentar("\u001b[?25l".toByteArray())
            tela.activity.alimentar(bytesGravados(nome))
        }
        try {
            tela.waitUntil(15_000) {
                imagem?.recycle()
                imagem = instr.uiAutomation.takeScreenshot()
                val bitmap = imagem ?: return@waitUntil false
                val altura = minOf(bitmap.height, 1000)
                val pixels = IntArray(bitmap.width * altura)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, altura)
                val contados = cores.associateWith { 0 }.toMutableMap()
                pixels.forEach { cor -> val rgb = cor and 0xffffff; if (rgb in cores) contados[rgb] = contados.getValue(rgb) + 1 }
                contagens = contados
                contagens.values.all { it >= 4 }
            }
            val pasta = File(instr.targetContext.getExternalFilesDir(null), "computador-spike").apply { mkdirs() }
            val png = ByteArrayOutputStream().also { imagem!!.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            File(pasta, "$nome.png").writeBytes(png)
            // O pool devolve o protocolo do instrumentador mesmo quando o worker é descartável.
            instr.sendStatus(0, Bundle().apply { putString("cmp_spike_png", nome + " " + Base64.encodeToString(png, Base64.NO_WRAP)) })
            salvar(nome, JSONObject().put("pixels", JSONObject(contagens.mapKeys { "%06x".format(it.key) })).put("flag_secure", false))
        } catch (erro: Throwable) {
            imagem?.let { bitmap ->
                val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                instr.sendStatus(0, Bundle().apply { putString("cmp_spike_png", "falha-" + nome + " " + Base64.encodeToString(png, Base64.NO_WRAP)) })
            }
            throw AssertionError("$nome: contagem dos quadrantes $contagens", erro)
        } finally { imagem?.recycle() }
    }

    @Test fun a0TextoUtf8ECoresAnsiPassamPeloEmuladorReal() {
        medirImagem("texto-cores.bin")
        tela.waitUntil(5_000) { tela.activity.emulador.getLastCommandOutput() == "Olá, terminal!" }
        salvar("texto", JSONObject().put("saida", tela.activity.emulador.getLastCommandOutput()).put("cores_ansi", true))
    }

    @Test fun aKittyRealDesenhaOsQuatroQuadrantes() = medirImagem("kitty.bin")
    @Test fun bIterm2RealDesenhaOsQuatroQuadrantes() = medirImagem("iterm2.bin")
    @Test fun cPlaceholdersReaisDoKittenDesenhamAImagem() = medirImagem("placeholders-kitty.bin")
    @Test fun dHerdrRealDesenhaAImagemDoPane() = medirImagem("herdr.bin")

    @Test fun eRespostasRegistramBytesEOrigemAssincrona() {
        val medidas = JSONObject()
        fun consultar(nome: String, entrada: String): List<TerminalSpikeActivity.Saida> {
            eventos()
            tela.runOnIdle { tela.activity.alimentar(entrada.toByteArray()) }
            val saida = eventos()
            medidas.put(nome, JSONArray().apply {
                saida.forEach { put(JSONObject().put("hex", it.bytes.joinToString("") { b -> "%02x".format(b) }).put("dentro_write", it.dentroDoWriteInput)) }
            })
            return saida
        }
        val da1 = consultar("DA1", "\u001b[c")
        assertTrue(texto(da1).contains("?1;2c"))
        assertTrue(da1.all { !it.dentroDoWriteInput })
        assertTrue(texto(consultar("DSR", "\u001b[5n")).contains("0n"))
        assertTrue(texto(consultar("CPR", "\u001b[6n")).contains("R"))
        consultar("cor_10", "\u001b]10;?\u0007")
        consultar("cor_11", "\u001b]11;?\u0007")
        consultar("titulo", "\u001b]0;Título de teste\u0007\u001b[21t")
        assertTrue(texto(consultar("kitty_query", "\u001b_Ga=q,t=d,f=24,s=1,v=1,i=50;AAAA\u001b\\")).contains("OK"))
        for (meio in listOf("f", "t", "s")) {
            val resposta = texto(consultar("kitty_t_$meio", "\u001b_Ga=q,t=$meio,f=24,s=1,v=1,i=51;L25hby1leGlzdGU=\u001b\\"))
            assertTrue("O meio $meio precisa ser recusado", resposta.isNotEmpty())
            assertFalse(resposta.contains(";OK"))
        }
        salvar("respostas", medidas)
    }

    private fun campo(view: View): View? {
        if (view.javaClass.simpleName == "ImeInputView") return view
        if (view is ViewGroup) repeat(view.childCount) { campo(view.getChildAt(it))?.let { achado -> return achado } }
        return null
    }

    @Test fun fInputConnectionMedeUtf8SemEnterEReproduzCaminhoDuplo() {
        val info = EditorInfo()
        eventos()
        tela.runOnIdle {
            val view = checkNotNull(campo(tela.activity.window.decorView))
            view.requestFocus()
            val conexao = view.onCreateInputConnection(info)
            assertNotNull(conexao)
            assertTrue(conexao!!.commitText("ação e voz", 1))
        }
        sincronizarEntrada()
        val ditado = texto(eventos())
        assertEquals("ação e voz", ditado)
        assertFalse(ditado.contains('\r') || ditado.contains('\n'))
        tela.runOnIdle {
            val view = checkNotNull(campo(tela.activity.window.decorView))
            val conexao = checkNotNull(view.onCreateInputConnection(EditorInfo()))
            val baixo = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
            val alto = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)
            conexao.sendKeyEvent(baixo)
            view.dispatchKeyEvent(baixo)
            conexao.sendKeyEvent(alto)
            view.dispatchKeyEvent(alto)
        }
        sincronizarEntrada()
        val duplo = texto(eventos())
        // Diagnóstico do upstream: os dois caminhos juntos produzem duplicação.
        // A cola da CMP-T13 deverá escolher/deduplicar a origem antes do SSH.
        assertEquals("aa", duplo)
        tela.runOnIdle {
            val view = checkNotNull(campo(tela.activity.window.decorView))
            val conexao = checkNotNull(view.onCreateInputConnection(EditorInfo()))
            conexao.setComposingText("a", 1)
            conexao.setComposingText("ação", 1)
            conexao.finishComposingText()
        }
        sincronizarEntrada()
        val composicao = texto(eventos())
        salvar("entrada", JSONObject().put("input_type", info.inputType).put("commit", ditado).put("caminho_duplo", duplo).put("composicao_tipo_null", composicao)
            .put("keyflow_gboard_orbe_reais", "não medido; exercitado o InputConnection real do terminal"))
    }

    @Test fun gMouseUsaOModoNegociadoEProduzSgrCorreto() {
        eventos()
        tela.runOnIdle { tela.activity.alimentar("\u001b[?1000h\u001b[?1006h".toByteArray()) }
        tela.waitUntil(5_000) { tela.activity.emulador.mouseMode.value == 1 }
        tela.runOnIdle {
            tela.activity.emulador.dispatchMouseMove(2, 4)
            tela.activity.emulador.dispatchMouseButton(1, true)
            tela.activity.emulador.dispatchMouseButton(1, false)
        }
        val cliques = texto(eventos())
        assertTrue(cliques.contains("\u001b[<0;5;3M"))
        assertTrue(cliques.contains("\u001b[<0;5;3m"))
        tela.runOnIdle { tela.activity.alimentar("\u001b[?1000l".toByteArray()) }
        tela.waitUntil(5_000) { tela.activity.emulador.mouseMode.value == 0 }
        tela.runOnIdle { tela.activity.emulador.dispatchMouseButton(1, true) }
        assertEquals("", texto(eventos()))
        salvar("mouse", JSONObject().put("sgr", cliques).put("modo_desligado_silencioso", true))
    }

    @Test fun hSixelBrutoPermaneceSemTranscodificadorNoSpike() {
        tela.runOnIdle { tela.activity.alimentar(bytesGravados("img2sixel.bin")) }
        salvar("sixel", JSONObject().put("transcodificador_no_app", "não medido; implementação na CMP-T25/T26").put("fluxo_bruto_sem_crash", true))
    }

    @Test fun zMemoriaEHistoricoMedidosEmCincoEVinteMilLinhas() {
        val medidas = JSONArray()
        for (limite in listOf(5_000, 20_000)) {
            System.gc()
            val antes = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            val heapAntes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val emulador = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80, maxScrollbackLines = limite)
            try {
                fun carregar() {
                    val bytes = buildString {
                        repeat(limite + 24) { append("linha ").append(it.toString().padStart(5, '0')).append(' ').append("x".repeat(60)).append("\r\n") }
                    }.toByteArray()
                    emulador.writeInput(bytes)
                }
                carregar()
                assertEquals(limite, emulador.retainedScrollbackLines)
                instr.waitForIdleSync()
                System.gc()
                val depois = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                val heapDepois = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                medidas.put(JSONObject().put("linhas", limite).put("retidas", emulador.retainedScrollbackLines).put("colunas", 80).put("rows", 24)
                    .put("pss_antes_kb", antes.totalPss).put("pss_depois_kb", depois.totalPss).put("delta_pss_kb", depois.totalPss - antes.totalPss)
                    .put("delta_heap_bytes", heapDepois - heapAntes).put("heap_max_bytes", Runtime.getRuntime().maxMemory()))
            } finally { emulador.close() }
        }
        salvar("memoria", JSONObject().put("sessoes", medidas).put("metodo", "PSS do processo e heap após GC; uma sessão por medição, sem imagens"))
    }
}
