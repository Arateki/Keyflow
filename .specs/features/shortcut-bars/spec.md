# Shortcut Bars (barras personalizáveis + atalhos físicos) — Specification

> **Trabalho 3.** Transformar a barra do teclado KeyFlow num sistema de **barras personalizáveis**
> (rendering Canvas, irmã da `KeyboardGridView`) cujos botões incluem **gatilhos de grades de atalhos**
> (teclados de atalhos efêmeros press-drag-release), **alavanca direcional** e **teclas avulsas** — tudo
> emitindo **eventos de teclado físico** (`sendKeyEvent` + metaState) para uso em SSH/tmux/neovim.
> Config em Compose na aba de teclados. A orbe ganhará um "modo atalhos" no futuro — **fora desta spec**.

## Problem Statement

Sessões de terminal no Android (SSH via Termius/JuiceSSH, Termux) dependem de chords de teclado físico —
Ctrl+B do tmux, Ctrl+W do neovim, Esc, Tab, setas — que teclados virtuais comuns não oferecem ou oferecem
mal. O KeyFlow hoje emite quase tudo via `commitText` (só Enter/Esc usam `sendKeyEvent`) e a barra é fixa
no código (mic/config/colapsar). Queremos que o usuário monte suas próprias barras com botões que simulam
um teclado físico: atalhos únicos, um direcional analógico e grades inteiras de atalhos acessadas num
gesto só — visíveis apenas onde o dedo está, como um quasimode.

## Goals

- [ ] Camada de emissão de **eventos de teclado físico** (`PhysicalKeyEmitter`): chords com modificadores (Ctrl/Alt/Shift/Meta) e sequências prefixo→tecla, corretos para apps de terminal.
- [ ] **Barras personalizáveis** em Canvas substituindo a barra atual, com paridade visual/comportamental de fábrica (mic/config/colapsar) e catálogo de botões extensível.
- [ ] **Grades de atalhos efêmeras**: pressionar o gatilho, arrastar (a direção ancora a grade), lente + preview sob o dedo, soltar emite, com modo aprendizado.
- [ ] **Alavanca direcional** com repetição acelerada e feedback tátil.
- [ ] **Config UI em Compose** na aba de teclados: editor WYSIWYG de barras, editor de grades e picker de teclado físico compartilhado.
- [ ] Zero regressão no mic/candidates/colapsar (sincronia overlay/notificação intacta).

## Out of Scope

| Fora | Razão |
| ---- | ----- |
| Modo atalhos na **orbe** (associar grade a atalho de overlay) | Futuro próximo; o modelo de dados já serve, a UI/gesto da orbe é outra feature (Yan, 2026-07-02) |
| Macros de **texto** (botão que digita uma string) | Escopo é teclado **físico**; `KeyAction`/catálogo já são extensíveis para isso depois |
| Diagonais/8 direções na alavanca | 4 direções cobrem terminal (setas); diagonal não tem keycode natural |
| Remapear/customizar a grade QWERTY (resize/reposição) | Fundação já existe (EXT-01/02 do keyboard-canvas); é outra feature |
| Import/export/compartilhamento de grades | Nice-to-have pós-validação de uso real |
| Fallback para apps que ignoram KeyEvent sintético | Sem contorno confiável; limitação documentada (terminais tratam corretamente) |

---

## User Stories & Acceptance Criteria

### Emissão de teclas físicas (`EMT`)

**Como** usuário de SSH/tmux/neovim, **quero** que os botões enviem eventos idênticos aos de um teclado
físico, para o app remoto interpretar chords corretamente.

1. **EMT-01**: WHEN um atalho é definido THEN ele SHALL ser modelado como `ShortcutKey` (keyCode + modificadores Ctrl/Alt/Shift/Meta), **serializável** (DataStore) — a unidade única usada por célula de grade, prefixo, tecla avulsa e picker.
2. **EMT-02**: WHEN um `ShortcutKey` com modificadores é emitido (chord) THEN o `PhysicalKeyEmitter` SHALL enviar via `InputConnection.sendKeyEvent` a sequência modificador(es) DOWN → tecla DOWN → tecla UP → modificador(es) UP, com `metaState` coerente em todos os eventos.
3. **EMT-03**: WHEN um binding em modo **sequência** dispara THEN o emitter SHALL emitir o prefixo como chord completo e **depois** a tecla escolhida como emissão separada (caso tmux: Ctrl+B, depois `c`).
4. **EMT-04**: WHEN um binding em modo **chord** dispara THEN o emitter SHALL fundir os modificadores do prefixo na tecla escolhida, emitindo um único chord (caso Ctrl+`x` direto).
5. **EMT-05**: WHEN Enter/Esc do teclado atual são acionados THEN eles SHALL passar pelo `PhysicalKeyEmitter` (migração da emissão ad-hoc atual, comportamento preservado).
6. **EMT-06**: WHEN não há `InputConnection` ativa THEN a emissão SHALL ser no-op seguro (sem crash, sem fila fantasma).

