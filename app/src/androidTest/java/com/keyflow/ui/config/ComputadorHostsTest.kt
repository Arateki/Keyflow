package com.keyflow.ui.config

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.computador.AutenticacaoHost
import com.keyflow.computador.ChaveDoServidor
import com.keyflow.computador.ConfigHosts
import com.keyflow.computador.EdicaoDeHost
import com.keyflow.computador.ErroHost
import com.keyflow.computador.Host
import com.keyflow.computador.ImpressaoDigital
import com.keyflow.computador.PoliticaAgente
import com.keyflow.computador.ResultadoEdicao
import com.keyflow.computador.ssh.CausaSsh
import com.keyflow.computador.ssh.ResultadoTeste
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** CMP-05, 07, 08, 09, 59, 61: o painel de hosts com a regra de gravação de verdade e um teste de conexão de mentira. */
@RunWith(AndroidJUnit4::class)
class ComputadorHostsTest {
    @get:Rule val regra = createComposeRule()

    private class Acoes(inicial: List<Host> = emptyList()) : AcoesDeHost {
        var hosts by mutableStateOf(ConfigHosts(inicial))
        val excluidos = mutableListOf<String>()
        val agenteDesligadoEm = mutableListOf<String>()
        var resultadoDoTeste: ResultadoTeste = ResultadoTeste.Falhou(CausaSsh.RECUSADA)
        val testados = mutableListOf<Pair<Host, String?>>()
        val senhas = inicial.filter { it.autenticacao == AutenticacaoHost.Senha && it.lembrarSenha }.associate { it.id to "senha-falsa" }.toMutableMap()

        override suspend fun salvar(host: Host, senha: String?): List<ErroHost> = when (val r = EdicaoDeHost.aplicar(hosts, host, credencialDisponivel = !senha.isNullOrEmpty() || !senhas[host.id].isNullOrEmpty())) {
            is ResultadoEdicao.Erros -> r.erros
            is ResultadoEdicao.Ok -> { hosts = r.config; senha?.let { senhas[r.host.id] = it }; emptyList() }
        }
        override suspend fun temSenhaGuardada(host: Host) = !senhas[host.id].isNullOrEmpty()
        override suspend fun excluir(host: Host) { excluidos += host.id; hosts = ConfigHosts(hosts.hosts.filter { it.id != host.id }) }
        override suspend fun testar(host: Host, senha: String?): ResultadoTeste { testados += host to senha; return resultadoDoTeste }
        override fun impressoesConhecidas(host: Host): List<ChaveDoServidor> = emptyList()
        override fun esquecerServidor(host: Host) = Unit
        override suspend fun desligarAgente(host: Host) { agenteDesligadoEm += host.id }
        override fun novoId(existentes: Collection<String>) = "%08x".format(existentes.size + 100)
    }

    private fun mostrar(acoes: Acoes, dark: Boolean = false) {
        regra.setContent { KeyFlowTheme(dark = dark) { ComputadorPanel(hosts = acoes.hosts, chaves = emptyList(), sessoes = emptyList(), acoes = acoes) } }
    }

    private fun nt(tag: String) = regra.onNodeWithTag(tag, useUnmergedTree = true)
    private fun nh(texto: String, substring: Boolean = false) = regra.onNode(hasText(texto, substring = substring), useUnmergedTree = true)

    private fun digitar(tag: String, texto: String) {
        nt(tag).performScrollTo().performTextReplacement(texto)
    }

    private fun preencher(nome: String, endereco: String = "dev.example.org", usuario: String = "yan", porta: String? = null) {
        digitar(COMPUTADOR_HOST_NOME_TAG, nome)
        digitar(COMPUTADOR_HOST_ENDERECO_TAG, endereco)
        digitar(COMPUTADOR_HOST_USUARIO_TAG, usuario)
        porta?.let { digitar(COMPUTADOR_HOST_PORTA_TAG, it) }
    }

    private fun clicar(tag: String) { nt(tag).performScrollTo().performClick() }

    private fun senhaLembrada(id: String = "00000001") = Host(id, "dev", "dev.example.org", 22, "yan", AutenticacaoHost.Senha, lembrarSenha = true)

    @Test fun tresSecoesEHostsVazioMostraOConvite() {
        mostrar(Acoes())
        nt(COMPUTADOR_SECAO_HOSTS_TAG).assertIsDisplayed()
        nt(COMPUTADOR_SECAO_CHAVES_TAG).assertIsDisplayed()
        nt(COMPUTADOR_SECAO_SESSOES_TAG).assertIsDisplayed()
        nt(COMPUTADOR_HOSTS_VAZIO_TAG).assertIsDisplayed()
    }

