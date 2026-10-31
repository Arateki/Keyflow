package com.keyflow.computador.ssh

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MapaDeErrosTest {
    @Test fun excecoesDeRede() {
        assertEquals(CausaSsh.DNS, MapaDeErros.causaDe(UnknownHostException("x")))
        assertEquals(CausaSsh.RECUSADA, MapaDeErros.causaDe(ConnectException("Connection refused")))
        assertEquals(CausaSsh.TEMPO, MapaDeErros.causaDe(SocketTimeoutException()))
        assertEquals(CausaSsh.REDE, MapaDeErros.causaDe(NoRouteToHostException()))
        assertEquals(CausaSsh.REDE, MapaDeErros.causaDe(SocketException("Network is unreachable")))
    }

    @Test fun olhaACadeiaDeCausas() {
        assertEquals(CausaSsh.DNS, MapaDeErros.causaDe(RuntimeException("envolvido", RuntimeException("meio", UnknownHostException()))))
        assertEquals(CausaSsh.PROTOCOLO, MapaDeErros.causaDe(IllegalStateException("qualquer")))
    }

    @Test fun nomesDeClassesDeBibliotecas() {
        class ConnectTimeoutException : Exception()
        class UnresolvedAddressException : Exception()
        assertEquals(CausaSsh.TEMPO, MapaDeErros.causaDe(ConnectTimeoutException()))
        assertEquals(CausaSsh.DNS, MapaDeErros.causaDe(UnresolvedAddressException()))
    }

    @Test fun transporteDoCbsshEClassificadoPeloTextoSemExporloNaCausa() {
        class TransportException(m: String) : Exception(m)
        assertEquals(CausaSsh.RECUSADA, MapaDeErros.causaDe(TransportException("Failed to connect to x:22. Tried 1 addresses. Errors: Connection refused")))
        assertEquals(CausaSsh.DNS, MapaDeErros.causaDe(TransportException("Could not resolve host: x")))
        assertEquals(CausaSsh.TEMPO, MapaDeErros.causaDe(TransportException("Connect timed out")))
        assertEquals(CausaSsh.REDE, MapaDeErros.causaDe(TransportException("Network is unreachable")))
        assertEquals(CausaSsh.PROTOCOLO, MapaDeErros.causaDe(TransportException("Not connected")))
    }

    @Test fun causaCiclicaNaoTravaENaoUsaAMensagem() {
        val a = RuntimeException("segredo-na-mensagem")
        a.initCause(RuntimeException("b", a))
        assertEquals(CausaSsh.PROTOCOLO, MapaDeErros.causaDe(a))
    }

    @Test fun frasesNuncaVazamEntreCausas() {
        assertEquals(null, CausaSsh.SERVIDOR_NAO_CONFIADO.frase)
        assertFalse(CausaSsh.entries.filter { it != CausaSsh.SERVIDOR_NAO_CONFIADO }.any { it.frase.isNullOrBlank() })
    }
}
