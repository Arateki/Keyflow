package com.keyflow.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.data.SettingsRepository
import com.keyflow.orbe.ConfigOrbeTeclado
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OrbeDoTecladoRepositoryTest {
    @Test fun snapshotEObservacaoDaOrbeNaoAbremOCofre() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arquivo = File(context.cacheDir, "orbe-${UUID.randomUUID()}.preferences_pb")
        val job = SupervisorJob()
        val repo = SettingsRepository(context, dataStoreFile = arquivo,
            scope = CoroutineScope(job + Dispatchers.IO),
            securePrefsFactory = { _, _ -> error("A leitura da orbe não deve abrir o cofre") },
            credentialStoreFactory = { _, _ -> error("A leitura da orbe não deve abrir credenciais") })
        try {
            val config = ConfigOrbeTeclado(copiarDaOrbePrincipal = false, skin = "glitch")
            repo.atualizarOrbeTeclado { config }
            assertEquals(config, repo.orbeDoTecladoFlow().first().config)
            assertEquals(config, repo.keyboardSnapshotOnce().orbe.config)
        } finally {
            job.cancelAndJoin()
            arquivo.delete()
        }
    }
}
