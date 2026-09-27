package com.keyflow.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * `SharedPreferences` cifradas dos segredos (tokens das APIs), com recuperação quando o par
 * keyset↔chave-mestra se desencontra.
 *
 * Por que a recuperação existe: o keyset do Tink mora num `shared_prefs` comum (vai no Auto Backup /
 * transferência D2D do Android), mas a chave mestra mora no Keystore e **não é exportável** — então uma
 * restauração de backup, uma troca de aparelho ou uma reinstalação por cima de dados restaurados devolve
 * um sem o outro. A partir daí toda leitura estourava `AEADBadTagException`, e como as settings são lidas
 * na inicialização, o app entrava em crash em loop que só saía com "limpar dados" (achado no Galaxy A32
 * do Yan, 2026-09-07 — ver `.specs/quick/008-modal-grade-picker/TASK.md`).
 *
 * O que é perdido no descarte: só os segredos cifrados (o usuário reinsere o token). Eles são
 * irrecuperáveis por definição — a chave que os decifra não existe mais. Tudo que não é segredo mora no
 * DataStore em claro e não passa por aqui.
 *
 * `AndroidManifest` também exclui este arquivo do backup ([BACKUP_RULES]), pra evitar a situação na
 * origem; a recuperação aqui é a rede de segurança pra quem JÁ está com o estado quebrado (e pros
 * caminhos que as regras de backup não cobrem).
 */
internal object SecurePrefs {

    /**
     * Abre as prefs cifradas [name]; se o keyset e a chave [keyAlias] não combinarem mais, descarta os
     * dois e devolve prefs vazias e funcionais em vez de propagar a exceção de cripto.
     */
    fun create(
        context: Context,
        name: String,
        keyAlias: String = MasterKey.DEFAULT_MASTER_KEY_ALIAS,
    ): SharedPreferences {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return context.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
        return try {
            // `.all` decifra TODO o conteúdo agora, ainda dentro do try: sem isso, um keyset válido com
            // um valor individual ilegível passaria pela criação e só estouraria depois, num `getString`
            // solto no meio do app — que é exatamente o crash que estamos eliminando.
            open(context, name, keyAlias).also { it.all }
        } catch (e: Exception) {
            // Amplo de propósito: o Tink embrulha a causa real (AEADBadTagException, KeyStoreException,
            // InvalidProtocolBufferException…) em tipos que variam por versão de OS/biblioteca. Qualquer
            // falha aqui tem a mesma saída sã — recomeçar do zero vale mais que derrubar o app.
            Log.w(TAG, "Prefs cifradas ilegíveis ($name); recriando do zero", e)
            reset(context, name, keyAlias)
            open(context, name, keyAlias)
        }
    }

    private fun open(context: Context, name: String, keyAlias: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context, keyAlias)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Apaga o par inteiro — o arquivo de prefs (que guarda o keyset do Tink junto dos valores) e a chave
     * mestra. Apagar só um dos dois deixaria o mesmo desencontro que causou o problema.
     */
    private fun reset(context: Context, name: String, keyAlias: String) {
        runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
        runCatching { context.deleteSharedPreferences(name) }
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(keyAlias)
        }
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TAG = "SecurePrefs"

    /** Ver `res/xml/backup_rules.xml` e `res/xml/data_extraction_rules.xml`. */
    private const val BACKUP_RULES = "backup_rules.xml"
}
