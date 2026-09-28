package com.keyflow.ime.keyboard.dictionary

import android.content.Context
import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Corretor por idioma do teclado (`pt`/`en`/`es`), com o dicionário de `assets/dict/<idioma>.tsv`
 * lido uma vez por processo, fora da main thread (~100 mil palavras). Trocar de idioma e voltar não lê
 * de novo; a view do teclado ser recriada também não.
 */
object Dictionaries {

    private val cache = ConcurrentHashMap<String, CompletableFuture<Suggester?>>()

    /** O corretor de [language]; completa quando o dicionário termina de carregar (`null` se não há). */
    fun suggesterFor(context: Context, language: String): CompletableFuture<Suggester?> {
        val app = context.applicationContext
        return cache.computeIfAbsent(language) { lang ->
            CompletableFuture.supplyAsync { load(app, lang) }
        }
    }

    private fun load(context: Context, language: String): Suggester? = runCatching {
        val start = System.currentTimeMillis()
        val words = context.assets.open("dict/$language.tsv").bufferedReader().useLines {
            WordList.parse(it)
        }
        Log.i(TAG, "$language: ${words.size} palavras em ${System.currentTimeMillis() - start} ms")
        Suggester(words)
    }.onFailure { Log.w(TAG, "sem dicionário para $language: ${it.message}") }.getOrNull()

    private const val TAG = "KeyFlow.Dictionary"
}
