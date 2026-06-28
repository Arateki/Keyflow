# Redesign Ateliê (Fase 2) — Specification

## Problem Statement

O KeyFlow é funcional (MVP T1–T15) mas a UI é Material cinza crua, com config de tela única e poucas
opções. O Claude Design entregou uma direção visual completa ("Ateliê" — papel amarelado, hairlines,
mono, orbe reativo) e uma estrutura de produto mais rica (config em abas, presets de API, dark mode,
pausa de gravação). Esta fase reveste todo o app nessa direção em Jetpack Compose, adiciona a pausa de
gravação, e cria um harness de verificação por screenshot no emulador headless para retroalimentar o
agente de implementação. Monetização (créditos/skins) entra como **casca visual inerte**; loja é fase futura.

## Goals

- [x] Todas as telas do app seguem a direção Ateliê (claro e escuro), batendo visualmente com os mocks.
- [x] Pausar/retomar a gravação de áudio com gestos por modo (toggle/PTT) e pela notificação.
- [x] Cada tela/estado é renderizável de forma determinística e verificada por screenshot vs. mock no emulador headless.
- [x] Nenhuma regressão funcional: ditar→inserir, presets de API, overlay, teclado e permissões seguem funcionando (gates unit+instrumented verdes).

## Out of Scope

| Feature | Razão |
| ------- | ----- |
| Backend de créditos + Google Play Billing | Monetização real é fase futura; nesta fase é UI inerte (C8) |
| IAP de skins (compra real) | Idem — skins ficam bloqueadas, sheet inerte |
| i18n real EN/ES | UI fica PT-only; toggle inerte (C12) |
| Publicação na Play Store (privacy policy, Data Safety, consent screen, targetSdk 35) | Fase futura (C2); ver `PLAYSTORE_CHECKLIST.md` |
| Painel de escuta / revisão / streaming no teclado (mock `A-kbd`) | Descartado pelo usuário (C9) |
| Streaming de transcrição em tempo real | APIs Whisper são não-streaming |
| Suporte a Android 5–6 (API 21–23) | minSdk sobe p/ 24 para pause nativo (C21) |

---

## User Stories

### P1: Fundação Compose + Design System ⭐ MVP

**User Story**: Como dev, quero o app rodando em Compose com os tokens Ateliê (cores claro/escuro,
tipografia empacotada) para que todas as telas compartilhem uma fonte de verdade visual.

**Why P1**: Tudo depende disso — sem Compose + tema, nenhuma tela do redesign existe.

**Acceptance Criteria**:
1. WHEN o build é configurado THEN o projeto SHALL compilar com Jetpack Compose habilitado, `minSdk 24`, e os PoCs (`poc/` + `method_poc.xml` + entradas no manifest) removidos.
2. WHEN um Composable usa a tipografia THEN o sistema SHALL renderizar Space Grotesk/Space Mono a partir de `res/font` empacotados (sem provider de Downloadable Fonts).
3. WHEN o tema é aplicado THEN o sistema SHALL expor a paleta Ateliê completa em claro e escuro via um `KeyFlowTheme` (tokens de C/contexto), com acentos teal/terracota/record fixos.
4. WHEN o usuário alterna o tema (sol/lua) THEN o sistema SHALL trocar claro↔escuro e **persistir** a escolha; default = claro.
5. WHEN os gates rodam após a fundação THEN `testDebugUnitTest` e `connectedDebugAndroidTest` SHALL permanecer verdes (nenhuma regressão na lógica existente).

**Independent Test**: App abre em Compose, alterna tema e reabre mantendo a escolha; gates verdes.

---

### P1: Orbe reativo (VoiceOrb) ⭐ MVP

**User Story**: Como usuário, quero o orbe vivo (respira parado, deforma escutando, ondula transcrevendo,
pausa quando pausado) como elemento central do app.

**Why P1**: É o "coração" do design e aparece em config, onboarding, overlay e notificação.

**Acceptance Criteria**:
1. WHEN `VoiceOrb` é renderizado com `state` THEN o sistema SHALL desenhar idle/listening/transcribing fiel ao algoritmo de `reference/orb-algorithm.js` (paleta A).
2. WHEN o estado é **paused** THEN o orbe SHALL exibir o visual de escuta com um **glifo de pause no centro** no lugar do timer.
3. WHEN o orbe é renderizado em modo de verificação THEN o sistema SHALL aceitar um **clock/tempo injetável** para produzir frames determinísticos (mesmo `t` → mesmo pixel).
4. WHEN há tempo de gravação a exibir THEN o sistema SHALL renderizar o timer (`m:ss`, Space Mono 700) **dentro** do orbe, sem pílula/legenda externa.

