package com.keyflow.computador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidadorHostTest {
    private fun host(
        nome: String = "dev", endereco: String = "dev.example.org", porta: Int = 22, usuario: String = "yan", id: String = "a1b2c3d4",
    ) = Host(id = id, nome = nome, endereco = endereco, porta = porta, usuario = usuario)

    private fun motivos(h: Host, outros: List<Host> = emptyList()) = ValidadorHost.validar(h, outros).map { it.campo to it.motivo }

    @Test fun hostValidoNaoTemErro() = assertEquals(emptyList<ErroHost>(), ValidadorHost.validar(host()))

    @Test fun enderecosValidos() {
        for (e in listOf("dev.example.org", "localhost", "meu-servidor", "10.0.0.1", "255.255.255.255", "[::1]", "[2001:db8::1]", "[fe80::1%eth0]", "[::ffff:192.0.2.1]", "a_b.example.com", "xn--bcher-kva.example"))
            assertNull("deveria aceitar $e", ValidadorHost.validarEndereco(e))
    }

    @Test fun enderecosInvalidosTemMotivo() {
        assertEquals(MotivoHost.ENDERECO_VAZIO, ValidadorHost.validarEndereco(""))
        assertEquals(MotivoHost.ENDERECO_LONGO, ValidadorHost.validarEndereco("a".repeat(254)))
        for (e in listOf("999.1.1.1", "1.2.3", "1.2.3.4.5", "-ruim.com", "ruim-.com", "a..b", "com espaco", "[::1", "[gggg::1]", "[1:2:3:4:5:6:7:8:9]", "[1::2::3]", "[:::1]", "[]", "http://x", "a/b"))
            assertEquals("deveria recusar $e", MotivoHost.ENDERECO_INVALIDO, ValidadorHost.validarEndereco(e))
    }

    @Test fun ipv6SemColchetesPedeColchetes() {
        assertEquals(MotivoHost.IPV6_SEM_COLCHETES, ValidadorHost.validarEndereco("::1"))
        assertEquals(MotivoHost.IPV6_SEM_COLCHETES, ValidadorHost.validarEndereco("2001:db8::1"))
    }

    @Test fun portaForaDaFaixaERecusadaComMotivo() {
        for (p in listOf(0, -1, 65536, 99999)) assertEquals(listOf(CampoHost.PORTA to MotivoHost.PORTA_INVALIDA), motivos(host(porta = p)))
        for (p in listOf(1, 22, 65535)) assertTrue(motivos(host(porta = p)).isEmpty())
    }

    @Test fun nomeVazioLongoERepetido() {
        assertEquals(listOf(CampoHost.NOME to MotivoHost.NOME_VAZIO), motivos(host(nome = "   ")))
        assertEquals(listOf(CampoHost.NOME to MotivoHost.NOME_LONGO), motivos(host(nome = "x".repeat(41))))
        assertTrue(motivos(host(nome = "x".repeat(40))).isEmpty())
        val outro = host(nome = "Dev", id = "11111111")
        assertEquals(listOf(CampoHost.NOME to MotivoHost.NOME_REPETIDO), motivos(host(nome = "dev"), listOf(outro)))
        // Editar o próprio host mantendo o nome não é repetição.
        assertTrue(motivos(host(nome = "Dev", id = "11111111"), listOf(outro)).isEmpty())
    }

    @Test fun usuarioVazioLongoEComEspaco() {
        assertEquals(listOf(CampoHost.USUARIO to MotivoHost.USUARIO_VAZIO), motivos(host(usuario = "")))
        assertEquals(listOf(CampoHost.USUARIO to MotivoHost.USUARIO_LONGO), motivos(host(usuario = "u".repeat(65))))
        assertEquals(listOf(CampoHost.USUARIO to MotivoHost.USUARIO_INVALIDO), motivos(host(usuario = "a b")))
        assertTrue(motivos(host(usuario = "u".repeat(64))).isEmpty())
    }

    @Test fun cinquentaEUmHostsRecusa() {
        val cheios = (0 until 50).map { host(nome = "h$it", id = "%08x".format(it)) }
        assertEquals(listOf(CampoHost.LIMITE to MotivoHost.LISTA_CHEIA), motivos(host(nome = "novo", id = "ffffffff"), cheios))
        assertTrue(motivos(cheios[3], cheios).isEmpty())
    }
    @Test fun autenticacaoExigeReferenciaValidaEFatoSobreOSegredo() {
        assertTrue(ValidadorHost.validar(host().copy(autenticacao = AutenticacaoHost.Chave(""))).any { it.motivo == MotivoHost.CHAVE_NAO_GUARDADA })
        val senha = host().copy(autenticacao = AutenticacaoHost.Senha, lembrarSenha = true)
        assertTrue(ValidadorHost.validar(senha, credencialDisponivel = false).any { it.motivo == MotivoHost.SENHA_NAO_INFORMADA })
        assertTrue(ValidadorHost.validar(senha, credencialDisponivel = true).isEmpty())
    }
}
