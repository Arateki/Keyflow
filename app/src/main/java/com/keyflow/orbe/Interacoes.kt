package com.keyflow.orbe

/**
 * Onde há uma orbe (D9 da feature Fluxos): a flutuante, a do teclado e a da notificação. Um gesto da orbe é sempre "em algum
 * lugar" — `Orbe(FLUTUANTE, 2)` e `Orbe(NOTIFICACAO, 2)` são gatilhos diferentes. Compartilhado com a feature `notification`,
 * que acrescenta aqui o que for dela (`Interacao`, `SuporteDeInteracoes`); quem chegar primeiro cria o arquivo.
 */
enum class LugarDaOrbe { FLUTUANTE, TECLADO, NOTIFICACAO }