**Independent Test**: Galeria mostra os 4 estados do orbe com `t` fixo; prints reproduzíveis batendo com as amostras do mock (`Parado/Escutando/Transcrevendo` + pausado).

---

### P1: Config em abas + auto-save ⭐ MVP

**User Story**: Como usuário, quero a configuração organizada em abas (Modo/Voz/Teclado/Overlay/Docs) que
salvam sozinhas, com o header (orbe, idioma, tema).

**Why P1**: É a tela principal do app e o esqueleto que hospeda Voz/Teclado/Overlay/Docs/stubs.

**Acceptance Criteria**:
1. WHEN a config abre THEN o sistema SHALL exibir o header (orbe + "KeyFlow" + label da aba ativa + toggle de idioma + toggle de tema) e a barra de 5 abas com ícones.
2. WHEN um modo (Overlay/Keyboard) está **desligado** na aba Modo THEN a aba correspondente (Overlay/Teclado) SHALL ficar **esmaecida e não-navegável**.
3. WHEN o usuário toca uma aba liberada THEN o sistema SHALL navegar para ela sem recriar a Activity.
4. WHEN o usuário muda um **toggle/segmento** THEN o sistema SHALL persistir **imediatamente** e exibir um **toast discreto** "salvo".
5. WHEN o usuário edita um **campo de texto** (endpoint, token, modelo, idioma) THEN o sistema SHALL persistir **no desfoque, se o valor mudou**, e exibir o toast.
6. WHEN a aba Modo é exibida THEN o sistema SHALL mostrar o estado do Microfone (permissão geral) e cartões "Modo Overlay" e "Modo Keyboard" com toggle, chips de permissão (SOBREPOSIÇÃO/TECLADO) e o placeholder de GIF "como usar".

**Independent Test**: Navegar pelas 5 abas; desligar um modo e ver a aba esmaecer; alterar um toggle e um campo de texto e ver persistência + toast.

---

### P1: Origem da transcrição (presets) ⭐ MVP

**User Story**: Como usuário, quero escolher a origem da voz por preset (Créditos/OpenAI/Groq/Custom) em vez
de digitar URL crua.

**Why P1**: É como o motor de transcrição é configurado; substitui a tela de config atual sem perder função.

**Acceptance Criteria**:
1. WHEN a aba Voz abre THEN o sistema SHALL exibir o segmented "Origem da transcrição" com Créditos/OpenAI/Groq/Custom.
2. WHEN o usuário escolhe **OpenAI** ou **Groq** THEN o sistema SHALL pré-preencher endpoint+modelo do provedor e pedir apenas o **token** (mais modelo/idioma editáveis), persistindo na config.
3. WHEN o usuário escolhe **Custom** THEN o sistema SHALL permitir endpoint, header, valor, modelo e idioma manuais (paridade com a config atual).
4. WHEN o usuário escolhe **Créditos** THEN o sistema SHALL exibir o cartão de saldo **mock** (128/500, ≈42 min usados, 165 min no total) + "Recarregar créditos" + "Pagamento via Google Play", todos **inertes**.
5. WHEN a origem ativa é Créditos E o usuário tenta gravar THEN o sistema SHALL avisar "indisponível (em breve)" e **não** transcrever.
6. WHEN uma origem BYO-key (OpenAI/Groq/Custom) está completa E o usuário grava THEN o sistema SHALL transcrever via o endpoint configurado (comportamento atual preservado).

**Independent Test**: Trocar entre presets; configurar OpenAI e Custom com chaves e transcrever em ambos; selecionar Créditos e ver o aviso "em breve" ao gravar.

---

### P1: Reskin do teclado + timer no mic ⭐ MVP

**User Story**: Como usuário, quero o teclado atual com o visual Ateliê e, ao gravar, o tempo dentro do
botão de microfone — sem mudar como o teclado funciona.

**Why P1**: O teclado é um dos dois modos de entrada; precisa combinar com o resto.

