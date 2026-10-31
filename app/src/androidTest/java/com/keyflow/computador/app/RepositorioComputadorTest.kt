package com.keyflow.computador.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.auth.SecureCredentialStore
import com.keyflow.computador.AutenticacaoHost
import com.keyflow.computador.ChaveDoServidor
import com.keyflow.computador.ConfigHosts
import com.keyflow.computador.Host
import com.keyflow.computador.ImpressaoDigital
import com.keyflow.computador.PoliticaAgente
import com.keyflow.computador.chaves.ChaveSsh
import com.keyflow.computador.chaves.CofreDeSegredos
import com.keyflow.computador.chaves.ErroChave
import com.keyflow.computador.chaves.Protecao
import com.keyflow.computador.chaves.Resultado
import com.keyflow.computador.chaves.TipoDeChave
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CMP-T11: persistência de hosts, chaves e known_hosts e a migração do host único, sobre o DataStore e o cofre de verdade. */
@RunWith(AndroidJUnit4::class)
class RepositorioComputadorTest {
    private lateinit var ctx: Context
    private lateinit var dataStoreFile: File
    private lateinit var knownHosts: File
    private lateinit var securePrefsName: String
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class CofreMem : CofreDeSegredos {
        val itens = linkedMapOf<String, String>()
        override fun ler(chave: String) = itens[chave]
        override fun gravar(chave: String, valor: String) { itens[chave] = valor }
        override fun apagar(chave: String) { itens.remove(chave) }
        override fun valores() = itens.values.toList()
    }

    @Before fun preparar() {
        ctx = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        dataStoreFile = File(ctx.filesDir, "test-datastore/computador-$id.preferences_pb")
        knownHosts = File(ctx.filesDir, "test-known-hosts/$id/known_hosts")
        securePrefsName = "test_keyflow_secure_computador_$id"
    }

    @After fun limpar() {
        escopo.cancel()
        dataStoreFile.delete(); dataStoreFile.parentFile?.delete()
        knownHosts.parentFile?.deleteRecursively()
        ctx.deleteSharedPreferences(securePrefsName)
        ctx.deleteSharedPreferences("${SecureCredentialStore.PREFS_NAME}_$securePrefsName")
    }

    private fun settings() = SettingsRepository(context = ctx, dataStoreFile = dataStoreFile, securePrefsName = securePrefsName, scope = escopo)
    private fun repo(cofre: CofreDeSegredos = CofreMem(), s: SettingsRepository = settings()) = RepositorioComputador(s, cofre, knownHosts)
    private fun host(n: Int, auth: AutenticacaoHost = AutenticacaoHost.Perguntar) = Host("%08x".format(n), "h$n", "srv$n.example.org", 22, "yan", auth)
    private fun chave(id: String) = ChaveSsh(id, "k-$id", TipoDeChave.ED25519, ImpressaoDigital.de(id.toByteArray()), Protecao.KEYSTORE_ENVELOPE, false, 1L)

    @Test fun leituraSemChaveEListaVaziaENadaGravado() = runBlocking {
        val s = settings()
        val r = repo(s = s)
        assertEquals(ConfigHosts(), r.hostsOnce())
        assertEquals(emptyList<ChaveSsh>(), r.chavesOnce())
        val prefs = s.rawPreferencesOnce().asMap().keys.map { it.name }
        assertFalse(prefs.toString(), "computer_hosts_json" in prefs || "computer_keys_json" in prefs || "computer_migrated" in prefs)
    }

    @Test fun duasAlteracoesConcorrentesFicamAmbas() = runBlocking {
        val r = repo()
        (1..20).map { n -> async(Dispatchers.IO) { r.atualizarHosts { c -> ConfigHosts(c.hosts + host(n)) } } }.awaitAll()
        assertEquals((1..20).map { "%08x".format(it) }.toSet(), r.hostsOnce().hosts.map { it.id }.toSet())
    }

    @Test fun saveSettingsNaoTocaAChave() = runBlocking {
        val s = settings()
        val r = repo(s = s)
        r.atualizarHosts { ConfigHosts(listOf(host(1))) }
        s.saveSettings(Settings())
        assertEquals(listOf(host(1)), r.hostsOnce().hosts)
    }

    @Test fun migracaoDoHostUnicoMoveOsSegredosESoApagaDepois() = runBlocking {
        val s = settings()
        s.saveSettings(Settings(computerSshHost = "legado.example.org", computerSshPort = 2200, computerSshUser = "yan", computerSshAuthType = "key", computerSshPassword = "SENHA-FALSA-LEGADO", computerSshKey = "PEM-FALSO-LEGADO"))
        val cofre = CofreMem()
        val r = repo(cofre, s)
        var recebido: String? = null
        val migrou = r.migrarHostUnico { pem -> recebido = String(pem); Resultado.Ok(chave("kmigrada")) }
        assertTrue(migrou)
        assertEquals("PEM-FALSO-LEGADO", recebido)
        val h = r.hostsOnce().hosts.single()
        assertEquals("Servidor SSH", h.nome); assertEquals("legado.example.org", h.endereco); assertEquals(2200, h.porta)
        assertEquals(AutenticacaoHost.Chave("kmigrada"), h.autenticacao)
        assertEquals(listOf("kmigrada"), r.chavesOnce().map { it.id })
        // O cofre antigo ficou sem os segredos; o novo não guarda a senha (a auth é por chave).
        val depois = s.settingsFlow().first()
        assertEquals("", depois.computerSshPassword); assertEquals("", depois.computerSshKey)
        // Segunda partida: não duplica.
        assertFalse(r.migrarHostUnico { Resultado.Ok(chave("outra")) })
        assertEquals(1, r.hostsOnce().hosts.size)
        assertEquals(1, r.chavesOnce().size)
    }

