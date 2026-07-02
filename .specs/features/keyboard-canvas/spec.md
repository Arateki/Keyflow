# Keyboard Canvas (teclado fluido) — Specification

> **Trabalho 2.** Substituir o miolo do teclado KeyFlow (a grade QWERTY feita de `LinearLayout` + um
> `TextView` por tecla) por um rendering em **Canvas**, ganhando a fluidez de um teclado nativo, **sem**
> quebrar o botão de microfone sincronizado com overlay/notificação. Só inicia após `orb-gestures` fechar
> (baseline verde).

## Problem Statement

O propósito central do KeyFlow é entrada por voz com **0 px de teclado** durante o uso normal — atendido
hoje pela barra retraída (uma linha). Mas, quando o usuário precisa digitar à mão, o teclado expandido é
pouco fluido: cada tecla é um `TextView` em `LinearLayout`, sem key preview, sem popup de acentos, sem
repeat-on-hold, sem multitouch. Teclados nativos (Gboard/Samsung) desenham todas as teclas numa única
`View` em `Canvas`; a fluidez mora aí, não em código nativo (o C++ do AOSP/AnySoftKeyboard é o motor de
**dicionário**, não o rendering). Reescrever o rendering em Kotlin entrega fluidez máxima com **footprint
mínimo** (zero `.so`), e deixa glide/dicionário como camadas aditivas futuras.

## Goals

- [ ] Grade de teclas desenhada em `Canvas`, dirigida por um **modelo de layout data-driven**, com fluidez (preview, acentos, repeat).
- [ ] Camada de letras + camada **?123** (símbolos), com **PT/EN/ES** (acentos por idioma, `ñ`/`¿¡` no ES).
- [ ] Corretor do **sistema** (`SpellCheckerSession`) com candidates view, só no modo expandido.
- [ ] Acessibilidade básica via `AccessibilityNodeProvider` (teclas lidas pelo TalkBack).
- [ ] **Botão de mic, action row e barra retraída preservados como Views**, com sincronia overlay/notificação intacta.
- [ ] Troca segura: flag de debug (antigo↔novo) + golden estático + validação manual no device; aprovado → novo vira padrão.
- [ ] Modelo de layout **serializável**, preparado para customização futura (resize/reposição/macros) sem refazer o rendering.

## Out of Scope (MVP)

| Fora | Razão |
| ---- | ----- |
| Glide / gesture typing | Camada futura (motor de reconhecimento + dicionário) |
| Dicionário/sugestões/next-word próprios | Futuro; MVP usa só o **corretor do sistema** |
| UI de customização (resize/reposição/macros) | Yan: "em breve" — MVP só prepara a **fundação** data-driven |
| Portar mic/action row/barra para Canvas | Híbrido: ficam Views agora (fronteira pronta p/ portar depois) |
| Novos idiomas além de PT/EN/ES | Reusam a estrutura quando vierem |
| Layout numérico/emoji dedicados | Pós-MVP (a arquitetura de camadas já comporta) |

---

## User Stories & Acceptance Criteria

### Rendering em Canvas fluido (`RND`)

**Como** usuário, **quero** digitar com a fluidez de um teclado nativo.

1. **RND-01**: WHEN o teclado expandido é exibido THEN a grade SHALL ser desenhada por uma única `View` em `Canvas` (uma chamada de `onDraw` pinta todas as teclas), sem um `View` por tecla.
2. **RND-02**: WHEN o dedo pressiona uma tecla THEN o sistema SHALL mostrar **key preview** (a letra ampliada acima do dedo) enquanto pressionada.
3. **RND-03**: WHEN o usuário mantém pressionada uma tecla com variantes THEN o sistema SHALL abrir um **popup de acentos** (long-press) e inserir a variante selecionada ao soltar.
4. **RND-04**: WHEN o usuário mantém o **backspace** THEN o sistema SHALL repetir o apagar (repeat-on-hold) com aceleração razoável; soltar para.
5. **RND-05**: WHEN dois toques quase simultâneos ocorrem THEN o sistema SHALL tratar multitouch básico (não perder a 2ª tecla) — refinamento de fluidez.
6. **RND-06**: WHEN o tema é claro/escuro THEN a grade SHALL usar a paleta Ateliê correspondente (paridade visual com o teclado atual, reskinado).