### Barras personalizáveis (`BAR`)

**Como** usuário, **quero** montar as barras do teclado com os botões que eu uso.

1. **BAR-01**: WHEN o teclado renderiza THEN a(s) barra(s) SHALL ser desenhada(s) por uma **`BarView` em Canvas** (mesmo paradigma da `KeyboardGridView`: view única, hit-test por célula, função pura de um modelo data-driven), substituindo a barra `LinearLayout` atual.
2. **BAR-02**: WHEN o usuário nunca configurou barras THEN a config de fábrica SHALL ser uma barra com **mic, config e colapsar** — paridade visual e comportamental com a barra atual (goldens comparáveis).
3. **BAR-03**: WHEN o catálogo de botões é consultado THEN ele SHALL conter: **Mic**, **Config**, **Colapsar**, **Tecla avulsa** (`ShortcutKey`), **Alavanca direcional** e **Gatilho de grade** — tipo sealed extensível (casos novos sem mexer no rendering).
4. **BAR-04**: WHEN o usuário adiciona barras extras THEN elas SHALL empilhar acima da barra principal, nos dois modos do teclado (colapsado e expandido), aumentando a altura da janela do IME.
5. **BAR-05**: WHEN uma barra tem mais botões do que cabe na largura THEN ela SHALL rolar lateralmente; a rolagem SHALL iniciar apenas em botões de tap simples ou espaço vazio — botões de arrasto (alavanca, gatilho de grade) capturam o gesto e a barra não rola.
6. **BAR-06**: WHEN `mirrored = true` (padrão) THEN o mesmo conjunto de barras SHALL valer para colapsado e expandido; WHEN `mirrored = false` THEN cada modo SHALL ter sua própria lista de barras.
7. **BAR-07**: WHEN a config de barras muda no app de config THEN o teclado SHALL refletir a mudança ao vivo (mesmo mecanismo de observação do DataStore já usado pelas settings atuais).

### Sincronia preservada (`SYNC`)

**Como** usuário, **quero** que mic, candidatos e colapsar continuem funcionando exatamente como hoje.

1. **SYNC-01**: WHEN o botão de mic (agora célula da `BarView`) é usado THEN estados (`ButtonState`), timer `m:ss` ao vivo, cores e sincronia com overlay/notificação SHALL permanecer idênticos ao atual.
2. **SYNC-02**: WHEN o usuário segura o mic THEN o hold-seletor (`PAUSE_RESUME`/`CANCEL`/`UNDO`) SHALL funcionar igual (KBDCTL-01..04 preservados).
3. **SYNC-03**: WHEN o usuário colapsa/expande (botão ou atalho `TOGGLE_KEYBOARD` da orbe) THEN o comportamento SHALL ser idêntico (`setExpanded`, `preferExpanded` persistido).
4. **SYNC-04**: WHEN o teclado expandido mostra sugestões THEN a candidates view SHALL continuar funcionando na região da barra, como hoje.

### Grade de atalhos efêmera (`GRD`)

**Como** usuário de tmux/neovim, **quero** acessar uma grade de atalhos num gesto só, sem UI permanente.

