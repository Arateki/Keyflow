package com.keyflow.i18n

import com.keyflow.i18n.en.Bancada
import com.keyflow.i18n.en.Comum
import com.keyflow.i18n.en.Computador
import com.keyflow.i18n.en.Conta
import com.keyflow.i18n.en.Docs
import com.keyflow.i18n.en.Fluxos
import com.keyflow.i18n.en.FluxosExecucao
import com.keyflow.i18n.en.Keyflow
import com.keyflow.i18n.en.Modelos
import com.keyflow.i18n.en.OrbeTeclado
import com.keyflow.i18n.en.Privacidade
import com.keyflow.i18n.en.Raciocinio
import com.keyflow.i18n.en.Skins
import com.keyflow.i18n.en.Sobrepor
import com.keyflow.i18n.en.Teclado
import com.keyflow.i18n.en.Tour
import com.keyflow.i18n.en.Transcricao
import com.keyflow.i18n.en.Visao
import com.keyflow.i18n.en.Voz

/**
 * Português → inglês. Cada funcionalidade tem o próprio arquivo em `i18n/en`, com as chaves em ordem
 * alfabética; o que serve a mais de uma fica em [Comum]. Este mapa só junta. A API continua [tr].
 */
internal val TranslationsEn: Map<String, String> = translationMap(
    Bancada,
    Comum,
    Computador,
    Conta,
    Docs,
    Fluxos,
    FluxosExecucao,
    Keyflow,
    Modelos,
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
