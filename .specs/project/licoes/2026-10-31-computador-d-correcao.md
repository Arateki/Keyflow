# 2026-10-31 — Correções da revisão do Computador D (#96)

## CMP-35: a gramática vem do parser, não da representação textual

`ParserVt` acompanha o `parser.c` fixado: C0/DEL não terminam CSI/DCS, CAN/SUB cancelam e ESC dentro de string só é elevado a C1 quando é ST. Prefixos nunca são entregues antes da decisão. Números decimais perdem zeros à esquerda; argumentos fora da faixa e sequências acima de 1 MiB são descartados até o final/cancelamento, sem mudar para passagem livre. O limite retém no máximo 1 MiB por sequência; imagens seguem Off nesta fase, e a integração de imagens deverá respeitar esse orçamento ou fornecer sua própria fronteira segura.

Os controles do teclado continuam sendo teclas: Ctrl+E e Alt+Ctrl+X/Z precisam conservar seus bytes. A direção do fluxo distingue o ENQ do servidor dos controles produzidos pelo teclado; isso não tenta identificar a origem de callbacks nativos. A regressão adicional confere todos os controles com/sem Alt, Alt+Esc e Alt+Unicode; a matriz de `MapaTecladoFisicoTest` confere cada combinação mapeada. O lote final do filtro e do mapa passou em 42/42 testes. Bytes inválidos após ESC também são ignorados na decisão, como no C; o orçamento não é reiniciado ao reconhecer um introduzidor depois deles.

O filtro de respostas reconhece C1 bruto sem confundi-lo com continuação UTF-8. O servidor não pode ligar S8C1T; a saída ainda aplica a lista fechada mesmo se receber respostas C1. Títulos cancelados não são publicados. Remover a sequência inteira evita deixar o emulador preso em uma string cujo ESC foi consumido.

`ParserLibvtermTest` compila o C original com o compilador do host dentro dos unitários do pool (sem NDK/JNI), consulta os callbacks do parser e compara 500 fluxos fragmentados e 300 fluxos de bytes arbitrários: consultas proibidas ausentes, DECCKM igual e texto posterior visível. Testes do filtro: 25/25. A mutação que liberou S8C1T derrubou três testes (incluindo o fuzz); revertida e 25/25 novamente.

Mouse SGR na tela continua para CMP-25/T27; esta correção não o conecta.
