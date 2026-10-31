package com.keyflow.computador.app

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.subtle.Ed25519Verify
import com.keyflow.computador.chaves.Assinador
import com.keyflow.computador.chaves.AvaliadorDaSonda
import com.keyflow.computador.chaves.ChavesSoftware
import com.keyflow.computador.chaves.CofreDeSegredos
import com.keyflow.computador.chaves.EstadoChave
import com.keyflow.computador.chaves.FormatadorChavePublica
import com.keyflow.computador.chaves.Protecao
import com.keyflow.computador.chaves.RelogioDeChaves
import com.keyflow.computador.chaves.Resultado
import com.keyflow.computador.chaves.ChaveSsh
import com.keyflow.computador.chaves.TipoDeChave
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CMP-T8 no emulador (API 34): Keystore, envelope e janela de biometria. Aparelhos físicos ficam para a T21. */
@RunWith(AndroidJUnit4::class)
class ChavesAndroidTest {
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Cofre em SharedPreferences **em claro** (arquivo conhecido): o que o envelope grava dá para varrer. */
    private class CofrePrefs(ctx: Context) : CofreDeSegredos {
        val nome = "keyflow_chaves_teste"
        private val prefs = ctx.getSharedPreferences(nome, Context.MODE_PRIVATE)
        override fun ler(chave: String) = prefs.getString(chave, null)
        override fun gravar(chave: String, valor: String) { prefs.edit().putString(chave, valor).commit() }
        override fun apagar(chave: String) { prefs.edit().remove(chave).commit() }
        override fun valores(): Collection<String> = prefs.all.values.filterIsInstance<String>()
        fun chaves(): Set<String> = prefs.all.keys
        fun limpar() { prefs.edit().clear().commit() }
    }

    private class AutenticadorFalso(var responde: Boolean = true, var disponivel: Disponibilidade = Disponibilidade.SIM) : AutenticadorDeUsuario {
        var prompts = 0
        override fun disponivel() = disponivel
        override suspend fun autenticar(titulo: String, descricao: String): Boolean { prompts++; return responde }
    }

    private class RelogioFalso(var ms: Long = 5_000_000L) : RelogioDeChaves { override fun agoraMs() = ms }

    private lateinit var cofre: CofrePrefs
    private val criadas = mutableListOf<Pair<ProvedorDeChavesAndroid, ChaveSsh>>()

    @Before fun preparar() { cofre = CofrePrefs(ctx).also { it.limpar() } }

    @After fun limpar() {
        criadas.forEach { (p, c) -> runCatching { p.excluir(c) } }
        cofre.limpar()
    }

    private fun provedor(
        autenticador: AutenticadorDeUsuario = AutenticadorFalso(),
        relogio: RelogioDeChaves = RelogioFalso(),
        forcarEnvelope: Boolean = false,
        aplicarBio: Boolean = true,
        sonda: SondaEd25519 = SondaEd25519(),
        semente: (() -> ChavesSoftware.Ed25519Gerada)? = null,
        detector: DetectorDeCadastro? = null,
    ) = ProvedorDeChavesAndroid(
        cofre, autenticador, relogio, sonda = sonda, forcarEnvelopeEd25519 = forcarEnvelope, aplicarBiometriaNoKeystore = aplicarBio,
        gerarEd25519Software = semente ?: ChavesSoftware::gerarEd25519,
        detector = detector ?: DetectorDeCadastroKeystore(),
    )

    private fun gerar(p: ProvedorDeChavesAndroid, tipo: TipoDeChave, bio: Boolean = false): ChaveSsh {
        val r = runBlocking { p.gerar("teste-$tipo", tipo, bio) }
        val chave = (r as? Resultado.Ok)?.valor ?: error("gerar falhou: $r")
        criadas += p to chave
        return chave
    }