**Acceptance Criteria**:
1. WHEN o teclado KeyFlow é exibido THEN o sistema SHALL renderizar QWERTY+dígitos+shift+linha de ação na paleta Ateliê (teclas papel, labels mono), mantendo o comportamento de **encolher/expandir** atual.
2. WHEN a gravação inicia pelo botão de mic THEN o botão SHALL exibir o **tempo decorrido** (`m:ss`) no lugar do ícone, e voltar ao ícone ao encerrar.
3. WHEN o estado do `KeyFlowController` muda (idle/recording/sending/pending/error) THEN o botão de mic SHALL refletir o estado visual correspondente na paleta Ateliê.
4. WHEN o teclado está encolhido THEN o sistema SHALL preservar a barra reduzida atual (mic/colar/esc/⏎/expandir), reskinada.

**Independent Test**: Ativar o teclado, ver as teclas em papel; gravar e ver o timer no mic; encolher/expandir.

---

### P1: Reskin do overlay + sincronia + anti-movimento ⭐ MVP

**User Story**: Como usuário, quero a bola flutuante como o orbe reativo, mostrando estado/tempo, e que
arrastar para reposicionar não dispare gravação.

**Why P1**: O overlay é o outro modo de entrada e o uso mais "mãos-livres".

**Acceptance Criteria**:
1. WHEN o overlay está ativo THEN o sistema SHALL desenhar o **orbe** (não a bola "KF") arrastável, com sombra, refletindo idle/listening/paused/transcribing.
2. WHEN a gravação está em curso THEN o orbe SHALL mostrar o **tempo no centro**; quando pausado, o **glifo de pause**.
3. WHEN o usuário **arrasta** o orbe (gesto de movimento) THEN o sistema SHALL **reposicionar sem iniciar gravação** e persistir a nova posição.
4. WHEN o usuário está em **PTT segurando+gravando** e arrasta THEN o sistema SHALL **continuar gravando** durante o movimento.
5. WHEN a aba Overlay define **posição inicial** (Esq/Dir) e **tamanho** (P/M/G) THEN o sistema SHALL aplicar e persistir esses ajustes no overlay.
6. WHEN três toques rápidos ocorrem THEN o sistema SHALL abrir o seletor de teclado (comportamento atual preservado).

**Independent Test**: Ligar overlay, ver o orbe; arrastar sem gravar; gravar e ver tempo/pausa no centro; mudar posição/tamanho na aba e ver refletir.

---

### P1: Pausa de gravação ⭐ MVP

**User Story**: Como usuário, quero pausar e retomar a gravação no meio do ditado, por gesto e pela
notificação, para usar o conteúdo já falado sem encerrar.

**Why P1**: Capacidade nova explicitamente pedida; muda a máquina de estados de gravação.

**Acceptance Criteria**:
1. WHEN a gravação está ativa E o gesto de pausa do modo ocorre THEN o sistema SHALL **pausar** a captura (`MediaRecorder.pause()`), entrar no estado "pausado" e parar o timer.
2. WHEN está pausado E o gesto de retomar ocorre THEN o sistema SHALL **retomar** (`MediaRecorder.resume()`) e voltar a "escutando" com o timer correndo.
3. WHEN o modo é **toggle** THEN o gesto de pausa/retoma SHALL ser um **hold breve** no botão/overlay durante a gravação.
4. WHEN o modo é **push-to-talk** THEN o gesto de pausa SHALL ser **soltar + 1 tap rápido**; retomar conforme definido no design.
5. WHEN pausado THEN o orbe/overlay/notificação SHALL exibir o **glifo de pause no centro** (no lugar do timer).
6. WHEN o usuário encerra a partir de pausado ("Parar e copiar"/gesto de parar) THEN o sistema SHALL finalizar o áudio acumulado e seguir para transcrição normalmente.
7. WHEN o modo selecionado muda THEN a **explicação do gesto de pausa** SHALL ser exibida na área de seleção do modo (Overlay/Teclado), na aba Modo (contextual) e na aba Docs.

**Independent Test**: Em toggle: gravar, hold breve → pausa (glifo), hold breve → retoma, parar → transcreve. Em PTT: o gesto soltar+tap rápido pausa.

---

### P1: Notificação de gravação ⭐ MVP

**User Story**: Como usuário, quero a notificação de gravação no visual Ateliê com waveform e ações de
pausar/retomar e parar e copiar.

**Why P1**: A FGS exige notificação; é superfície visível e controle de pausa fora do app.

