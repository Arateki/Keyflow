package com.keyflow.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Idioma atual da interface, um só para o processo inteiro: app, orbe, notificação e teclado leem daqui.
 * É estado observável do Compose — trocar o idioma recompõe na hora o que já está na tela, sem recriar
 * a Activity. Quem escreve é o [com.keyflow.App], a partir das configurações.
 */
object AppLocale {
    var language: AppLanguage by mutableStateOf(AppLanguage.PT)

    /**
     * Idioma fixado por cima das configurações — só a galeria de verificação usa (prints determinísticos,
     * em português por padrão, mesmo num emulador em inglês). `null` = vale [language].
     */
    var pinned: AppLanguage? by mutableStateOf(null)

    /** O idioma em que a interface fala agora. */
    val current: AppLanguage get() = pinned ?: language
}

/**
 * Traduz um texto da interface. O argumento É o texto em português (a língua de origem, que continua
 * legível no código); nos outros idiomas ele vira a chave da tabela ([TranslationsEn], [TranslationsEs]).
 * Sem tradução, sai o português — e o `TranslationsTest` falha, apontando o que faltou.
 *
 * Em Compose, ler [AppLocale.language] aqui dentro faz a tela recompor ao trocar de idioma.
 */
fun tr(pt: String): String = when (AppLocale.current) {
    AppLanguage.PT -> pt
    AppLanguage.EN -> TranslationsEn[pt] ?: pt
    AppLanguage.ES -> TranslationsEs[pt] ?: pt
}

/**
 * Marca um texto de interface guardado para depois (rótulo de `enum`, catálogo): devolve o português como
 * está, e quem mostra chama [tr] na hora. Sem a marca, o `TranslationsTest` não o acharia no código.
 */
fun trKey(pt: String): String = pt

/** [tr] com valores no texto: `%s`/`%d` no português, na mesma ordem nas traduções. */
fun tr(pt: String, vararg args: Any?): String = tr(pt).format(*args)