1. **GRD-01**: WHEN o usuário pressiona um gatilho de grade e **arrasta** THEN a direção inicial do arrasto SHALL ancorar a grade (deslizou para cima → grade se estende acima do dedo), sem configuração de lado.
2. **GRD-02**: WHEN o dedo percorre a grade ancorada THEN a grade SHALL permanecer invisível exceto por uma **lente** (célula atual + vizinhas imediatas) e um **preview acima do ponto de toque** mostrando o atalho da célula atual.
3. **GRD-03**: WHEN o dedo muda de célula THEN o sistema SHALL emitir feedback tátil (padrão `Haptics` existente).
4. **GRD-04**: WHEN o usuário **solta** sobre uma célula atribuída THEN o sistema SHALL emitir conforme o modo do binding: só a tecla da célula / prefixo→tecla em sequência (EMT-03) / prefixo+tecla em chord (EMT-04).
5. **GRD-05**: WHEN o usuário arrasta de volta ao gatilho de origem ou para fora da grade e solta THEN a interação SHALL ser **cancelada** sem emissão (consistente com o padrão cancelar da Fase 2).
6. **GRD-06**: WHEN o dedo fica **parado ~400 ms** durante a interação THEN a grade inteira SHALL ser revelada semi-transparente (modo aprendizado); voltar a arrastar oculta de novo.
7. **GRD-07**: WHEN a grade ancorada extrapola a área visível do teclado (ex.: para cima, no modo colapsado) THEN ela SHALL renderizar sobre a área do app via janela flutuante não-tocável (`PopupWindow` com `isTouchable=false`, padrão já provado pelo seletor do mic) — o gesto pertence ao gatilho; toques de fora continuam indo para o app.

### Alavanca direcional (`JOY`)

**Como** usuário de terminal, **quero** setas rápidas e repetição contínua num botão só.

1. **JOY-01**: WHEN o usuário arrasta a alavanca além do limiar numa das 4 direções THEN o sistema SHALL emitir a seta correspondente (DPAD_UP/DOWN/LEFT/RIGHT) com tique tátil.
2. **JOY-02**: WHEN o usuário mantém a alavanca puxada THEN a seta SHALL repetir com **aceleração progressiva** (generalização do `RepeatSchedule` do backspace), com feedback tátil a cada disparo.
3. **JOY-03**: WHEN o usuário, sem soltar, muda a direção do puxão THEN a repetição SHALL trocar para a nova direção sem disparo espúrio na transição.
4. **JOY-04**: WHEN o usuário solta ou volta ao centro THEN a repetição SHALL parar imediatamente; um flick curto SHALL emitir exatamente uma seta.

### Config UI (`CFG`)

**Como** usuário, **quero** montar barras e grades visualmente, escolhendo teclas num teclado físico simulado.

1. **CFG-01**: WHEN o usuário abre a aba de teclados do app de config THEN ela SHALL conter as seções **"Layout do teclado"** (barras) e **"Teclados de atalhos"** (grades), em Compose no padrão Ateliê.
2. **CFG-02**: WHEN o usuário edita o layout THEN o editor SHALL mostrar as barras **como renderizam no teclado** (WYSIWYG): tocar num botão edita/remove, "+" adiciona do catálogo, arrastar reordena, "+ barra" cria/remove barras.
3. **CFG-03**: WHEN o toggle **"espelhar colapsado/expandido"** está ligado (padrão) THEN há uma config só; desligado, o editor SHALL expor as duas listas separadamente.
4. **CFG-04**: WHEN o usuário edita uma grade THEN o editor SHALL permitir definir dimensões (linhas × colunas) e tocar em cada célula para atribuir um `ShortcutKey` via picker, com label opcional por célula.
5. **CFG-05**: WHEN qualquer atalho precisa ser escolhido (célula, prefixo, tecla avulsa) THEN o sistema SHALL abrir o **picker de teclado físico** compartilhado: um teclado ANSI desenhado + toggles Ctrl/Alt/Shift/Meta (tocar `B` com Ctrl ligado produz `Ctrl+B`).
6. **CFG-06**: WHEN o usuário configura um gatilho de grade THEN ele SHALL escolher: a grade alvo, o prefixo opcional (`ShortcutKey`) e o modo (**só grade** / **sequência** / **chord**).

### Acessibilidade (`A11Y`)

1. **A11Y-01**: WHEN o TalkBack está ativo THEN a `BarView` SHALL expor cada botão como nó virtual (`AccessibilityNodeProvider`, mesmo padrão da grade Canvas): label, bounds, ação de clique — sem regredir o que a barra atual oferece.
2. **A11Y-02**: WHEN o TalkBack aciona uma **tecla avulsa** por duplo-toque THEN o atalho SHALL ser emitido normalmente — teclas avulsas são o caminho acessível para atalhos; grade efêmera e alavanca são gestos contínuos (limitação anunciada no nó: "requer gesto deslizante").