    @Test fun migracaoPorSenhaGuardaSoNoCofreNovo() = runBlocking {
        val s = settings()
        s.saveSettings(Settings(computerSshHost = "legado.example.org", computerSshUser = "yan", computerSshAuthType = "password", computerSshPassword = "SENHA-FALSA-LEGADO"))
        val cofre = CofreMem()
        val r = repo(cofre, s)
        assertTrue(r.migrarHostUnico { error("sem chave") })
        val h = r.hostsOnce().hosts.single()
        assertEquals(AutenticacaoHost.Senha, h.autenticacao); assertTrue(h.lembrarSenha)
        assertEquals("SENHA-FALSA-LEGADO", cofre.itens["ssh_pw.${h.id}"])
        assertEquals("", s.settingsFlow().first().computerSshPassword)
        // Nenhum JSON persistido leva o segredo.
        val json = s.rawPreferencesOnce().asMap().values.joinToString { it.toString() }
        assertFalse(json.contains("SENHA-FALSA-LEGADO"))
    }

    @Test fun chaveLegadaQueNaoImportaNaoESApagada() = runBlocking {
        val s = settings()
        s.saveSettings(Settings(computerSshHost = "legado.example.org", computerSshUser = "yan", computerSshAuthType = "key", computerSshKey = "PEM-FALSO-LEGADO"))
        val r = repo(s = s)
        assertTrue(r.migrarHostUnico { Resultado.Falha(ErroChave.FORMATO) })
        assertEquals(AutenticacaoHost.Perguntar, r.hostsOnce().hosts.single().autenticacao)
        assertEquals("PEM-FALSO-LEGADO", s.settingsFlow().first().computerSshKey)
    }

    @Test fun semHostLegadoNaoMigraNada() = runBlocking {
        val r = repo()
        assertFalse(r.migrarHostUnico { error("não deveria importar") })
        assertEquals(ConfigHosts(), r.hostsOnce())
    }

    @Test fun excluirHostFechaSessoesEsqueceServidorApagaSenhaEMantemAChave() = runBlocking {
        val cofre = CofreMem()
        val r = repo(cofre)
        val h = host(1, AutenticacaoHost.Chave("k1")).copy(agente = PoliticaAgente.PERMITIDO)
        val outro = host(2)
        r.atualizarHosts { ConfigHosts(listOf(h, outro)) }
        r.atualizarChaves { listOf(chave("k1")) }
        r.lembrarSenha(h.id, "SENHA-FALSA")
        r.confianca.confiar(h.endereco, h.porta, ChaveDoServidor("ssh-ed25519", ByteArray(32) { 1 }))
        r.confianca.confiar(outro.endereco, outro.porta, ChaveDoServidor("ssh-ed25519", ByteArray(32) { 2 }))
        val fechados = mutableListOf<String>()
        r.excluirHost(h.id) { fechados += it }
        assertEquals(listOf(h.id), fechados)
        assertEquals(listOf(outro), r.hostsOnce().hosts)
        assertTrue(r.confianca.chavesConhecidas(h.endereco, h.porta).isEmpty())
        assertEquals(1, r.confianca.chavesConhecidas(outro.endereco, outro.porta).size)
        assertNull(r.senhaLembrada(h.id))
        assertEquals(listOf("k1"), r.chavesOnce().map { it.id })
    }

    @Test fun excluirChaveEmUsoDeixaOsHostsSemChaveSemAMarcaDoAgente() = runBlocking {
        val r = repo()
        val usa = host(1, AutenticacaoHost.Chave("k1")).copy(agente = PoliticaAgente.PERMITIDO)
        val nao = host(2, AutenticacaoHost.Chave("k2"))
        r.atualizarHosts { ConfigHosts(listOf(usa, nao)) }
        r.atualizarChaves { listOf(chave("k1"), chave("k2")) }
        assertEquals(listOf(usa), r.hostsDaChave("k1"))
        val apagadas = mutableListOf<String>()
        val afetados = r.excluirChave(chave("k1")) { apagadas += it.id }
        assertEquals(listOf(usa), afetados)
        assertEquals(listOf("k1"), apagadas)
        val hosts = r.hostsOnce().hosts
        assertEquals(AutenticacaoHost.Perguntar, hosts.first { it.id == usa.id }.autenticacao)
        assertFalse(hosts.first { it.id == usa.id }.agentePodeUsar)
        assertEquals(nao, hosts.first { it.id == nao.id })
        assertEquals(listOf("k2"), r.chavesOnce().map { it.id })
    }

    @Test fun knownHostsEmArquivoSobreviveAReabrirELinhaAlheiaFica() {
        knownHosts.parentFile!!.mkdirs()
        knownHosts.writeText("# meu comentario\nlixo sem formato\n")
        val k = ChaveDoServidor("ssh-ed25519", ByteArray(32) { 5 })
        repo().confianca.confiar("a.example.org", 2222, k)
        val reaberto = repo().confianca
        assertEquals(listOf(k), reaberto.chavesConhecidas("a.example.org", 2222))
        assertTrue(knownHosts.readText().startsWith("# meu comentario\nlixo sem formato\n"))
        assertTrue(knownHosts.readText().contains("[a.example.org]:2222 ssh-ed25519 "))
        assertFalse(File(knownHosts.path + ".tmp").exists())
    }
}
