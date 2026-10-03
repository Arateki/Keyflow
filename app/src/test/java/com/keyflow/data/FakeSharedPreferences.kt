package com.keyflow.data

import android.content.SharedPreferences

/**
 * `SharedPreferences` em memória para os testes JVM do cofre (o `EncryptedSharedPreferences` precisa de Keystore).
 * [commitSucceeds] = false simula o cofre que não consegue gravar: `commit()` devolve `false` e nada é aplicado,
 * como o `SharedPreferences` real quando a escrita em disco falha.
 */
class FakeSharedPreferences(var commitSucceeds: Boolean = true) : SharedPreferences {

    private val values = linkedMapOf<String, Any?>()

    /** Quantas vezes alguém pediu um editor — o caminho comum da migração não pode nem abrir o cofre. */
    var editorsOpened = 0
        private set

    /** O conteúdo atual, sem passar por [edit]. */
    fun snapshot(): Map<String, Any?> = values.toMap()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor {
        editorsOpened++
        return FakeEditor()
    }

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values)
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            removed += key!!
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            if (!commitSucceeds) return false
            if (clear) values.clear()
            removed.forEach { values.remove(it) }
            values.putAll(pending)
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
