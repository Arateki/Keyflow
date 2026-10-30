package com.keyflow.fluxos.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.core.KeyFlowController
import com.keyflow.core.ReasoningSource
import com.keyflow.data.SettingsRepository
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.TipoEtapa
import com.keyflow.fluxos.execucao.CausaErro
import com.keyflow.fluxos.execucao.DestinoAba
import com.keyflow.fluxos.execucao.ResultadoEtapa
import com.keyflow.fluxos.execucao.Valor
import com.keyflow.ui.gallery.GalleryActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * As portas montadas de verdade (`MontagemDasPortas.base`) sobre o `KeyFlowController` e os motores do processo: Inserir com a
 * área de transferência real e Perguntar com o modelo local ausente (INDISPONIVEL, nada baixado). O que não precisa de Android está
 * em `PortasAndroidTest` (JVM, motores falsos).
 */
@RunWith(AndroidJUnit4::class)
class PortasAndroidInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<GalleryActivity>

    @Before
    fun setUp() {
        // Uma Activity em primeiro plano: a leitura da área de transferência exige foco no Android 10+.
        scenario = ActivityScenario.launch(Intent(context, GalleryActivity::class.java).putExtra("route", "config-visao"))
        instrumentation.runOnMainSync { KeyFlowController.clearInserter(); KeyFlowController.autoPasteInField = true; KeyFlowController.autoPasteOnFocus = false }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            KeyFlowController.clearInserter()
            KeyFlowController.autoPasteInField = true
            KeyFlowController.autoPasteOnFocus = false
        }
        scenario.close()
    }

    private fun aguardarFoco() {
        val limite = SystemClock.uptimeMillis() + 15_000
        do {
            var focada = false
            scenario.onActivity { focada = it.hasWindowFocus() }
            if (focada) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < limite)
        throw AssertionError("A janela da Activity não recebeu foco para ler a área de transferência")
    }

    private fun clip(): String? {
        aguardarFoco()
        var texto: String? = null
        instrumentation.runOnMainSync {
            texto = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
        }
        return texto
    }

    private fun definirClip(texto: String) = instrumentation.runOnMainSync {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("teste", texto))
    }

    private fun portas() = MontagemDasPortas.base(context)

    @Test
    fun inserirVoltaParaMainQuandoChamadoForaDela() = runBlocking {
        instrumentation.runOnMainSync {
            KeyFlowController.registerInserter {
                assertEquals("insertOrHold deve rodar na main", Looper.getMainLooper(), Looper.myLooper())
                true
            }
        }
        val resultado = withContext(Dispatchers.Default) {
            portas().getValue(TipoEtapa.INSERIR).executar(Etapa.Inserir, Valor.Texto("texto"))
        }
        assertTrue(resultado is ResultadoEtapa.Ok)
    }

    @Test
    fun theBasePortsCoverTheFourEnginelessSteps() {
        assertEquals(setOf(TipoEtapa.PERGUNTAR, TipoEtapa.VISAO, TipoEtapa.FALAR, TipoEtapa.INSERIR), portas().keys)
    }

    @Test
    fun insertingIntoAFocusedFieldLeavesTheClipboardAlone() = runBlocking {
        definirClip("antes")
        val recebidos = mutableListOf<String>()
        instrumentation.runOnMainSync { KeyFlowController.registerInserter { recebidos += it; true } }
        val r = portas().getValue(TipoEtapa.INSERIR).executar(Etapa.Inserir, Valor.Texto("texto do fluxo"))
        assertEquals(Valor.Texto("texto do fluxo"), (r as ResultadoEtapa.Ok).valor)
        assertEquals(listOf("texto do fluxo"), recebidos)
        assertEquals("antes", clip())
    }

    @Test
    fun heldTextAlsoGoesToTheClipboard() = runBlocking {
        definirClip("antes")
        // Campo focado, mas a cola automática no campo está desligada: o texto fica só na área de transferência (Held).
        instrumentation.runOnMainSync { KeyFlowController.registerInserter { true }; KeyFlowController.autoPasteInField = false }
        val r = portas().getValue(TipoEtapa.INSERIR).executar(Etapa.Inserir, Valor.Texto("guardado"))
        assertEquals(Valor.Texto("guardado"), (r as ResultadoEtapa.Ok).valor)
        assertEquals("guardado", clip())
        instrumentation.runOnMainSync { KeyFlowController.autoPasteInField = true }
    }

    @Test
    fun askingWithNoLocalModelIsUnavailableAndDownloadsNothing() = runBlocking {
        val repo = SettingsRepository(context)
        val saved = repo.settingsFlow().first()
        val modelos = context.filesDir.walkTopDown().count()
        try {
            repo.saveSettings(saved.copy(reasoningSource = ReasoningSource.LOCAL, reasoningLocalModelId = null))
            val r = portas().getValue(TipoEtapa.PERGUNTAR).executar(Etapa.Perguntar(), Valor.Texto("oi"))
            val erro = (r as ResultadoEtapa.Falha).erro
            assertEquals(CausaErro.INDISPONIVEL, erro.causa)
            assertEquals(DestinoAba.RACIOCINIO, erro.destino)
            assertNotNull(erro.mensagem)
            assertTrue("a porta criou arquivos (baixou algo?)", context.filesDir.walkTopDown().count() <= modelos + 2)
        } finally {
            repo.saveSettings(saved)
        }
    }
}