    @Test fun criarHostValidoGrava() {
        val a = Acoes()
        mostrar(a)
        clicar(COMPUTADOR_NOVO_HOST_TAG)
        preencher("dev-server")
        clicar(COMPUTADOR_HOST_SALVAR_TAG)
        regra.waitForIdle()
        val h = a.hosts.hosts.single()
        assertEquals("dev-server", h.nome)
        assertEquals("dev.example.org", h.endereco)
        assertEquals(22, h.porta)
        assertEquals(PoliticaAgente.PROIBIDO, h.agente)
        nt(computadorHostCardTag(h.id)).assertIsDisplayed()
    }

    @Test fun porta0ENomeRepetidoMostramOMotivo() {
        val a = Acoes(listOf(senhaLembrada()))
        mostrar(a)
        clicar(COMPUTADOR_NOVO_HOST_TAG)
        preencher("DEV", porta = "0")
        clicar(COMPUTADOR_HOST_SALVAR_TAG)
        nt("computador-erro-porta").performScrollTo().assertIsDisplayed()
        nh("A porta vai de 1 a 65535.").assertIsDisplayed()
        nh("Já existe um servidor com esse nome.").assertIsDisplayed()
        assertEquals(1, a.hosts.hosts.size)
    }

    @Test fun testarConexaoMostraImpressaoELatenciaEUsaASenhaDigitada() {
        val a = Acoes()
        a.resultadoDoTeste = ResultadoTeste.Ok("ssh-ed25519", ImpressaoDigital.de(ByteArray(8) { 3 }), "password", 42)
        mostrar(a)
        clicar(COMPUTADOR_NOVO_HOST_TAG)
        preencher("dev")
        clicar(COMPUTADOR_HOST_AUTH_SENHA_TAG)
        digitar(COMPUTADOR_HOST_SENHA_TAG, "senha-falsa")
        clicar(COMPUTADOR_HOST_TESTAR_TAG)
        regra.waitUntil(5_000) { a.testados.isNotEmpty() }
        nt(COMPUTADOR_HOST_TESTE_RESULTADO_TAG).performScrollTo()
        nh("SHA256:", substring = true).assertIsDisplayed()
        nh("42 ms", substring = true).assertIsDisplayed()
        assertEquals("senha-falsa", a.testados.single().second)
        assertTrue("o teste não grava o host", a.hosts.hosts.isEmpty())
    }

    @Test fun testarContraPortaFechadaDizOMotivoENuncaAFraseAntiga() {
        val a = Acoes()
        mostrar(a)
        clicar(COMPUTADOR_NOVO_HOST_TAG)
        preencher("dev")
        clicar(COMPUTADOR_HOST_TESTAR_TAG)
        regra.waitUntil(5_000) { a.testados.isNotEmpty() }
        nt(COMPUTADOR_HOST_TESTE_RESULTADO_TAG).performScrollTo()
        nh("O servidor recusou a conexão.").assertIsDisplayed()
        nh("Configuração SSH válida", substring = true).assertDoesNotExist()
    }

    @Test fun marcarAgenteMostraOAvisoESoLigaAoConfirmar() {
        val a = Acoes(listOf(senhaLembrada()))
        mostrar(a)
        clicar(computadorHostCardTag("00000001"))
        clicar(COMPUTADOR_HOST_AGENTE_TAG)
        nt(COMPUTADOR_AGENTE_AVISO_TAG).assertIsDisplayed()
        nh("O agente poderá abrir terminais neste servidor e ler o texto da tela; esse texto é enviado ao provedor do modelo.").assertIsDisplayed()
        nt(COMPUTADOR_AGENTE_AVISO_CANCELAR_TAG).performClick()
        clicar(COMPUTADOR_HOST_SALVAR_TAG); regra.waitForIdle()
        assertEquals("cancelar não liga", PoliticaAgente.PROIBIDO, a.hosts.hosts.single().agente)
        clicar(computadorHostCardTag("00000001"))
        clicar(COMPUTADOR_HOST_AGENTE_TAG)
        nt(COMPUTADOR_AGENTE_AVISO_CONFIRMAR_TAG).performClick()
        clicar(COMPUTADOR_HOST_SALVAR_TAG); regra.waitForIdle()
        assertEquals(PoliticaAgente.PERMITIDO, a.hosts.hosts.single().agente)
    }

