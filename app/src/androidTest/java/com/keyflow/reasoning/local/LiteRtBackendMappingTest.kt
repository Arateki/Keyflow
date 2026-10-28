package com.keyflow.reasoning.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.Backend
import com.keyflow.core.ComputeBackend
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O `Backend` do LiteRT-LM de cada [ComputeBackend]. As classes da biblioteca são bytecode de Java 21 e não carregam no JDK 17 dos
 * testes de unidade, por isso a prova mora aqui, no aparelho.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtBackendMappingTest {

    @Test
    fun cpuAndGpuMapToTheLiteRtBackendsOfTheSameName() {
        assertTrue(LiteRtConfigs.backend(ComputeBackend.CPU) is Backend.CPU)
        assertTrue(LiteRtConfigs.backend(ComputeBackend.GPU) is Backend.GPU)
    }

    /** Todo valor de [ComputeBackend] tem backend: um valor novo sem mapeamento quebra aqui, não no aparelho do usuário. */
    @Test
    fun everyComputeBackendHasALiteRtBackend() {
        ComputeBackend.entries.forEach { LiteRtConfigs.backend(it) }
    }
}