### Rollout seguro (`ROL`)

1. **ROL-01**: WHEN os componentes novos são construídos THEN modelo de barras/grades, `PhysicalKeyEmitter`, máquina de estados da grade efêmera e curva da alavanca SHALL ser **unidades isoladas com testes unitários** (sequências exatas de KeyEvent, transições, aceleração).
2. **ROL-02**: WHEN o build é **debug** THEN uma flag SHALL alternar barra **antiga ↔ `BarView`** para comparação no device; aprovada a validação manual, a nova vira padrão e a antiga sai.
3. **ROL-03**: WHEN as telas estabilizam THEN goldens SHALL cobrir: barra padrão (colapsada/expandida, claro/escuro), barra com botões custom + overflow, grade revelada (modo aprendizado), alavanca, editores e picker na config.

---

## Edge Cases

- WHEN o usuário solta sobre uma **célula vazia** (sem atalho atribuído) THEN nada é emitido e a interação encerra (equivale a cancelar).
- WHEN uma grade referenciada por um gatilho é **excluída** THEN o botão SHALL renderizar desabilitado e o editor SHALL sinalizar o binding órfão (sem crash no IME).
- WHEN a âncora escolhida não comporta a grade inteira na tela THEN a posição SHALL ser **clampada** para caber (a direção do gesto decide o lado, a borda decide o ajuste).
- WHEN `mirrored = false` e um modo fica **sem barras** THEN o teclado SHALL usar a barra padrão de fábrica nesse modo (mic/config/colapsar nunca ficam inacessíveis).
- WHEN todos os botões de uma barra com overflow são **arrastáveis** THEN a rolagem SHALL continuar possível pelas margens/espaço entre botões (hitbox de rolagem garantida).
- WHEN o app em foco **ignora KeyEvent sintético** THEN não há fallback — comportamento documentado; terminais (Termux/Termius/JuiceSSH) tratam como teclado físico.
- WHEN a gravação de voz está ativa e o usuário usa grade/alavanca THEN a emissão SHALL não interferir nos estados do mic (fluxos independentes no service).

---

## Requirement Traceability

| ID | Área | Status | Tipo de verificação |
| --- | ---- | ------ | ------------------- |
| EMT-01..06 | Emissão física | Pending | unit (sequências exatas de `KeyEvent`/metaState com `InputConnection` fake) |
| BAR-01..07 | Barras Canvas | Pending | unit (modelo/hit-test/scroll) + instrumentado + golden (paridade barra padrão) |
| SYNC-01..04 | Mic/candidates/colapsar | Pending | instrumentados existentes (`KeyFlowKeyboardCanvasGridTest` etc.) verdes sem alteração de expectativa |
| GRD-01..07 | Grade efêmera | Pending | unit (máquina de estados: âncora/lente/reveal/cancel) + instrumentado (emissão fim-a-fim) + manual no device (sensação do gesto) |
| JOY-01..04 | Alavanca | Pending | unit (limiar/aceleração/troca de direção) + manual no device (háptica) |
| CFG-01..06 | Config Compose | Pending | instrumentado (editores/picker) + golden |
| A11Y-01..02 | TalkBack | Pending | instrumentado (nós virtuais + `ACTION_CLICK`); leitura em voz manual |
| ROL-01..03 | Rollout | Pending | flag debug + goldens + gate `full` |

**Status values:** Pending → In Design → In Tasks → Implementing → Verified.
**Coverage:** 39 requisitos, 0 mapeados a tasks (fase Tasks pendente).

---

## Success Criteria

- [ ] Numa sessão tmux via SSH: enviar `Ctrl+B` + tecla via grade (sequência), `Ctrl+X` via chord, setas contínuas via alavanca e `Esc`/`Tab` via teclas avulsas — tudo interpretado corretamente pelo terminal.
- [ ] Barra de fábrica indistinguível da atual (goldens) e testes instrumentados do mic verdes sem mudança.
- [ ] Montar uma barra custom completa (grade + alavanca + 2 teclas avulsas) só pela config UI, sem tocar em código.
- [ ] Gesto da grade utilizável "às cegas" após aprendizado: lente + háptica + preview bastam para acertar a célula no Galaxy A32.
