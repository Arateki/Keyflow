package com.keyflow.i18n

import com.keyflow.i18n.es.Bancada
import com.keyflow.i18n.es.Comum
import com.keyflow.i18n.es.Computador
import com.keyflow.i18n.es.Conta
import com.keyflow.i18n.es.Docs
import com.keyflow.i18n.es.Fluxos
import com.keyflow.i18n.es.FluxosExecucao
import com.keyflow.i18n.es.Keyflow
import com.keyflow.i18n.es.Modelos
import com.keyflow.i18n.es.Notificacao
import com.keyflow.i18n.es.OrbeTeclado
import com.keyflow.i18n.es.Privacidade
import com.keyflow.i18n.es.Raciocinio
import com.keyflow.i18n.es.Skins
import com.keyflow.i18n.es.Sobrepor
import com.keyflow.i18n.es.Teclado
import com.keyflow.i18n.es.Tour
import com.keyflow.i18n.es.Transcricao
import com.keyflow.i18n.es.Visao
import com.keyflow.i18n.es.Voz

/**
 * Português → espanhol. O par de cada arquivo em `i18n/en` fica em `i18n/es`, com as mesmas chaves
 * na mesma ordem. Este mapa só junta.
 */
internal val TranslationsEs: Map<String, String> = translationMap(
    Bancada,
    Comum,
    Computador,
    Conta,
    Docs,
    Fluxos,
    FluxosExecucao,
    Keyflow,
    Modelos,
    Notificacao,
    OrbeTeclado,
    Privacidade,
    Raciocinio,
    Skins,
    Sobrepor,
    Teclado,
    Tour,
    Transcricao,
    Visao,
    Voz,
)