    @Test fun hostComAutenticacaoPerguntarNaoDeixaMarcarEDizOMotivo() {
        val a = Acoes(listOf(Host("00000001", "dev", "dev.example.org", 22, "yan", AutenticacaoHost.Perguntar)))
        mostrar(a)
        clicar(computadorHostCardTag("00000001"))
        nt(COMPUTADOR_HOST_AGENTE_MOTIVO_TAG).performScrollTo().assertIsDisplayed()
        clicar(COMPUTADOR_HOST_AGENTE_TAG)
        nt(COMPUTADOR_AGENTE_AVISO_TAG).assertDoesNotExist()
        assertEquals(PoliticaAgente.PROIBIDO, a.hosts.hosts.single().agente)
    }

    @Test fun desligarAMarcaFechaAsSessoesDoAgente() {
        val a = Acoes(listOf(senhaLembrada().copy(agente = PoliticaAgente.PERMITIDO)))
        mostrar(a)
        clicar(computadorHostCardTag("00000001"))
        clicar(COMPUTADOR_HOST_AGENTE_TAG)
        clicar(COMPUTADOR_HOST_SALVAR_TAG); regra.waitForIdle()
        assertEquals(PoliticaAgente.PROIBIDO, a.hosts.hosts.single().agente)
        assertEquals(listOf("00000001"), a.agenteDesligadoEm)
    }

    @Test fun excluirPedeConfirmacao() {
        val a = Acoes(listOf(senhaLembrada()))
        mostrar(a, dark = true)
        clicar(computadorHostCardTag("00000001"))
        clicar(COMPUTADOR_HOST_EXCLUIR_TAG)
        assertTrue("só pedir não exclui", a.excluidos.isEmpty())
        nt(COMPUTADOR_HOST_EXCLUIR_CONFIRMAR_TAG).performClick()
        regra.waitForIdle()
        assertEquals(listOf("00000001"), a.excluidos)
        assertFalse(a.hosts.hosts.any())
    }
    @Test fun semChavesNaoCriaChaveComReferenciaVazia() {
        val a = Acoes(); mostrar(a)
        clicar(COMPUTADOR_NOVO_HOST_TAG); preencher("dev")
        nt(COMPUTADOR_HOST_AUTH_CHAVE_TAG).performScrollTo().assertIsNotEnabled()
        clicar(COMPUTADOR_HOST_SALVAR_TAG); regra.waitForIdle()
        assertEquals(AutenticacaoHost.Perguntar, a.hosts.hosts.single().autenticacao)
    }

    @Test fun lembrarSemSenhaNaoLiberaAgenteNemGrava() {
        val a = Acoes(); mostrar(a)
        clicar(COMPUTADOR_NOVO_HOST_TAG); preencher("dev")
        clicar(COMPUTADOR_HOST_AUTH_SENHA_TAG); clicar(COMPUTADOR_HOST_LEMBRAR_TAG)
        nt(COMPUTADOR_HOST_AGENTE_TAG).performScrollTo().assertIsNotEnabled()
        clicar(COMPUTADOR_HOST_SALVAR_TAG)
        nt(computadorErroTag(com.keyflow.computador.CampoHost.AUTENTICACAO)).performScrollTo().assertIsDisplayed()
        assertTrue(a.hosts.hosts.isEmpty()); assertTrue(a.senhas.isEmpty())
    }

    @Test fun preferenciaAntigaSemSegredoNaoPermiteMarcar() {
        val a = Acoes(listOf(senhaLembrada())); a.senhas.clear(); mostrar(a)
        clicar(computadorHostCardTag("00000001"))
        nt(COMPUTADOR_HOST_AGENTE_TAG).performScrollTo().assertIsNotEnabled()
        clicar(COMPUTADOR_HOST_SALVAR_TAG)
        nt(computadorErroTag(com.keyflow.computador.CampoHost.AUTENTICACAO)).performScrollTo().assertIsDisplayed()
        assertFalse(a.hosts.hosts.single().agentePodeUsar)
    }

    @Test fun senhaDigitadaPermiteConfirmarEGravarAgente() {
        val a = Acoes(); mostrar(a)
        clicar(COMPUTADOR_NOVO_HOST_TAG); preencher("dev")
        clicar(COMPUTADOR_HOST_AUTH_SENHA_TAG); digitar(COMPUTADOR_HOST_SENHA_TAG, "senha-falsa")
        clicar(COMPUTADOR_HOST_LEMBRAR_TAG); clicar(COMPUTADOR_HOST_AGENTE_TAG)
        nt(COMPUTADOR_AGENTE_AVISO_CONFIRMAR_TAG).performClick()
        clicar(COMPUTADOR_HOST_SALVAR_TAG); regra.waitForIdle()
        val h = a.hosts.hosts.single()
        assertTrue(h.agentePodeUsar); assertEquals("senha-falsa", a.senhas[h.id])
    }
}
