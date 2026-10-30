package com.keyflow.fluxos

import org.junit.Assert.*
import org.junit.Test

class ReordenarEtapasTest {
    private val etapas = listOf<Etapa>(Etapa.Ditar(), Etapa.Perguntar(), Etapa.Falar(), Etapa.Inserir)

    @Test fun moveParaBaixoEParaCima() {
        assertEquals(listOf(etapas[1], etapas[0], etapas[2], etapas[3]), reordenar(etapas, 0, 1))
        assertEquals(listOf(etapas[0], etapas[1], etapas[3], etapas[2]), reordenar(etapas, 3, 2))
        assertEquals(listOf(etapas[1], etapas[2], etapas[3], etapas[0]), reordenar(etapas, 0, 3))
    }

    @Test fun foraDosLimitesOuIgualNaoMuda() {
        assertSame(etapas, reordenar(etapas, 1, 1))
        assertSame(etapas, reordenar(etapas, -1, 2))
        assertSame(etapas, reordenar(etapas, 0, 4))
        assertSame(etapas, reordenar(emptyList<Etapa>(), 0, 0).let { etapas })
    }

    @Test fun nuncaPerdeNemDuplica() {
        for (de in etapas.indices) for (para in etapas.indices) {
            val r = reordenar(etapas, de, para)
            assertEquals(etapas.size, r.size)
            assertEquals(etapas.toSet(), r.toSet())
            assertEquals(etapas[de], r[para])
        }
    }

    @Test fun textoESeletorRespeitamLimites() {
        val longo = "x".repeat(LIMITES.TEXTO_DA_ETAPA + 30)
        assertEquals(LIMITES.TEXTO_DA_ETAPA, (Etapa.Visao().comTexto(longo) as Etapa.Visao).instrucao!!.length)
        assertNull((Etapa.Perguntar("a").comTexto("   ") as Etapa.Perguntar).sistema)
        assertSame(Etapa.Inserir, Etapa.Inserir.comTexto("a"))
        assertNull(etapaNova(TipoEtapa.DESCONHECIDA))
        assertEquals(Etapa.Falar(ModeloRef("SYSTEM")), Etapa.Falar().comModelo(ModeloRef("SYSTEM")))
        assertSame(Etapa.Capturar, Etapa.Capturar.comModelo(ModeloRef("X")))
    }
}
