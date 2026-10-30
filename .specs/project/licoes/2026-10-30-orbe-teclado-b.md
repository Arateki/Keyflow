# 2026-10-30 — Plano de insets e orbe na janela do teclado (equipe/orbe-teclado-b)

## KBO-T4

`PlanoDeInsets` descreve conteúdo, visibilidade e região tocável sem Android. A união do
conteúdo com o disco preserva cantos inferiores que pertencem ao teclado e exclui os cantos
superiores fora do disco. O recorte à janela é explícito no contrato puro.

A reserva ganhou `sobressairPx = 0` para preservar os chamadores atuais. A soma da faixa
acontece antes do teto existente de 75% da altura útil, em `reservaComFaixaPx` (JVM puro).
O IME deve passar os 36 dp tanto à reserva do overscan quanto à reserva da barra na T5.

Teste vermelho inicial: 5 falhas em 6 testes, por faixa ignorada, região sempre de conteúdo
e reserva sem o termo adicional (`/tmp/orbe-b-t4-vermelho.log`).

Gate completo: 2.705/2.705 testes, sem ignorados, no worker escolhido pelo pool (AWS).
Mutação manual: substituir o disco por `retanguloDelimitador` na região fez falhar o
contraexemplo de cantos (`PlanoDeInsetsTest`, 1 falha em 6); o fonte correto foi restaurado.