### Layout data-driven + camadas + idiomas (`LAY`)

**Como** dev, **quero** o layout como dados para suportar camadas, idiomas e (futuro) customização.

1. **LAY-01**: WHEN o teclado renderiza THEN ele SHALL ler um **modelo de layout** (linhas × teclas; cada tecla com geometria — peso/largura/altura — e uma **ação**); o `Canvas` é função pura desse modelo.
2. **LAY-02**: WHEN a ação de uma tecla é definida THEN ela SHALL ser um tipo **extensível** (Character, Backspace, Enter, Space, Shift, LayerSwitch, …) — preparado para **Macro** como caso novo, sem refatorar o rendering.
3. **LAY-03**: WHEN o usuário toca `?123` / `ABC` THEN o sistema SHALL alternar entre a camada de **letras** e a de **símbolos** (`?123`, com símbolos completos: `/ ; " — ( ) @` etc.).
4. **LAY-04**: WHEN o idioma ativo é PT, EN ou ES THEN a **mesma grade QWERTY** SHALL ser usada, variando: mapa de **acentos** (long-press), tecla **`ñ`** (ES), e símbolos **`¿ ¡`** (ES na camada `?123`).
5. **LAY-05**: WHEN o usuário troca o idioma (globo/subtype) THEN o sistema SHALL aplicar o layout/acentos do idioma e **informar o locale ao corretor**; os subtypes SHALL estar declarados em `method_keyboard.xml`.
6. **LAY-06**: WHEN o shift está ativo/caps THEN as teclas SHALL refletir maiúsculas e os dígitos→símbolos conforme a camada.

### Sincronia preservada — não quebrar o que funciona (`SYNC`)

**Como** usuário, **quero** que o mic, a barra e a notificação continuem funcionando exatamente como hoje.

1. **SYNC-01**: WHEN o teclado é exibido THEN o **botão de mic, a action row e a barra retraída** SHALL permanecer como Views, com timer ao vivo (`KeyFlowController.recordingAccumulatedMs/SegmentStartMs`), estados (`buttonState`) e ripple intactos.
2. **SYNC-02**: WHEN o usuário toca/segura o mic THEN o tap (`OverlayService.keyboardMicIntent`) e o **hold-seletor** (`PAUSE_RESUME`/`CANCEL`/`UNDO`) SHALL funcionar igual ao atual (KBDCTL-01..04 da feature `orb-gestures`).
3. **SYNC-03**: WHEN o usuário alterna expandir/retrair (botão ou atalho da orbe `TOGGLE_KEYBOARD`) THEN o comportamento SHALL ser idêntico ao atual (`setExpanded`, `preferExpanded` persistido).
4. **SYNC-04**: WHEN o `KeyFlowKeyboardService` insere texto THEN continua via `getCurrentInputConnection().commitText` (a inserção de voz e a digitação compartilham o mesmo `InputConnection`).

### Corretor do sistema (`SPL`)

**Como** usuário, **quero** sugestões de correção ao digitar, sem motor próprio.

1. **SPL-01**: WHEN o teclado está **expandido** e o usuário digita THEN o sistema SHALL consultar o corretor do sistema (`TextServicesManager`/`SpellCheckerSession`) no locale ativo e exibir candidatos numa **candidates view** acima da grade.
2. **SPL-02**: WHEN o usuário toca um candidato THEN o sistema SHALL substituir a palavra em composição pelo escolhido.
3. **SPL-03**: WHEN o teclado está **retraído** (barra de uma linha) THEN **não** há candidates view (não há digitação) — a barra atual é preservada sem acréscimo de altura.

### Acessibilidade (`A11Y`)

1. **A11Y-01**: WHEN o TalkBack está ativo THEN a grade Canvas SHALL expor cada tecla como nó virtual via `AccessibilityNodeProvider` (label, bounds, ação de clique) — sem regredir o que o teclado atual oferece de graça.
2. **A11Y-02**: WHEN o usuário explora por toque THEN o nó da tecla sob o dedo SHALL ser anunciado, e o duplo-toque SHALL acioná-la.

