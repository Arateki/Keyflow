package com.keyflow.orbe

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.keyflow.auth.Credential
import com.keyflow.auth.CredentialStore
import com.keyflow.auth.ProviderId
import com.keyflow.core.OrbShortcutAction
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class CodecOrbeTecladoTest {

    @Test
    fun `round-trip preserva skin de pacote, modo ptt e acao flow`() {
        val original = ConfigOrbeTeclado(
            copiarDaOrbePrincipal = false,
            skin = "pack:neon-synth",
            modo = ModoDaOrbe.SEGURAR_PARA_FALAR,
            toque1 = AcaoDaOrbeDoTeclado.Nenhuma,
            toque2 = AcaoDaOrbeDoTeclado.RodarFluxo("abc"),
            toque3 = AcaoDaOrbeDoTeclado.PausarRetomar,
        )

        val json = CodecOrbeTeclado.codificar(original)
        val obj = JSONObject(json)
        assertEquals(1, obj.getInt("v"))
        assertEquals(false, obj.getBoolean("copy"))
        assertEquals("pack:neon-synth", obj.getString("skin"))
        assertEquals("ptt", obj.getString("mode"))
        assertEquals("none", obj.getJSONObject("taps").getString("1"))
        assertEquals("flow:abc", obj.getJSONObject("taps").getString("2"))
        assertEquals("pause_resume", obj.getJSONObject("taps").getString("3"))

        val decodificada = CodecOrbeTeclado.decodificar(json)
        assertEquals(original, decodificada)
    }

    @Test
    fun `round-trip cobre todas as acoes do catalogo`() {
        val acoes = listOf(
            AcaoDaOrbeDoTeclado.GravarEnviar,
            AcaoDaOrbeDoTeclado.Nenhuma,
            AcaoDaOrbeDoTeclado.RetrairExpandir,
            AcaoDaOrbeDoTeclado.TrocarTeclado,
            AcaoDaOrbeDoTeclado.LerTela,
            AcaoDaOrbeDoTeclado.PausarRetomar,
            AcaoDaOrbeDoTeclado.CancelarDesfazer,
            AcaoDaOrbeDoTeclado.RodarFluxo("fluxo-42"),
        )
        for (acao in acoes) {
            val cfg = ConfigOrbeTeclado(toque1 = acao, toque2 = acao, toque3 = acao)
            assertEquals(cfg, CodecOrbeTeclado.decodificar(CodecOrbeTeclado.codificar(cfg)))
        }
    }

    @Test
    fun `json ausente, vazio ou malformado devolve padroes da fabrica`() {
        val padrao = ConfigOrbeTeclado(
            copiarDaOrbePrincipal = true,
            skin = "atelie",
            modo = ModoDaOrbe.ALTERNAR,
            toque1 = AcaoDaOrbeDoTeclado.GravarEnviar,
            toque2 = AcaoDaOrbeDoTeclado.RetrairExpandir,
            toque3 = AcaoDaOrbeDoTeclado.TrocarTeclado,
        )

        assertEquals(padrao, CodecOrbeTeclado.decodificar(null))
        assertEquals(padrao, CodecOrbeTeclado.decodificar(""))
        assertEquals(padrao, CodecOrbeTeclado.decodificar("   "))
        assertEquals(padrao, CodecOrbeTeclado.decodificar("{json-quebrado"))
        assertEquals(padrao, CodecOrbeTeclado.decodificar("[1, 2, 3]"))
    }

    @Test
    fun `campos desconhecidos e valores nulos em JSONObject sao ignorados com seguranca`() {
        val json = """
            {
              "v": 99,
              "copy": false,
              "skin": null,
              "mode": "modo_futuro",
              "campo_extra": { "qualquer": true },
              "taps": {
                "1": null,
                "2": "vision",
                "4": "cancel_undo"
              }
            }
        """.trimIndent()

        val cfg = CodecOrbeTeclado.decodificar(json)
        assertEquals(false, cfg.copiarDaOrbePrincipal)
        assertEquals("atelie", cfg.skin)
        assertEquals(ModoDaOrbe.ALTERNAR, cfg.modo)
        assertEquals(AcaoDaOrbeDoTeclado.GravarEnviar, cfg.toque1)
        assertEquals(AcaoDaOrbeDoTeclado.LerTela, cfg.toque2)
        assertEquals(AcaoDaOrbeDoTeclado.TrocarTeclado, cfg.toque3)
    }

    @Test
    fun `acao desconhecida volta igual no codificar e nao e reescrita para gravar no 1 toque`() {
        val jsonOriginal = """
            {
              "v": 1,
              "copy": false,
              "skin": "aero",
              "mode": "toggle",
              "taps": {
                "1": "acao_futura_v2",
                "2": "flow:resumo-diario",
                "3": "outra_desconhecida"
              }
            }
        """.trimIndent()

        val decodificada = CodecOrbeTeclado.decodificar(jsonOriginal)
        assertEquals(AcaoDaOrbeDoTeclado.Desconhecida("acao_futura_v2"), decodificada.toque1)
        assertEquals(AcaoDaOrbeDoTeclado.RodarFluxo("resumo-diario"), decodificada.toque2)
        assertEquals(AcaoDaOrbeDoTeclado.Desconhecida("outra_desconhecida"), decodificada.toque3)

        val recodificada = JSONObject(CodecOrbeTeclado.codificar(decodificada))
        val taps = recodificada.getJSONObject("taps")
        assertEquals("acao_futura_v2", taps.getString("1"))
        assertEquals("flow:resumo-diario", taps.getString("2"))
        assertEquals("outra_desconhecida", taps.getString("3"))
    }

    @Test
    fun `ligar e desligar copy nao muda taps nem skin guardados`() {
        val custom = ConfigOrbeTeclado(
            copiarDaOrbePrincipal = false,
            skin = "glitch",
            modo = ModoDaOrbe.SEGURAR_PARA_FALAR,
            toque1 = AcaoDaOrbeDoTeclado.LerTela,
            toque2 = AcaoDaOrbeDoTeclado.CancelarDesfazer,
            toque3 = AcaoDaOrbeDoTeclado.Nenhuma,
        )

        val ligado = CodecOrbeTeclado.decodificar(
            CodecOrbeTeclado.codificar(custom.copy(copiarDaOrbePrincipal = true)),
        )
        assertTrue(ligado.copiarDaOrbePrincipal)
        assertEquals("glitch", ligado.skin)
        assertEquals(ModoDaOrbe.SEGURAR_PARA_FALAR, ligado.modo)
        assertEquals(AcaoDaOrbeDoTeclado.LerTela, ligado.toque1)
        assertEquals(AcaoDaOrbeDoTeclado.CancelarDesfazer, ligado.toque2)
        assertEquals(AcaoDaOrbeDoTeclado.Nenhuma, ligado.toque3)

        val desligado = CodecOrbeTeclado.decodificar(
            CodecOrbeTeclado.codificar(ligado.copy(copiarDaOrbePrincipal = false)),
        )
        assertEquals(custom, desligado)
    }

    @Test
    fun `json completo nao contem key, token, endpoint nem texto de ditado`() {
        val cfg = ConfigOrbeTeclado(
            copiarDaOrbePrincipal = false,
            skin = "pack:meu-pacote",
            modo = ModoDaOrbe.SEGURAR_PARA_FALAR,
            toque1 = AcaoDaOrbeDoTeclado.CancelarDesfazer,
            toque2 = AcaoDaOrbeDoTeclado.RodarFluxo("id-limpo"),
            toque3 = AcaoDaOrbeDoTeclado.LerTela,
        )
        val json = CodecOrbeTeclado.codificar(cfg).lowercase()
        assertFalse(json.contains("key"))
        assertFalse(json.contains("token"))
        assertFalse(json.contains("endpoint"))
        assertFalse(json.contains("secret"))
        assertFalse(json.contains("transcript"))
        assertFalse(json.contains("ditado"))
    }

    @Test
    fun `atualizarOrbeTeclado e leituras leves nao abrem cofres e saveSettings nao altera a chave`() = runTest {
        val tempDir = Files.createTempDirectory("orbe_datastore_test").toFile()
        val dsScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val dsFile = File(tempDir, "test_orbe.preferences_pb")
            val context = FakeContext(tempDir)

            // Instância 1: cofres proibidos — prova que leitura e escrita da orbe não tocam em cofres cifrados (KBO-29).
            val repoSemCofre = SettingsRepository(
                context = context,
                dataStoreFile = dsFile,
                scope = dsScope,
                securePrefsFactory = { _, _ -> error("não deve abrir SecurePrefs") },
                credentialStoreFactory = { _, _ -> error("não deve abrir CredentialStore") },
            )

            assertEquals(ConfigOrbeTeclado(), repoSemCofre.configOrbeTecladoOnce())

            val personalizada = ConfigOrbeTeclado(
                copiarDaOrbePrincipal = false,
                skin = "ouvi",
                modo = ModoDaOrbe.SEGURAR_PARA_FALAR,
                toque1 = AcaoDaOrbeDoTeclado.Nenhuma,
                toque2 = AcaoDaOrbeDoTeclado.PausarRetomar,
                toque3 = AcaoDaOrbeDoTeclado.RodarFluxo("f1"),
            )
            val gravada = repoSemCofre.atualizarOrbeTeclado { personalizada }
            assertEquals(personalizada, gravada)
            assertEquals(personalizada, repoSemCofre.configOrbeTecladoOnce())
            assertEquals(personalizada, repoSemCofre.configOrbeTecladoFlow().first())

            // Instância 2 no mesmo DataStore: saveSettings regrava as Settings inteiras e não altera keyboard_orb_config_json.
            val repoComCofreFalso = SettingsRepository(
                context = context,
                dataStoreFile = dsFile,
                scope = dsScope,
                securePrefsFactory = { _, _ -> FakeSharedPreferences() },
                credentialStoreFactory = { _, _ -> FakeCredentialStore() },
            )
            repoComCofreFalso.saveSettings(
                Settings(
                    orbSkin = "rose_gold",
                    shortcut2Tap = OrbShortcutAction.VISION_MODE,
                ),
            )

            assertEquals(personalizada, repoSemCofre.configOrbeTecladoOnce())
            assertEquals(personalizada, repoComCofreFalso.configOrbeTecladoFlow().first())
        } finally {
            dsScope.cancel()
            tempDir.deleteRecursively()
        }
    }

    private class FakeContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = baseDir
    }

    private class FakeCredentialStore : CredentialStore {
        override fun get(provider: ProviderId): Credential? = null
        override fun put(provider: ProviderId, credential: Credential) = Unit
        override fun clear(provider: ProviderId) = Unit
        override fun changes(provider: ProviderId): Flow<Credential?> = emptyFlow()
        override fun meta(provider: ProviderId, key: String): String? = null
        override fun putMeta(provider: ProviderId, key: String, value: String) = Unit
    }

    private class FakeSharedPreferences : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor = this
            override fun clear(): SharedPreferences.Editor = this
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }
}