    private fun campos(blob: ByteArray): List<ByteArray> {
        val b = ByteBuffer.wrap(blob)
        return buildList { while (b.hasRemaining()) add(ByteArray(b.int).also { b.get(it) }) }
    }

    private val dados = "dados da autenticacao ssh".toByteArray()

    private fun verificaEd25519(a: Assinador, assinatura: ByteArray) {
        val partes = campos(assinatura)
        assertEquals("ssh-ed25519", String(partes[0]))
        Ed25519Verify(campos(a.chavePublica().blob)[1]).verify(partes[1], dados)
    }

    private fun verificaP256(a: Assinador, assinatura: ByteArray): Boolean {
        val partes = campos(assinatura)
        assertEquals("ecdsa-sha2-nistp256", String(partes[0]))
        val rs = campos(partes[1])
        val ponto = campos(a.chavePublica().blob)[2]
        val params = (KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as java.security.interfaces.ECPublicKey).params
        val pub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, ponto.copyOfRange(1, 33)), BigInteger(1, ponto.copyOfRange(33, 65))), params))
        fun inteiro(v: BigInteger) = v.toByteArray().let { byteArrayOf(0x02, it.size.toByte()) + it }
        val corpo = inteiro(BigInteger(rs[0])) + inteiro(BigInteger(rs[1]))
        return Signature.getInstance("SHA256withECDSA").apply { initVerify(pub); update(dados) }.verify(byteArrayOf(0x30, corpo.size.toByte()) + corpo)
    }

    // ---- ECDSA P-256 ---------------------------------------------------------------------------------------------------------

    @Test fun p256AssinaEAAssinaturaVerificaComAPublicaExportada() {
        val p = provedor()
        val chave = gerar(p, TipoDeChave.ECDSA_P256)
        val a = p.assinador(chave)
        assertTrue(verificaP256(a, runBlocking { a.assinar(dados) }!!))
        assertEquals("ecdsa-sha2-nistp256", p.linhaDaPublica(chave, "emulador")!!.substringBefore(' '))
        assertTrue(p.linhaDaPublica(chave, "emulador")!!.endsWith("keyflow@emulador"))
        assertEquals(chave.impressao, a.chavePublica().impressao)
    }

    @Test fun p256NaoCaiNoEnvelope() {
        val p = provedor(forcarEnvelope = true) // a flag de teste só vale para ed25519
        val chave = gerar(p, TipoDeChave.ECDSA_P256)
        assertTrue("proteção ${chave.protecao}", chave.protecao == Protecao.HARDWARE || chave.protecao == Protecao.SOFTWARE_COFRE)
        assertTrue("o cofre não pode ter material do P-256: ${cofre.chaves()}", cofre.chaves().none { it.startsWith("ssh_key.") })
        assertFalse(p.chavePublica(chave) == null)
    }

    @Test fun p256ReiniciarOProcessoEReler() {
        val chave = gerar(provedor(), TipoDeChave.ECDSA_P256)
        // "Reiniciar": um provedor novo, sem nenhum estado em memória, só com os metadados da chave.
        val novo = provedor()
        val a = novo.assinador(chave)
        assertTrue(verificaP256(a, runBlocking { a.assinar(dados) }!!))
    }

    // ---- Ed25519 -------------------------------------------------------------------------------------------------------------

    @Test fun ed25519AssinaEVerificaEAPublicaTemFormatoEd25519() {
        val p = provedor()
        val chave = gerar(p, TipoDeChave.ED25519)
        val a = p.assinador(chave)
        assertEquals("ssh-ed25519", a.chavePublica().tipoDoBlob)
        assertEquals(32, campos(a.chavePublica().blob)[1].size)
        verificaEd25519(a, runBlocking { a.assinar(dados) }!!)
        // Onde a sonda passa, a chave é nativa; senão, envelope — e a tela diz qual (a proteção está na chave).
        if (p.ed25519VaiParaOKeystore()) assertTrue(chave.protecao != Protecao.KEYSTORE_ENVELOPE) else assertEquals(Protecao.KEYSTORE_ENVELOPE, chave.protecao)
    }

    @Test fun ed25519ReiniciarOProcessoEReler() {
        val chave = gerar(provedor(), TipoDeChave.ED25519)
        val a = provedor().assinador(chave)
        verificaEd25519(a, runBlocking { a.assinar(dados) }!!)
    }

    @Test fun envelopeForcadoAssinaIgualENaoDeixaASementeEmClaro() {
        var semente: ByteArray? = null
        val p = provedor(forcarEnvelope = true, semente = { ChavesSoftware.gerarEd25519().also { semente = it.material.bytes.copyOf() } })
        val chave = gerar(p, TipoDeChave.ED25519)
        assertEquals(Protecao.KEYSTORE_ENVELOPE, chave.protecao)
        val a = p.assinador(chave)
        verificaEd25519(a, runBlocking { a.assinar(dados) }!!)
        assertNotNull(cofre.ler("ssh_key.${chave.id}"))
        varrerPorSemente(semente!!)
    }

    @Test fun envelopeNaoAbreComOutroId() {
        val p = provedor(forcarEnvelope = true)
        val a = gerar(p, TipoDeChave.ED25519)
        val b = gerar(p, TipoDeChave.ED25519)
        // Troca o texto cifrado de A para o lugar de B: o AAD (id) e o alias diferentes impedem a leitura.
        cofre.gravar("ssh_key.${b.id}", cofre.ler("ssh_key.${a.id}")!!)
        assertNull(runBlocking { p.assinador(b).assinar(dados) })
    }

    /** A semente (em bytes, Base64 e hexa) não aparece em nenhum arquivo do app (prefs, filesDir, databases). */
    private fun varrerPorSemente(semente: ByteArray) {
        val b64 = Base64.getEncoder().encodeToString(semente)
        val b64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(semente)
        val hex = semente.joinToString("") { "%02x".format(it) }
        val arquivos = listOf(ctx.filesDir.parentFile!!).flatMap { raiz -> raiz.walkTopDown().filter { it.isFile }.toList() }
        assertTrue(arquivos.isNotEmpty())
        for (f in arquivos) {
            val conteudo = runCatching { f.readBytes() }.getOrNull() ?: continue
            assertFalse("semente crua em ${f.path}", indexOf(conteudo, semente) >= 0)
            val texto = String(conteudo, Charsets.ISO_8859_1)
            assertFalse("semente em Base64 em ${f.path}", texto.contains(b64) || texto.contains(b64Url))
            assertFalse("semente em hexa em ${f.path}", texto.contains(hex))
        }
    }

    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        outer@ for (i in 0..h.size - n.size) { for (j in n.indices) if (h[i + j] != n[j]) continue@outer; return i }
        return -1
    }

    // ---- sonda ---------------------------------------------------------------------------------------------------------------

    @Test fun sondaReprovaProvedorQueDevolveP256ELeva_o_ed25519ParaOEnvelope() {
        val falsoP256 = GeradorEd25519Keystore { alias ->
            val g = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
            g.initialize(android.security.keystore.KeyGenParameterSpec.Builder(alias, android.security.keystore.KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256).build())
            val par = g.generateKeyPair()
            object : CandidataEd25519 {
                override val spki = par.public.encoded
                override fun assinar(dados: ByteArray) = ByteArray(64)
                override fun apagar() { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
            }
        }
        val sonda = SondaEd25519(api = 34, gerador = falsoP256)
        val v = sonda.avaliar()
        assertTrue("reprovada: $v", v is AvaliadorDaSonda.Veredito.Reprovada)
        val p = provedor(sonda = sonda)
        assertFalse(p.ed25519VaiParaOKeystore())
        val chave = gerar(p, TipoDeChave.ED25519)
        assertEquals(Protecao.KEYSTORE_ENVELOPE, chave.protecao)
        assertEquals(Protecao.KEYSTORE_ENVELOPE.frase(), "Guardada cifrada no cofre do aparelho")
        verificaEd25519(p.assinador(chave), runBlocking { p.assinador(chave).assinar(dados) }!!)
    }

    @Test fun sondaDeProducaoNesteEmuladorTemVeredito() {
        val v = SondaEd25519().avaliar()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "CMP_T8 API=${Build.VERSION.SDK_INT} sonda=$v\n") })
        if (Build.VERSION.SDK_INT < 33) assertTrue(v is AvaliadorDaSonda.Veredito.Reprovada)
    }

    // ---- biometria (janela de 300 s) -----------------------------------------------------------------------------------------

    @Test fun semBiometriaCadastradaAOpcaoFicaDesligadaComOMotivo() {
        val aut = AutenticadorFalso(disponivel = Disponibilidade.NADA_CADASTRADO)
        val r = runBlocking { provedor(autenticador = aut).gerar("x", TipoDeChave.ECDSA_P256, exigeBiometria = true) }
        assertEquals(com.keyflow.computador.chaves.ErroChave.SEM_BIOMETRIA, (r as Resultado.Falha).erro)
        assertTrue(Disponibilidade.NADA_CADASTRADO.frase.isNotEmpty())
    }

    @Test fun promptUmaVezEDepoisDaJanelaDeNovo() {
        val aut = AutenticadorFalso()
        val relogio = RelogioFalso()
        // aplicarBio=false: o aparelho de teste não tem biometria cadastrada; o Keystore não exige nada e a janela é a da nossa lógica.
        val p = provedor(autenticador = aut, relogio = relogio, aplicarBio = false)
        val chave = gerar(p, TipoDeChave.ECDSA_P256, bio = true)
        assertTrue(chave.exigeBiometria)
        val a = p.assinador(chave)
        assertNotNull(runBlocking { a.assinar(dados) })
        assertEquals("primeiro uso: um prompt", 1, aut.prompts)
        relogio.ms += 299_000
        assertNotNull(runBlocking { a.assinar(dados) })
        assertEquals("dentro dos 300 s: sem novo prompt", 1, aut.prompts)
        relogio.ms += 2_000
        assertNotNull(runBlocking { a.assinar(dados) })
        assertEquals("depois da janela: prompt de novo", 2, aut.prompts)
    }

    @Test fun promptCanceladoDevolveNullSemExcecao() {
        val aut = AutenticadorFalso(responde = false)
        for (tipo in listOf(TipoDeChave.ECDSA_P256, TipoDeChave.ED25519)) {
            val p = provedor(autenticador = aut, aplicarBio = false, forcarEnvelope = tipo == TipoDeChave.ED25519)
            // Criar com prompt cancelado só vale no envelope (que precisa da janela para cifrar): libera na criação e cancela no uso.
            aut.responde = true
            val chave = gerar(p, tipo, bio = true)
            aut.responde = false
            val relogio = RelogioFalso()
            val novo = ProvedorDeChavesAndroid(cofre, aut, relogio, aplicarBiometriaNoKeystore = false)
            assertNull("$tipo: prompt cancelado", runBlocking { novo.assinador(chave).assinar(dados) })
        }
    }

    @Test fun envelopeComBiometriaAssinaDentroDaJanela() {
        val aut = AutenticadorFalso()
        val relogio = RelogioFalso()
        val p = provedor(autenticador = aut, relogio = relogio, forcarEnvelope = true, aplicarBio = false)
        val chave = gerar(p, TipoDeChave.ED25519, bio = true)
        val promptsNaCriacao = aut.prompts
        val a = p.assinador(chave)
        verificaEd25519(a, runBlocking { a.assinar(dados) }!!)
        assertEquals("criar abriu a janela, assinar dentro dela não pede de novo", promptsNaCriacao, aut.prompts)
        relogio.ms += 301_000
        verificaEd25519(a, runBlocking { a.assinar(dados) }!!)
        assertEquals(promptsNaCriacao + 1, aut.prompts)
    }

    // ---- exclusão e estado ---------------------------------------------------------------------------------------------------

    @Test fun excluirApagaOAliasEOCofre() {
        val p = provedor(forcarEnvelope = true)
        val env = gerar(p, TipoDeChave.ED25519)
        val nat = gerar(p, TipoDeChave.ECDSA_P256)
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(ks.containsAlias("keyflow_ssh_env_${env.id}"))
        assertTrue(ks.containsAlias("keyflow_ssh_${nat.id}"))
        p.excluir(env); p.excluir(nat)
        assertFalse(ks.containsAlias("keyflow_ssh_env_${env.id}"))
        assertFalse(ks.containsAlias("keyflow_ssh_${nat.id}"))
        assertTrue("cofre sem resíduos: ${cofre.chaves()}", cofre.chaves().isEmpty())
        assertNull(p.chavePublica(env))
    }

    @Test fun chaveAtivaTemEstadoAtivaEChaveSumidaNaoLanca() {
        val p = provedor()
        val chave = gerar(p, TipoDeChave.ECDSA_P256)
        assertEquals(EstadoChave.ATIVA, p.estado(chave))
        p.excluir(chave)
        assertNull(runBlocking { p.assinador(chave).assinar(dados) })
    }

    @Test fun importadaEntraNoEnvelopeEAssina() {
        val pem = String(InstrumentationRegistry.getInstrumentation().context.assets.open("computador/chaves/ed25519").use { it.readBytes() })
        val p = provedor()
        val r = runBlocking { p.importar(pem.toCharArray(), null, "importada", exigeBiometria = false) }
        val chave = (r as Resultado.Ok).valor
        criadas += p to chave
        assertEquals(Protecao.KEYSTORE_ENVELOPE, chave.protecao)
        verificaEd25519(p.assinador(chave), runBlocking { p.assinador(chave).assinar(dados) }!!)
    }

    // ---- biometria cadastrada mudou (APIs 24–29: sentinela; aqui um fake, o cadastro real fica para a T21) ----------------------

    private class DetectorFalso : DetectorDeCadastro {
        val criadas = mutableListOf<String>()
        val apagadas = mutableListOf<String>()
        @Volatile var invalida = false
        override fun criar(id: String) { criadas += id }
        override fun invalidada(id: String) = invalida
        override fun apagar(id: String) { apagadas += id }
    }

    @Test fun cadastroNovoInvalidaAChaveEAssinarDevolveNullSemExcecao() {
        for ((tipo, envelope) in listOf(TipoDeChave.ECDSA_P256 to false, TipoDeChave.ED25519 to true)) {
            val d = DetectorFalso()
            val aut = AutenticadorFalso()
            val p = provedor(autenticador = aut, aplicarBio = false, forcarEnvelope = envelope, detector = d)
            val chave = gerar(p, tipo, bio = true)
            assertEquals(EstadoChave.ATIVA, p.estado(chave))
            val a = p.assinador(chave)
            assertNotNull("$tipo: antes da troca assina", runBlocking { a.assinar(dados) })
            val promptsAntes = aut.prompts
            d.invalida = true // o usuário cadastrou outra digital
            assertNull("$tipo: invalidada não assina", runBlocking { a.assinar(dados) })
            assertTrue(a.invalidada)
            assertEquals(EstadoChave.INVALIDADA, p.estado(chave))
            assertEquals("não abre prompt para chave invalidada", promptsAntes, aut.prompts)
            // E continua invalidada, sem exceção, nas tentativas seguintes.
            assertNull(runBlocking { a.assinar(dados) })
        }
    }

    @Test fun estadoInvalidadaSobreviveAReinicioEOtaEChaveNaoVoltaAFuncionar() {
        val d = DetectorFalso()
        val p = provedor(aplicarBio = false, detector = d)
        val chave = gerar(p, TipoDeChave.ECDSA_P256, bio = true)
        d.invalida = true
        assertNull(runBlocking { p.assinador(chave).assinar(dados) })
        // "Reinício + OTA": provedor novo, sem memória, e o detector não acusa mais nada.
        val novo = provedor(aplicarBio = false, detector = DetectorFalso())
        assertEquals(EstadoChave.INVALIDADA, novo.estado(chave))
        assertNull("a chave invalidada não volta a assinar", runBlocking { novo.assinador(chave).assinar(dados) })
        assertTrue(novo.assinador(chave).invalidada)
        novo.excluir(chave)
        assertTrue("excluir limpa a marca", cofre.chaves().none { it.startsWith("ssh_inv.") })
    }

    @Test fun cofreQueLancaNaLeituraDaMarcaBloqueiaAChave() {
        val d = DetectorFalso()
        val aut = AutenticadorFalso()
        val p = provedor(autenticador = aut, aplicarBio = false, detector = d)
        val chave = gerar(p, TipoDeChave.ECDSA_P256, bio = true)
        val quebrado = object : CofreDeSegredos by cofre {
            override fun ler(chave: String): String? = throw SecurityException("cofre indisponível")
        }
        val bloqueado = ProvedorDeChavesAndroid(quebrado, aut, RelogioFalso(), aplicarBiometriaNoKeystore = false, detector = d)
        val promptsAntes = aut.prompts
        assertEquals(EstadoChave.INVALIDADA, bloqueado.estado(chave))
        val a = bloqueado.assinador(chave)
        assertTrue(a.invalidada)
        assertNull(runBlocking { a.assinar(dados) })
        assertEquals("sem prompt", promptsAntes, aut.prompts)
    }

    @Test fun cofreQueLancaNaGravacaoDaMarcaBloqueiaSemExcecao() {
        val d = DetectorFalso()
        val aut = AutenticadorFalso()
        val p = provedor(autenticador = aut, aplicarBio = false, detector = d)
        val chave = gerar(p, TipoDeChave.ECDSA_P256, bio = true)
        val quebrado = object : CofreDeSegredos by cofre {
            override fun gravar(chave: String, valor: String) { if (chave.startsWith("ssh_inv.")) throw SecurityException("cofre cheio") else cofre.gravar(chave, valor) }
        }
        val q = ProvedorDeChavesAndroid(quebrado, aut, RelogioFalso(), aplicarBiometriaNoKeystore = false, detector = d)
        d.invalida = true // outra digital cadastrada
        val a = q.assinador(chave)
        // `assinar` descobre a invalidação, a gravação da marca falha, e mesmo assim: null, sem exceção, e a chave segue bloqueada.
        assertNull(runBlocking { a.assinar(dados) })
        assertTrue(a.invalidada)
        assertEquals(EstadoChave.INVALIDADA, q.estado(chave))
        assertNull(runBlocking { a.assinar(dados) })
        // `estado()` também não deixa a exceção escapar.
        val e = ProvedorDeChavesAndroid(quebrado, aut, RelogioFalso(), aplicarBiometriaNoKeystore = false, detector = d)
        assertEquals(EstadoChave.INVALIDADA, e.estado(chave))
    }

    @Test fun excluirApagaASentinela() {
        val d = DetectorFalso()
        val p = provedor(aplicarBio = false, detector = d)
        val chave = gerar(p, TipoDeChave.ECDSA_P256, bio = true)
        p.excluir(chave)
        assertTrue(chave.id in d.apagadas)
    }

    @Test fun detectorRealNaApi30OuMaisNaoFazNadaENuncaInvalida() {
        val real = DetectorDeCadastroKeystore(api = 34)
        real.criar("x1")
        assertFalse(real.invalidada("x1"))
        real.apagar("x1")
    }
}