**Acceptance Criteria**:
1. WHEN a gravação está ativa THEN o sistema SHALL postar uma notificação Ateliê (escura) com orbe + "● Gravando · m:ss" + waveform + ações.
2. WHEN o usuário toca **Pausar/Retomar** THEN o sistema SHALL pausar/retomar a captura e atualizar o rótulo/estado da notificação.
3. WHEN o usuário toca **Parar e copiar** THEN o sistema SHALL encerrar a gravação e seguir o fluxo de transcrição/inserção (ou pendência, se sem campo).

**Independent Test**: Gravar, ver a notificação; pausar/retomar por ela; parar e copiar e ver o texto fluir.

---

### P1: Onboarding ⭐ MVP

**User Story**: Como usuário novo, quero uma tela de boas-vindas que me guie pelas permissões.

**Why P1**: Sem teclado+overlay+microfone nada funciona; o caminho é pouco descobrível.

**Acceptance Criteria**:
1. WHEN o app abre pela 1ª vez (ou com pré-requisitos pendentes) THEN o sistema SHALL exibir o onboarding Ateliê (orbe grande, headline "Sua voz vira texto em qualquer campo.", checklist Microfone/Sobreposição/Teclado, "Conceder permissões", "Pular por agora").
2. WHEN cada permissão está concedida/pendente THEN o checklist SHALL refletir o estado (✓ / FALTA).
3. WHEN o usuário toca "Conceder permissões" THEN o sistema SHALL encaminhar às telas de sistema apropriadas; "Pular por agora" leva à config.

**Independent Test**: Instalação limpa → onboarding com checklist; conceder e chegar à config.

---

### P1: Harness de verificação por screenshot ⭐ MVP

**User Story**: Como dev/agente, quero renderizar cada tela/estado de forma determinística no emulador
headless e capturar prints rotulados para comparar com os mocks e iterar.

**Why P1**: É o mecanismo que retroalimenta o agente e trava a fidelidade visual (pedido explícito).

**Acceptance Criteria**:
1. WHEN o harness roda THEN o sistema SHALL renderizar cada **rota** (tela+estado+tema) isolada e determinística (orbe com `t` fixo, dados mock fixos, status bar fixa).
2. WHEN a captura roda no emulador headless THEN o sistema SHALL salvar **1 PNG por rota** em `verification/<rota>.png` por um comando/script repetível.
3. WHEN superfícies de sistema (IME sobre campo, overlay sobre app, notificação) precisam de print THEN o sistema SHALL capturá-las via **UiAutomator** dirigindo o app real.
4. WHEN uma rota é aprovada THEN seu PNG SHALL servir de **golden** para diff de regressão em rodadas seguintes.
5. WHEN o inventário de rotas muda THEN o índice de rotas SHALL refletir o conjunto atual (sem rota órfã/sem print).

**Independent Test**: Rodar o script → pasta `verification/` populada com 1 PNG por rota; rodar de novo → prints idênticos (determinismo); diff de regressão acusa mudança proposital.

---

### P2: Skins (bloqueadas) + sheet de desbloqueio (stub)

**User Story**: Como usuário, quero ver os temas de teclado/overlay disponíveis e a oferta de desbloqueio.

**Why P2**: É casca visual de monetização; não funcional, mas faz parte das telas dos mocks.

**Acceptance Criteria**:
1. WHEN as abas Teclado/Overlay exibem "Skins" THEN o sistema SHALL mostrar as amostras esmaecidas + chip "BLOQUEADO" + botão "Desbloquear skins".
2. WHEN o usuário toca "Desbloquear skins" THEN o sistema SHALL abrir o bottom-sheet ("Recarregar R$ 20 — MELHOR" / "Pagar R$ 15 uma vez" / "Pagamento via Google Play"), **inerte**.
3. WHEN o usuário fecha o sheet THEN nenhuma compra SHALL ocorrer e o tema ativo SHALL permanecer o Ateliê padrão.

**Independent Test**: Abrir o sheet, ver as opções, fechar — nada muda.

---

### P2: Origem Créditos (stub) + Docs

**User Story**: Como usuário, quero ver a opção de créditos e uma aba de documentação explicando o app.

**Why P2**: Completa a casca visual (C8) e a descoberta do produto/pausa (C19).

