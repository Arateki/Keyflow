package com.keyflow.computador

import org.junit.Assert.assertEquals
import org.junit.Test

class RegraAgenteTest {
    private fun h(a: AutenticacaoHost, lembrar: Boolean = false) = Host("a1b2c3d4", "n", "x.org", 22, "u", a, lembrarSenha = lembrar)

    @Test fun perguntarSempreNaoPode() =
        assertEquals(PodeMarcar.Nao(MotivoAgente.PERGUNTA_SEMPRE), RegraAgente.podeMarcar(h(AutenticacaoHost.Perguntar)))

    @Test fun senhaSoComLembrar() {
        assertEquals(PodeMarcar.Nao(MotivoAgente.SENHA_NAO_LEMBRADA), RegraAgente.podeMarcar(h(AutenticacaoHost.Senha)))
        assertEquals(PodeMarcar.Sim, RegraAgente.podeMarcar(h(AutenticacaoHost.Senha, lembrar = true), credencialDisponivel = true))
    }

    @Test fun chaveGuardadaPodeMasComPassphraseNaHoraNao() {
        assertEquals(PodeMarcar.Sim, RegraAgente.podeMarcar(h(AutenticacaoHost.Chave("k1")), credencialDisponivel = true))
        assertEquals(PodeMarcar.Nao(MotivoAgente.CHAVE_COM_PASSPHRASE), RegraAgente.podeMarcar(h(AutenticacaoHost.Chave("k1")), credencialDisponivel = true) { true })
    }
    @Test fun lembrarNaoEProvaDeSegredoGuardado() {
        assertEquals(PodeMarcar.Nao(MotivoAgente.SENHA_INDISPONIVEL), RegraAgente.podeMarcar(h(AutenticacaoHost.Senha, lembrar = true)))
    }

    @Test fun chaveVaziaOuIndisponivelNaoPermiteAgente() {
        for (id in listOf("", " ")) assertEquals(PodeMarcar.Nao(MotivoAgente.CHAVE_NAO_GUARDADA), RegraAgente.podeMarcar(h(AutenticacaoHost.Chave(id)), credencialDisponivel = true))
        assertEquals(PodeMarcar.Nao(MotivoAgente.CHAVE_NAO_GUARDADA), RegraAgente.podeMarcar(h(AutenticacaoHost.Chave("k1"))))
    }
}
