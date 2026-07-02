# Shortcut Bars — Context (decisões da discussão, 2026-07-02)

Gray areas resolvidas com o Yan no brainstorm desta feature.

## Escopo — teclado primeiro, orbe depois
A ideia original incluía associar grades a atalhos da **orbe** (overlay). Decisão: **fora desta spec** —
a orbe ganhará um "modo de atalhos" no futuro, onde a associação será feita lá dentro. Esta feature cobre
apenas o teclado: barras personalizáveis + grades + alavanca + emissão física + config UI.

Limitação de plataforma registrada para a feature futura da orbe: overlay **não injeta teclas** — só o IME
ativo tem `InputConnection`. Atalhos da orbe exigirão o KeyFlow como teclado ativo no campo em foco.

## Barras por modo — (b) com espelhamento padrão
Config **independente por modo** (colapsado/expandido), com toggle **"espelhar nos dois modos"** ligado
por padrão (uma config só até o usuário querer divergir). Barras extras empilham acima da principal e
aumentam a altura do IME nos dois modos.

## Âncora da grade — (a) direção do primeiro arrasto
"Escolher um dos 4 lados" = a **direção do primeiro arrasto** ancora a grade (deslizou para cima → grade
se estende acima do dedo). Sem configuração de lado por binding. Clamp nas bordas da tela.

## Grade invisível — disclosure progressivo
A grade é invisível (só lente + preview acima do dedo + háptica ao trocar de célula). Para aprendizado:
**segurar o dedo parado ~400 ms revela a grade inteira** semi-transparente; voltar a arrastar oculta.
Preview fica **acima** do ponto de toque (dedo não cobre). Cancelar = arrastar de volta ao gatilho ou para
fora (padrão cancelar da Fase 2).

## Sequência vs chord — modo do binding, não da grade
O prefixo e o modo moram no **binding** (botão gatilho), não na grade — a mesma grade é reutilizável:
- **só grade**: emite apenas a tecla da célula;
- **sequência**: prefixo como chord completo, depois a tecla (caso tmux `Ctrl+B`, depois `c`);
- **chord**: modificadores do prefixo fundidos na tecla (caso `Ctrl+x` como evento único).

## Tecla avulsa — sim
Catálogo inclui botão de **atalho único direto** (Esc, Tab, Ctrl+C) configurado no mesmo picker. É também
o caminho acessível (TalkBack) para atalhos, já que grade e alavanca são gestos contínuos.

## Rendering — opção B: `BarView` em Canvas
Barras desenhadas por uma **view Canvas única**, irmã da `KeyboardGridView` (hit-test por célula,
press-drag-release, popups, goldens) — não estender o `LinearLayout` atual (touch espalhado em listeners)
nem Compose no IME (IME é Views/Canvas; Compose fica no app de config). Barra de fábrica =
mic/config/colapsar renderizada pela `BarView` com paridade visual (rollback via flag debug, padrão
keyboard-canvas ROL-02).

## Alavanca direcional (ideia do Yan)
Botão pequeno estilo manche de fliperama: arrasto além do limiar dispara a seta; **manter puxado repete
com aceleração progressiva** e tique tátil por disparo; trocar direção sem soltar; flick = uma seta.
Reusa/generaliza o `RepeatSchedule` do backspace. 4 direções (sem diagonais).

## Rolagem lateral × botões de arrasto
Barra lotada rola lateralmente. Regra: botões **arrastáveis** (alavanca, gatilho de grade) capturam o
gesto (barra não rola); rolagem só inicia em botões de tap ou espaço vazio. Requisito explícito (BAR-05)
por ser berço clássico de bugs de touch.

## Picker de teclado físico (ideia do Yan)
Escolha de tecla sempre pela **simulação de um teclado físico ANSI** desenhado + toggles
Ctrl/Alt/Shift/Meta — um componente só para célula de grade, prefixo e tecla avulsa.

## Emissão — `sendKeyEvent`, sem fallback
Hoje quase tudo é `commitText`; só Enter/Esc usam `sendKeyEvent` ad-hoc. Novo `PhysicalKeyEmitter`
centraliza: modificadores DOWN → tecla DOWN → tecla UP → modificadores UP com `metaState` coerente.
Terminais (Termux/Termius/JuiceSSH) tratam como teclado físico; apps comuns podem ignorar — **sem
fallback**, limitação documentada.