**Acceptance Criteria**:
1. WHEN a aba Docs abre THEN o sistema SHALL exibir "Como funciona" (01 Fale / 02 Transcreve / 03 Cola), Modos, Privacidade, Créditos e a **explicação do pause por modo**.
2. WHEN a origem Créditos é exibida THEN o cartão de saldo e CTA SHALL ser stubs inertes (ver P1 Voz AC4/AC5).

**Independent Test**: Abrir Docs e ver as seções incl. pause; ver a aba Voz/Créditos inerte.

---

### P2: Toggle de idioma (inerte)

**User Story**: Como usuário, quero ver o seletor de idioma no header (mesmo que ainda não troque a UI).

**Why P2**: Fidelidade ao mock; i18n real é fase futura (C12).

**Acceptance Criteria**:
1. WHEN o header é exibido THEN o sistema SHALL mostrar o toggle de idioma (PT) com aparência do mock.
2. WHEN o usuário toca o toggle THEN o sistema SHALL sinalizar "em breve" e **não** trocar o locale.

**Independent Test**: Tocar o toggle e ver que a UI permanece PT.

---

## Edge Cases

- WHEN o dispositivo está em API < 24 (não deve ocorrer pós-C21) THEN o build SHALL recusar instalar (`minSdk 24`).
- WHEN a permissão de overlay/microfone é revogada em runtime THEN o sistema SHALL detectar e reconduzir ao onboarding/avisar.
- WHEN o usuário arrasta o orbe muito rápido logo após tocar THEN o sistema SHALL classificar como **drag** (reposição), nunca como tap→gravar (C17).
- WHEN o pause é acionado em PTT mas nenhum tap rápido segue o soltar THEN o sistema SHALL **encerrar** normalmente (soltar = parar+enviar).
- WHEN há transcrição pendente (sem campo) e o usuário foca um campo THEN o estado "pronto para colar" SHALL valer (comportamento atual preservado).
- WHEN o tema é escuro THEN **todas** as telas (config, onboarding, teclado, overlay host, sheets) SHALL ter contraste correto (sem texto papel sobre papel).
- WHEN a fonte empacotada falta um peso THEN o sistema SHALL cair no peso mais próximo sem quebrar layout.
- WHEN o orbe roda fora do modo de verificação THEN o sistema SHALL animar em tempo real (clock real), não congelado.

---

## Requirement Traceability

