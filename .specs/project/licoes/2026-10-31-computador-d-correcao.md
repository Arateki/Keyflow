# 2026-10-31 — Correções da revisão do Computador D (#96)

## CMP-35: a gramática vem do parser, não da representação textual

`ParserVt` acompanha o `parser.c` fixado: C0/DEL não terminam CSI/DCS, CAN/SUB cancelam e ESC dentro de string só é elevado a C1 quando é ST. Prefixos nunca são entregues antes da decisão. Números decimais perdem zeros à esquerda; argumentos fora da faixa e sequências acima de 1 MiB são descartados até o final/cancelamento, sem mudar para passagem livre. O limite retém no máximo 1 MiB por sequência; imagens seguem Off nesta fase, e a integração de imagens deverá respeitar esse orçamento ou fornecer sua própria fronteira segura.

Os controles do teclado continuam sendo teclas: Ctrl+E e Alt+Ctrl+X/Z precisam conservar seus bytes. A direção do fluxo distingue o ENQ do servidor dos controles produzidos pelo teclado; isso não tenta identificar a origem de callbacks nativos. A regressão adicional confere todos os controles com/sem Alt, Alt+Esc e Alt+Unicode; a matriz de `MapaTecladoFisicoTest` confere cada combinação mapeada. O lote final do filtro e do mapa passou em 42/42 testes. Bytes inválidos após ESC também são ignorados na decisão, como no C; o orçamento não é reiniciado ao reconhecer um introduzidor depois deles.

O filtro de respostas reconhece C1 bruto sem confundi-lo com continuação UTF-8. O servidor não pode ligar S8C1T; a saída ainda aplica a lista fechada mesmo se receber respostas C1. Títulos cancelados não são publicados. Remover a sequência inteira evita deixar o emulador preso em uma string cujo ESC foi consumido.

`ParserLibvtermTest` compila o C original com o compilador do host dentro dos unitários do pool (sem NDK/JNI), consulta os callbacks do parser e compara 500 fluxos fragmentados e 300 fluxos de bytes arbitrários: consultas proibidas ausentes, DECCKM igual e texto posterior visível. Testes do filtro: 25/25. A mutação que liberou S8C1T derrubou três testes (incluindo o fuzz); revertida e 25/25 novamente.

Mouse SGR na tela continua para CMP-25/T27; esta correção não o conecta.

## CMP-26/27: ordem depende de uma fila, não de um atraso

O texto do Compose passa por `QueuedTerminal` e pela `TerminalDispatcher`; as teclas do app agora entram na mesma FIFO com `dispatchKeyboardInput`. O callback continua único e aplica o filtro antes do consumidor SSH. O atraso de 40 ms saiu. Três testes JVM retêm o worker e conferem Esc+i, Backspace+letra e texto+Enter sem relógio: 3/3; mutação emitindo fora da fila: 3/3 vermelhos, revertida, 3/3 verdes.

Os nove instrumentados de entrada foram preparados (inclusive o componente correto do IME), mas não executados. Antes da instalação, a verificação sob Gradle → emulador encontrou `Application Not Responding: com.android.systemui`, com lastanr `executing service ...SystemUIAuxiliaryDumpService`. Conforme a tarefa, os instrumentados foram suspensos; sem fechar diálogo, esconder erro, reiniciar ou mudar o AVD. A compilação dos APKs passou; após as correções restantes, a compilação final também confere os testes novos.

## CMP-09: preferência de lembrar não é existência de credencial

`RegraAgente` recebe um fato de disponibilidade e recusa por padrão; `Chave("")` é inválida. O codec valida estrutura e mantém a intenção nos metadados; a gravação consulta o cofre/registro. `DadosDaEdicaoDeHost` permite testar a persistência usada por `AcoesDeHostReais`: segredo confirmado antes de publicar a marca, senha anterior preservada na edição e restaurada se a configuração falhar. Falha silenciosa ou exceção do cofre não publica host/permissão. Não se grava `lembrarSenha=true` sem senha disponível.

Depois da validação, a fase segredo → confirmação do DataStore → limpeza é protegida contra cancelamento do modal. Um teste reproduziu o ator que confirma a configuração antes de entregar uma resposta cancelada: desfazer apenas a senha nessa janela deixava permissão sem credencial. O teste ficou vermelho, a fase foi protegida com `NonCancellable` e os nove testes do gravador passaram.

O modal consulta só a existência da senha (IO), acompanha a presença do rascunho para habilitar a marca e desabilita Chave quando não há chaves. Estado de tela após IO volta a Main. Os testes de persistência/regra/validação/codec e traduções passaram; a mutação que ignorou a disponibilidade da senha derrubou `lembrarNaoEProvaDeSegredoGuardado`, foi revertida e a rodada final passou. Os instrumentados novos compilaram; a execução continua suspensa pelo ANR observado antes da instalação.

## CMP-30: avisos de todos os conjuntos incorporados

A decisão do Yan mantém JetBrains Mono Nerd Font Mono 3.5.1. A OFL da JetBrains Mono 2.304 foi complementada com o LICENSE completo do Nerd Fonts (Ryan L McIntyre, 2014), os avisos MIT de Devicons, Font Awesome Extension, Octicons, Seti, Powerline/Extra e IEC, os textos OFL de Pomicons/Weather/Font Awesome, CC BY 4.0 de Codicons/ícones Font Awesome e Apache-2.0 de Material Design. O aviso do extraglyphs/Hack conserva também a origem Bitstream. Espaços finais/fins de linha foram normalizados sem alterar as palavras das licenças.

O inventário de `src/glyphs/README.md` na tag 3.5.1 traz Pomicons como OFL 1.1 com RFN. Font Logos 1.3.0 possui Unlicense no upstream; a tabela do Nerd Fonts ainda o chama de “unlicensed”. O texto Unlicense e o copyright 2014–2026 de Lukas W presente na fonte foram preservados. README e arquivo de avisos apontam as fontes exatas; fontes sem data publicada não receberam datas inventadas.

O asset permaneceu inalterado, SHA-256 `f2a5ea6cfab397445ffab00c0370927b66d61e560a05db5db271b42006381c1a`. A conferência verificou 16 atribuições (base, Nerd Fonts e 14 conjuntos) e os textos integrais; nenhum teste foi acrescentado para comparar texto de licença com ele mesmo.
