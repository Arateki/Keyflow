package com.keyflow.ui.config

import android.content.ClipboardManager
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.computador.AutenticacaoHost
import com.keyflow.computador.Host
import com.keyflow.computador.ImpressaoDigital
import com.keyflow.computador.app.Disponibilidade
import com.keyflow.computador.app.AutenticadorDeUsuario
import com.keyflow.computador.app.ProvedorDeChavesAndroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.keyflow.computador.chaves.*
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComputadorChavesTest {
    @get:Rule val regra = createComposeRule()
    private val publicaEd = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFAKE keyflow@teste"
    private val publicaEc = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYFAKE keyflow@teste"
    private fun chave(id: String = "00000001", protecao: Protecao = Protecao.HARDWARE, tipo: TipoDeChave = TipoDeChave.ED25519) =
        ChaveSsh(id, "chave-$id", tipo, ImpressaoDigital.de(id.toByteArray()), protecao, false, 1)
    private inner class Acoes(iniciais: List<ChaveSsh> = emptyList()) : AcoesDeChaves {
        var chaves by mutableStateOf(iniciais)
        var disponibilidade = Disponibilidade.SIM
        var respostaImportacao: Resultado<ChaveSsh> = Resultado.Falha(ErroChave.PASSPHRASE_NECESSARIA)
        var geracoes = 0
        var bioGerada = false
        var tipoGerado: TipoDeChave? = null
        val buffers = mutableListOf<CharArray>()
        val passphrases = mutableListOf<String?>()
        override fun biometriaDisponivel() = disponibilidade
        override suspend fun informacao(chave: ChaveSsh) = InformacaoDaChave(if (chave.tipo == TipoDeChave.ED25519) publicaEd else publicaEc, EstadoChave.ATIVA, false)
        override suspend fun gerar(nome: String, tipo: TipoDeChave, biometria: Boolean): Resultado<ChaveSsh> {
            geracoes++; tipoGerado = tipo; bioGerada = biometria
            val c = chave(id = "00000002", tipo = tipo).copy(nome = nome, exigeBiometria = biometria)
            chaves = chaves + c
            return Resultado.Ok(c)
        }
        override suspend fun importar(nome: String, privada: CharArray, passphrase: CharArray?, biometria: Boolean): Resultado<ChaveSsh> {
            buffers += privada
            passphrase?.let { buffers += it }
            passphrases += passphrase?.concatToString()
            if (respostaImportacao is Resultado.Ok) chaves = chaves + (respostaImportacao as Resultado.Ok).valor
            return respostaImportacao
        }
        override suspend fun renomear(chave: ChaveSsh, nome: String) { chaves = chaves.map { if (it.id == chave.id) it.copy(nome = nome) else it } }
        override suspend fun excluir(chave: ChaveSsh) { chaves = chaves.filter { it.id != chave.id } }
    }
    private fun mostrar(a: Acoes, hosts: List<Host> = emptyList(), dark: Boolean = false) {
        regra.setContent { KeyFlowTheme(dark = dark) { Column(Modifier.verticalScroll(rememberScrollState())) { ComputadorChaves(a.chaves, hosts, a) } } }
    }
    private fun no(tag: String) = regra.onNodeWithTag(tag, useUnmergedTree = true)
    private fun clicar(tag: String) {
        val alvo = no(tag)
        if (!alvo.isDisplayed()) alvo.performScrollTo()
        alvo.performClick()
    }
    private fun digitar(tag: String, valor: String) = no(tag).performScrollTo().performTextReplacement(valor)
    private fun texto(valor: String) = regra.onNodeWithText(valor, useUnmergedTree = true)
    private fun varrerSegredos(vararg segredos: String) {
        val nos = regra.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { raiz ->
            fun descendentes(n: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(n) + n.children.flatMap(::descendentes)
            descendentes(raiz)
        }
        for (n in nos) for ((_, valor) in n.config) {
            assertTrue("segredo na propriedade semântica", segredos.none { it in valor.toString() })
        }
    }
    @Test fun tecladoNativoDigitaSemRevelarUltimoCaractereNemPermitirCopia() {
        val segredo = SegredoDigitado()
        var raiz: View? = null
        regra.setContent { KeyFlowTheme(dark = false) {
            val view = LocalView.current
            SideEffect { raiz = view.rootView }
            CampoSecretoComputador("Senha de teste", segredo, "nativa")
        } }
        regra.runOnIdle {
            val campo = raiz!!.findViewWithTag<EditText>("entrada-secreta-nativa")
            campo.requestFocus()
            val entrada = campo.onCreateInputConnection(EditorInfo())!!
            assertTrue(entrada.commitText("SENTINELA-NATIVA-FALSA", 1))
            val copia = segredo.copiar()
            try { assertEquals("SENTINELA-NATIVA-FALSA", copia.concatToString()) } finally { copia.fill('\u0000') }
            assertEquals("•".repeat(campo.length()), campo.transformationMethod.getTransformation(campo.text, campo).toString())
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, campo.importantForAccessibility)
            assertFalse(campo.isSaveEnabled)
            val clipboard = campo.context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", "PUBLICA-FALSA"))
            campo.selectAll()
            campo.onTextContextMenuItem(android.R.id.copy)
            assertEquals("PUBLICA-FALSA", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        varrerSegredos("SENTINELA-NATIVA-FALSA")
    }
    @Test fun protecaoDoKeystoreRealApareceNaTelaSemSerInferidaPelaApi() {
        val cofre = object : CofreDeSegredos {
            private val valores = mutableMapOf<String, String>()
            override fun ler(chave: String) = valores[chave]
            override fun gravar(chave: String, valor: String) { valores[chave] = valor }
            override fun apagar(chave: String) { valores.remove(chave) }
            override fun valores(): Collection<String> = valores.values
        }
        val aut = object : AutenticadorDeUsuario {
            override fun disponivel() = Disponibilidade.NADA_CADASTRADO
            override suspend fun autenticar(titulo: String, descricao: String) = false
        }
        val provedor = ProvedorDeChavesAndroid(cofre, aut)
        val c = (runBlocking(Dispatchers.IO) { provedor.gerar("proteção real", TipoDeChave.ECDSA_P256, false) } as Resultado.Ok).valor
        try {
            mostrar(Acoes(listOf(c)))
            val esperada = when (c.protecao) {
                Protecao.HARDWARE -> "Protegida pelo hardware do aparelho"
                Protecao.SOFTWARE_COFRE -> "Protegida pelo Keystore (por software)"
                Protecao.KEYSTORE_ENVELOPE -> "Guardada cifrada no cofre do aparelho"
            }
            texto(esperada).assertIsDisplayed()
            assertTrue(provedor.linhaDaPublica(c, "teste")!!.startsWith("ecdsa-sha2-nistp256 "))
        } finally { provedor.excluir(c) }
    }
    @Test fun gerarExigeEscolherOTipoAntesDeSalvar() {
        val a = Acoes(); mostrar(a)
        clicar("computador-gerar-chave"); digitar("computador-chave-nome", "trabalho")
        no("computador-chave-salvar").assertIsNotEnabled()
        clicar("computador-chave-tipo-ED25519"); clicar("computador-chave-salvar")
        regra.waitForIdle()
        assertEquals(TipoDeChave.ED25519, a.tipoGerado)
        assertEquals(1, a.geracoes)
        texto("Protegida pelo hardware do aparelho").assertIsDisplayed()
    }
    @Test fun ecdsaP256EEscolhaExplicita() {
        val a = Acoes(); mostrar(a)
        clicar("computador-gerar-chave"); digitar("computador-chave-nome", "ec")
        clicar("computador-chave-tipo-ECDSA_P256"); clicar("computador-chave-salvar")
        regra.waitForIdle(); assertEquals(TipoDeChave.ECDSA_P256, a.tipoGerado)
        texto("ecdsa-sha2-nistp256").assertIsDisplayed()
    }
    @Test fun protecoesSaoAsTresReaisSemTrocarEnvelopePorHardware() {
        mostrar(Acoes(listOf(chave(), chave("00000002", Protecao.SOFTWARE_COFRE), chave("00000003", Protecao.KEYSTORE_ENVELOPE))), dark = true)
        texto("Protegida pelo hardware do aparelho").assertIsDisplayed()
        texto("Protegida pelo Keystore (por software)").assertIsDisplayed()
        texto("Guardada cifrada no cofre do aparelho").performScrollTo().assertIsDisplayed()
    }
    @Test fun passphraseErradaELimpaEPrivadaNuncaEntraEmSemantics() {
        val a = Acoes(); mostrar(a)
        clicar("computador-importar-chave"); digitar("computador-chave-nome", "importada")
        val privada = "-----BEGIN OPENSSH PRIVATE KEY-----SEGREDO-FALSO"
        digitar("computador-chave-privada", privada)
        varrerSegredos(privada, "SEGREDO-FALSO")
        no("computador-chave-privada").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.InputText))
        clicar("computador-chave-salvar")
        a.respostaImportacao = Resultado.Falha(ErroChave.PASSPHRASE)
        digitar("computador-chave-passphrase", "passphrase-falsa")
        varrerSegredos(privada, "passphrase-falsa")
        clicar("computador-chave-salvar"); regra.waitForIdle()
        no("computador-chave-erro").assertTextEquals("A senha da chave está errada.")
        assertTrue(a.buffers.all { buffer -> buffer.all { it == '\u0000' } })
        clicar("computador-chave-salvar"); regra.waitForIdle()
        assertEquals("", a.passphrases.last())
    }
    @Test fun importacaoComSucessoDescartaTextoEPassphrase() {
        val a = Acoes(); mostrar(a)
        clicar("computador-importar-chave"); digitar("computador-chave-nome", "importada")
        digitar("computador-chave-privada", "PRIVADA-FALSA")
        clicar("computador-chave-salvar")
        digitar("computador-chave-passphrase", "senha-falsa")
        a.respostaImportacao = Resultado.Ok(chave())
        clicar("computador-chave-salvar"); regra.waitForIdle()
        no("computador-chave-privada").assertDoesNotExist()
        assertTrue(a.buffers.all { b -> b.all { it == '\u0000' } })
        varrerSegredos("PRIVADA-FALSA", "senha-falsa")
    }
    @Test fun copiaPublicaEd25519SemAcessoAPrivada() = testarCopia(TipoDeChave.ED25519, publicaEd)
    @Test fun copiaPublicaEcdsaSemAcessoAPrivada() = testarCopia(TipoDeChave.ECDSA_P256, publicaEc)
    private fun testarCopia(tipo: TipoDeChave, esperada: String) {
        val a = Acoes(listOf(chave(tipo = tipo))); mostrar(a)
        clicar("computador-chave-00000001")
        regra.waitUntil(5_000) { regra.onAllNodesWithTag("computador-chave-publica", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        clicar("computador-chave-copiar")
        regra.runOnIdle {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            assertEquals(esperada, ctx.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        }
    }
    @Test fun biometriaAvisaAntesDeGravar() {
        val a = Acoes(); mostrar(a)
        clicar("computador-gerar-chave"); digitar("computador-chave-nome", "bio"); clicar("computador-chave-tipo-ED25519")
        clicar("computador-chave-biometria")
        texto("Se você cadastrar uma nova digital, esta chave deixa de funcionar. Uma autenticação libera a chave por 5 minutos.").assertIsDisplayed()
        assertEquals(0, a.geracoes)
        clicar("computador-biometria-confirmar"); clicar("computador-chave-salvar")
        regra.waitForIdle(); assertTrue(a.bioGerada)
    }
    @Test fun semBiometriaCadastradaMostraOMotivoEDesabilita() {
        val a = Acoes().also { it.disponibilidade = Disponibilidade.NADA_CADASTRADO }; mostrar(a)
        clicar("computador-gerar-chave")
        texto("Cadastre uma digital ou o rosto nas configurações do aparelho para usar esta opção.").performScrollTo().assertIsDisplayed()
        no("computador-chave-biometria").assertIsNotEnabled()
    }
    @Test fun excluirChaveListaHostsAfetadosEExigeConfirmar() {
        val a = Acoes(listOf(chave()))
        val h = Host("00000001", "servidor-teste", "example.org", 22, "teste", AutenticacaoHost.Chave("00000001"))
        mostrar(a, listOf(h)); clicar("computador-chave-00000001"); clicar("computador-chave-excluir")
        texto("Os hosts ficam sem chave e o agente perde o acesso: servidor-teste").assertIsDisplayed()
        assertEquals(1, a.chaves.size)
        clicar("computador-chave-excluir-confirmar"); regra.waitForIdle(); assertTrue(a.chaves.isEmpty())
    }
    @Test fun renomearPreservaIdentidade() {
        val c = chave(); val a = Acoes(listOf(c)); mostrar(a)
        clicar("computador-chave-00000001"); digitar("computador-chave-renomear", "novo nome"); clicar("computador-chave-renomear-salvar")
        regra.waitForIdle(); assertEquals(c.copy(nome = "novo nome"), a.chaves.single())
    }
    @Test fun alterarBiometriaPreservaChaveEHostsEOfereceOutra() {
        val c = chave(); val a = Acoes(listOf(c)); mostrar(a)
        clicar("computador-chave-00000001"); clicar("computador-chave-alterar-biometria")
        texto("A proteção de uma chave criada não pode ser alterada. Gere outra com a nova política e autorize a nova chave pública nos servidores. Esta chave e seus hosts serão preservados.").assertIsDisplayed()
        clicar("computador-chave-gerar-outra")
        assertEquals(listOf(c), a.chaves)
        assertEquals(0, a.geracoes)
    }
}
