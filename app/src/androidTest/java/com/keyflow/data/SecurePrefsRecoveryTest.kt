package com.keyflow.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

/**
 * Q8-bis: o `EncryptedSharedPreferences` quebrava DE VEZ quando o keyset do Tink voltava de um backup
 * sem a chave mestra correspondente — a chave do Keystore não é exportável, então o Auto Backup/D2D do
 * Android restaura um e não o outro. O resultado era `AEADBadTagException` em TODA leitura de settings
 * (crash em loop, só saía com "limpar dados"); visto no Galaxy A32 do Yan em 2026-09-07, ao reinstalar
 * o app por cima de dados restaurados.
 */
@RunWith(AndroidJUnit4::class)
class SecurePrefsRecoveryTest {

    private lateinit var context: Context
    private lateinit var prefsName: String
    private lateinit var keyAlias: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString().take(8)
        // Alias PRÓPRIO do teste: apagar a master key de produção quebraria as settings reais do device.
        prefsName = "test_secure_recovery_$id"
        keyAlias = "test_master_key_$id"
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(prefsName)
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(keyAlias)
        }
    }

    @Test
    fun recreatesThePrefsWhenTheKeysetSurvivesABackupButTheMasterKeyDoesNot() {
        val original = SecurePrefs.create(context, prefsName, keyAlias)
        original.edit().putString(TOKEN, "segredo-antigo").commit()
        assertEquals("segredo-antigo", original.getString(TOKEN, null))

        // Exatamente o que o restore do Android provoca: o keyset (shared_prefs) continua no disco, a
        // chave mestra do Keystore não veio junto.
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(keyAlias)

        // Antes do fix: AEADBadTagException aqui (e em toda leitura seguinte).
        val recovered = SecurePrefs.create(context, prefsName, keyAlias)

        // O valor antigo é irrecuperável (foi cifrado com uma chave que não existe mais) — o que não
        // pode acontecer é o app morrer por causa dele.
        assertNull(recovered.getString(TOKEN, null))
        recovered.edit().putString(TOKEN, "segredo-novo").commit()
        assertEquals("segredo-novo", recovered.getString(TOKEN, null))

        // E a recuperação tem que ser durável: abrir de novo lê o que acabou de ser gravado.
        assertEquals(
            "segredo-novo",
            SecurePrefs.create(context, prefsName, keyAlias).getString(TOKEN, null),
        )
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TOKEN = "openai_token"
    }
}
