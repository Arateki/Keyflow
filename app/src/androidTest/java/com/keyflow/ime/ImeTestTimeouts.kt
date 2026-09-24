package com.keyflow.ime

/**
 * Prazo para a `KeyFlowKeyboardView` anexar depois do `ime enable` + `ime set` e do toque no campo.
 *
 * A primeira vez que o sistema sobe o IME num aparelho recém-ligado inclui criar o processo do serviço e
 * inflar a view — no emulador do CI isso passou dos 5 s que as outras esperas usam, e só o PRIMEIRO
 * teste de teclado da rodada falhava (o seguinte, com o IME já quente, passava). A espera volta assim
 * que a view anexa, então a folga não custa nada quando o IME já está de pé.
 */
internal const val IME_COLD_ATTACH_TIMEOUT_MS = 30_000L