### Rollout seguro (`ROL`)

1. **ROL-01**: WHEN a grade Canvas é construída THEN ela SHALL ser um **componente isolado e testável** (unit cobre modelo de layout, troca de camada, mapa de acentos por idioma, shift/símbolos).
2. **ROL-02**: WHEN o build é **debug** THEN uma **flag** SHALL permitir alternar teclado **antigo ↔ novo** para comparação no device; em **release** o novo é o padrão.
3. **ROL-03**: WHEN as telas novas estabilizam THEN o sistema SHALL ter **golden** estático (expandido letras + `?123`, retraído, candidates, claro/escuro) e o índice de rotas atualizado; fluidez/preview/acentos são validados **manualmente** no Galaxy A32.

### Extensibilidade futura (`EXT`, NFR)

1. **EXT-01**: WHEN o modelo de layout é definido THEN ele SHALL ser **serializável** (preparado para persistir um layout custom do usuário no futuro).
2. **EXT-02**: WHEN customizações futuras (reduzir tamanho, reposicionar teclas, teclas-macro) forem implementadas THEN elas SHALL ser **aditivas** — editar dados/registrar uma ação nova — sem reescrever o rendering. *(validado por design, não por teste no MVP)*

---

## Edge Cases

- WHEN não há corretor do sistema instalado/ativo para o locale THEN a candidates view SHALL ficar vazia/oculta sem quebrar a digitação.
- WHEN o idioma do subtype não tem mapa de acentos THEN o long-press cai num conjunto base (sem crash).
- WHEN a tela é estreita/landscape THEN a grade SHALL re-medir proporcionalmente (geometria por peso, não px fixos).
- WHEN o TalkBack aciona uma tecla com variantes THEN a ação primária é inserida (popup de acento é gesto tátil; a11y usa a ação base).
- WHEN o teclado novo está atrás da flag desligada THEN o teclado antigo SHALL funcionar exatamente como hoje (rollback trivial).

---

## Requirement Traceability

| ID | Área | Status | Tipo de verificação |
| --- | ---- | ------ | ------------------- |
| RND-01..06 | Rendering Canvas | Implementing | unit (hit-test/estado: `GeometryTest`/`KeyboardGridStateTest`) + golden (`keyboard-canvas-*`); fluidez fica **manual no Galaxy A32** (pendente) |
| LAY-01..06 | Layout/camadas/idiomas | **Verified** | `LayoutsTest` (PT/EN/ES, `?123`, `ñ`, `¿¡`, acentos, shift, `withLanguageKey`) — 100% unit, sem dependência de device |
| SYNC-01..04 | Mic/barra preservados | **Verified** | `KeyFlowKeyboardCanvasGridTest`/`KeyFlowKeyboardSpellCheckerTest` (instrumentado, IME real: mic/timer/hold-seletor intactos com a grade nova) |
| SPL-01..03 | Corretor do sistema | Implementing | `ComposingWordTrackerTest` (unit) + `KeyFlowKeyboardSpellCheckerTest` (instrumentado: composição/backspace/separador); conteúdo real das sugestões depende do corretor do device — **manual** |
| A11Y-01..02 | TalkBack | Implementing | `KeyFlowKeyboardAccessibilityTest` (instrumentado: nó virtual + `ACTION_CLICK`); leitura em voz do TalkBack é **manual** |
| ROL-01..03 | Rollout/flag/golden | Implementing | `KeyFlowKeyboardCanvasGridTest` (flag debug liga/desliga) + golden `keyboard-canvas-expanded/symbols/collapsed/candidates` (claro/escuro, aprovados); promoção a padrão fica pendente da validação manual (KC-T10) |
| EXT-01..02 | Extensibilidade | **Verified** | design review (modelo serializável por construção — `data class` sem referências a Android) |

**Status values:** Pending → In Design → In Tasks → Implementing → Verified.
**Coverage (fim da sessão KC-T1..T9 + goldens da KC-T10):** ver coluna Status. Nenhum item chega a
Verified nas áreas que dependem de validação manual no device físico (RND fluidez, SPL sugestões reais,
A11Y TalkBack, ROL promoção a padrão) — isso é trabalho do Yan, não automatizável neste sandbox.