| ID | Story | Fase | Status |
| --- | ----- | ---- | ------ |
| FND-01 | P1 Fundação | Execute | Verified |
| FND-02 | P1 Fundação | Execute | Verified |
| FND-03 | P1 Fundação | Execute | Verified |
| FND-04 | P1 Fundação (tema toggle) | Execute | Verified |
| FND-05 | P1 Fundação (gates verdes) | Execute | Verified |
| ORB-01 | P1 Orbe | Execute | Verified |
| ORB-02 | P1 Orbe (paused) | Execute | Verified |
| ORB-03 | P1 Orbe (clock injetável) | Execute | Verified |
| ORB-04 | P1 Orbe (timer no centro) | Execute | Verified |
| CFG-01 | P1 Config abas | Execute | Verified |
| CFG-02 | P1 Config abas (gating) | Execute | Verified |
| CFG-03 | P1 Config abas (navegação) | Execute | Verified |
| CFG-04 | P1 Config abas (auto-save toggle) | Execute | Verified |
| CFG-05 | P1 Config abas (auto-save texto/desfoque) | Execute | Verified |
| CFG-06 | P1 Config abas (aba Modo) | Execute | Verified |
| VOZ-01 | P1 Presets | Execute | Verified |
| VOZ-02 | P1 Presets (OpenAI/Groq) | Execute | Verified |
| VOZ-03 | P1 Presets (Custom) | Execute | Verified |
| VOZ-04 | P1 Presets (Créditos stub) | Execute | Verified |
| VOZ-05 | P1 Presets (Créditos bloqueia gravar) | Execute | Verified |
| VOZ-06 | P1 Presets (BYO transcreve) | Execute | Verified |
| KBD-01 | P1 Teclado | Execute | Verified |
| KBD-02 | P1 Teclado (timer no mic) | Execute | Verified |
| KBD-03 | P1 Teclado (estados) | Execute | Verified |
| KBD-04 | P1 Teclado (encolher) | Execute | Verified |
| OVL-01 | P1 Overlay (orbe) | Execute | Verified |
| OVL-02 | P1 Overlay (tempo/pause centro) | Execute | Verified |
| OVL-03 | P1 Overlay (anti-movimento) | Execute | Verified |
| OVL-04 | P1 Overlay (PTT+mover grava) | Execute | Verified |
| OVL-05 | P1 Overlay (posição/tamanho) | Execute | Verified |
| OVL-06 | P1 Overlay (triplo-toque) | Execute | Verified |
| REC-01 | P1 Pausa (pause) | Execute | Verified |
| REC-02 | P1 Pausa (resume) | Execute | Verified |
| REC-03 | P1 Pausa (gesto toggle) | Execute | Verified |
| REC-04 | P1 Pausa (gesto PTT) | Execute | Verified |
| REC-05 | P1 Pausa (glifo) | Execute | Verified |
| REC-06 | P1 Pausa (encerrar de pausado) | Execute | Verified |
| REC-07 | P1 Pausa (explicação por modo) | Execute | Verified |
| NOTIF-01 | P1 Notificação | Execute | Verified |
| NOTIF-02 | P1 Notificação (pausar/retomar) | Execute | Verified |
| NOTIF-03 | P1 Notificação (parar e copiar) | Execute | Verified |
| ONB-01 | P1 Onboarding | Execute | Verified |
| ONB-02 | P1 Onboarding (checklist) | Execute | Verified |
| ONB-03 | P1 Onboarding (conceder/pular) | Execute | Verified |
| VERIFY-01 | P1 Harness (determinismo) | Execute | Verified |
| VERIFY-02 | P1 Harness (1 PNG/rota) | Execute | Verified |
| VERIFY-03 | P1 Harness (UiAutomator sistema) | Execute | Verified |
| VERIFY-04 | P1 Harness (golden regressão) | Execute | Verified |
| VERIFY-05 | P1 Harness (índice de rotas) | Execute | Verified |
| SKIN-01 | P2 Skins | Execute | Verified |
| SKIN-02 | P2 Skins (sheet) | Execute | Verified |
| SKIN-03 | P2 Skins (inerte) | Execute | Verified |
| MON-01 | P2 Docs/Créditos | Execute | Verified |
| MON-02 | P2 Docs/Créditos (stub) | Execute | Verified |
| I18N-01 | P2 Idioma | Execute | Verified |
| I18N-02 | P2 Idioma (inerte) | Execute | Verified |

**ID format:** `[ÁREA]-[NÚMERO]` (FND=fundação, THEME via FND, ORB=orbe, CFG=config, VOZ=origem, KBD=teclado,
OVL=overlay, REC=pausa/gravação, NOTIF=notificação, ONB=onboarding, VERIFY=harness, SKIN=skins, MON=créditos/docs, I18N=idioma)

**Status values:** Pending → In Design → In Tasks → Implementing → Verified

**Coverage:** 52 requisitos · **todos Verified** (T1–T27) · 0 órfãos ✅

---

## Success Criteria

- [x] Todas as telas dos mocks (config 5 abas, onboarding, overlay, teclado, notificação, sheets) existem em Compose, claro e escuro, batendo com os mocks na galeria de verificação. *(T10–T17, T22–T24; golden T26)*
- [x] Pausar/retomar funciona por gesto (toggle e PTT) e pela notificação; o estado pausado mostra o glifo no centro. *(T18–T20, T22, T24)*
- [x] Arrastar o overlay nunca inicia gravação; PTT segurando+movendo segue gravando. *(T20 unit + T22 instrumented)*
- [x] Presets OpenAI/Groq/Custom transcrevem com a chave do usuário; Créditos avisa "em breve". *(T5/T12 — lógica `effectiveConfigFor` + MockWebServer; **e2e com endpoint real pendente em device físico**, ver Pendências do STATE)*
- [x] Config salva sozinha (toggle imediato, texto no desfoque) com toast discreto. *(T10/T12)*
- [x] `verification/` populado com 1 PNG determinístico por rota; rodar 2× produz prints idênticos. *(T7/T8; golden T26)*
- [x] Gates `testDebugUnitTest` + `connectedDebugAndroidTest` verdes; nenhuma regressão funcional do MVP. *(55 instrumentados, 0 falhas)*
- [x] `poc/` removido; `minSdk 24`; fontes empacotadas; sem Downloadable Fonts. *(T1–T3)*
