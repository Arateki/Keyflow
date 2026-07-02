package com.keyflow.ime.keyboard

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionListener
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.util.Locale

/**
 * Fininha sobre `TextServicesManager`/`SpellCheckerSession` (SPL-01): pede sugestões para uma palavra e
 * entrega ao callback quando (e se) o corretor do sistema responder. Sem corretor instalado/ativo pro
 * locale, a sessão nunca abre ou nunca chama de volta — degrada pra lista vazia, sem crash (edge case do
 * spec). Não é testável em unit (depende do serviço real do Android); coberto por instrumentado + manual.
 *
 * Usa `getSuggestions` (nível de PALAVRA), não `getSentenceSuggestions`: a API de sentença é pensada pra
 * texto com pontuação/contexto gramatical e alguns serviços de corretor não respondem bem pra uma palavra
 * isolada sem esse contexto — nosso caso é sempre 1 palavra em composição.
 *
 * Diagnosticado com o Yan no device físico (2026-07-01, log temporário removido depois): sessão abre,
 * callback dispara em ~15ms, mas o corretor selecionado no sistema (no caso, o do próprio fabricante do
 * teclado — ex.: HoneyBoard no Samsung) devolve 0 sugestões pra qualquer palavra em português, mesmo
 * óbvias como "nao"→"não". O teclado do fabricante mostra sugestões ao digitar porque usa uma engine
 * PRÓPRIA/interna — não expõe isso via `SpellCheckerSession`, que é a única API pública que qualquer
 * app/teclado pode consultar. `referToSpellCheckerLanguageSettings=false` (força nosso [locale] explícito
 * em vez do idioma configurado nas settings do corretor) não mudou o resultado — é limitação do serviço
 * escolhido no dispositivo, não bug daqui. Trocar o "Verificador ortográfico" do sistema pra outro (ex.:
 * o do Gboard, se instalado) é o caminho pra ter sugestões reais nessa API.
 */
class SystemSpellChecker(context: Context, locale: Locale) {

    private var pendingCallback: ((List<String>) -> Unit)? = null

    private val listener = object : SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
            val suggestions = results
                ?.flatMap { info -> (0 until info.suggestionsCount).map { info.getSuggestionAt(it) } }
                ?.distinct()
                ?: emptyList()
            val callback = pendingCallback
            pendingCallback = null
            callback?.invoke(suggestions)
        }

        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) = Unit
    }

    private val session: SpellCheckerSession? = runCatching {
        val manager = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
        manager?.newSpellCheckerSession(null, locale, listener, false)
    }.getOrNull()

    /** Pede sugestões para [word]; [callback] roda depois (async) ou nunca, se não houver corretor. */
    fun suggest(word: String, callback: (List<String>) -> Unit) {
        val active = session
        if (active == null || word.isBlank()) {
            callback(emptyList())
            return
        }
        pendingCallback = callback
        runCatching {
            active.getSuggestions(TextInfo(word), MAX_SUGGESTIONS)
        }.onFailure {
            pendingCallback = null
            callback(emptyList())
        }
    }

    /** Descarta um callback pendente (a palavra mudou antes do corretor responder). */
    fun cancel() {
        pendingCallback = null
    }

    fun close() {
        runCatching { session?.close() }
    }

    private companion object {
        const val MAX_SUGGESTIONS = 5
    }
}
