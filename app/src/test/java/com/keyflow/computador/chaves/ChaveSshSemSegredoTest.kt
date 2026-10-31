package com.keyflow.computador.chaves

import com.keyflow.computador.ImpressaoDigital
import com.keyflow.computador.TesteSsh
import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CMP-16: nada de material privado em `ChaveSsh` (campos, `toString`, `copy`), no estado nem nos logs. */
class ChaveSshSemSegredoTest {
    private val sentinela = "SENTINELA-privada-5c3a91"

    @Test fun chaveSshNaoTemCampoDeMaterialSecreto() {
        val nomes = ChaveSsh::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name.lowercase() }
        assertTrue("campos: $nomes", nomes.none { n -> listOf("priv", "seed", "semente", "secret", "segredo", "pem", "passphrase", "senha", "material").any { n.contains(it) } })
        assertTrue(ChaveSsh::class.java.declaredFields.none { it.type == ByteArray::class.java })
    }

    @Test fun toStringECopyNaoMostramNomeNemImpressao() {
        val c = ChaveSsh("k0000001", "nome $sentinela", TipoDeChave.ED25519, ImpressaoDigital.de(sentinela.toByteArray()), Protecao.KEYSTORE_ENVELOPE, true, 1L)
        assertFalse(c.toString().contains(sentinela))
        assertFalse(c.toString().contains("SHA256"))
        assertFalse(c.copy(exigeBiometria = false).toString().contains("SHA256"))
        assertTrue(c.copy(nome = "outro") != c)
    }

    @Test fun materialPrivadoMascaraEZera() {
        val m = MaterialPrivado(FormatoDoMaterial.OPENSSH_SEM_SENHA, sentinela.toByteArray())
        assertFalse(m.toString().contains("SENTINELA"))
        assertFalse(m.hashCode().toString().isEmpty())
        m.zerar()
        assertTrue(m.bytes.all { it == 0.toByte() })
    }

    @Test fun importadaEGeradaNaoVazamEmToString() {
        val chave = (ImportadorDeChave().importar(TesteSsh.chave("ed25519").toCharArray(), null) as Resultado.Ok).valor
        assertFalse(chave.toString().contains("BEGIN"))
        assertFalse(ChavesSoftware.gerarEd25519().toString().contains("="))
    }
}
