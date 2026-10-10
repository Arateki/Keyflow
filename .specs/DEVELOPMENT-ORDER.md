# KeyFlow — Ordem de Desenvolvimento até a Play Store

> **Para o agente executor**: este arquivo é o mapa. Ele diz **em que ordem** trabalhar e onde
> está o detalhe de cada task. O detalhe (What/Where/Done when/Tests/Gate) vive SEMPRE no
> `tasks.md` da feature — não invente escopo a partir daqui. Trabalhe **uma task por vez**, na
> ordem, e só avance com o gate verde.

## Regras de ouro (valem para TODAS as tasks)

1. **Contexto mínimo por task**: leia `spec.md` + `design.md` + `context.md` + `tasks.md` da
   feature ativa e `.specs/codebase/TESTING.md`. Não carregue specs de outras features.
2. **Gate no sandbox**:
   `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon <task>`
   — nunca o wrapper às cegas. Instrumentado escopado com `ANDROID_SERIAL=emulator-5554`.
3. **Device físico do Yan**: antes de `adb install -r`, **sempre** `installDebug`/`assembleDebug`
   — nunca só `compileDebugKotlin` (não gera APK novo).
4. **Goldens**: fonte de verdade é `verification/golden/` (nunca o mirror em `/tmp`). Fluxo:
   `scripts/verify-screens.sh` → `scripts/diff-screens.sh` (`--approve` só com mudança visual
   intencional e revisada). Goldens pré-existentes devem ficar **bit-idênticos** salvo a task
   dizer o contrário.
5. **Não existem mais "falhas conhecidas e ambientais".** Esta regra dizia para não tentar
   consertar 2 falhas do `SystemSurfacesScreenshotTest` — e esse rótulo foi justamente o que
   escondeu um defeito real por uma sessão inteira (Q11). Em 2026-09-09 a suíte fechou **163/163
   no A32 e 163/163 no emulador A17**. Se um teste falhar, ele está dizendo alguma coisa: meça
   antes de chamar de ambiental, e prefira `screencap`/`printToLog` a campos de `dumpsys` que
   parecem confirmar o que você quer ouvir.
6. **Ao terminar cada task**: atualizar o Status no `tasks.md` da feature, a traceability do
   `spec.md` (Pending→Implemented/Verified) e registrar lições/decisões no
   `.specs/project/STATE.md`. Commit atômico por task, mensagem em português, **sem citar Claude
   nem trailers**.
7. **Desvio de spec**: se a realidade do código contradisser a task, NÃO desvie em silêncio —
   documente `SPEC_DEVIATION` no STATE.md e na task (padrão já usado na T19 do redesign).
8. **Nunca fabricar API** (Google/AdMob/Play/sherpa/crates): conferir doc oficial na task
   (Knowledge Verification Chain). Sem certeza → dizer que não sabe, não inventar.
9. **Portões do Yan**: itens marcados **[YAN]** são decisões/validações manuais dele (device
   físico, Play Console, aprovações). O agente prepara tudo, avisa, e **não** executa por ele.

## Recorte da v1 — a primeira versão na Play Store (2026-10-07)

> **Decisão do Yan (2026-10-07)**: a primeira versão pública sai **gratuita e sem monetização**. Até
> aqui a loja ficava no fim da fila (VC → MZ → TS → PS), presa às credenciais da API. Agora a v1 é o
> próximo marco; o que não está pronto entra como "Em breve", e a cadeia API → monetização anda em
> paralelo para uma atualização (v1.1). Fica valendo a partir daqui — os Blocos 5, 7 e 8 saem da frente
> da loja.

**O que a v1 é**: o teclado de voz completo sem conta nossa — a pessoa usa a própria chave (OpenAI,
Groq, endpoint próprio) ou os modelos locais, offline.

### Entra (já existe e funciona)

- Teclado de voz: IME, orbe flutuante, gravação (FGS `microphone`), pausa, cancelar/desfazer, gestos.
- Transcrição por chave própria (OpenAI/Groq/Custom) e por modelos locais (sherpa-onnx, offline).
- Teclado Canvas (PT/EN/ES, acentos, corretor próprio), barras de atalhos, grades e teclas físicas.
- Tour de gestos, Docs, onboarding.
- Aparência: Ateliê, Aero, Glitch, Rose Gold, o mascote e skins importáveis (`.zip`) — ver decisão 3.
- Raciocínio: modelos locais (Gemma/Qwen) e provedores por chave de API (OpenAI, Anthropic, Google).
  Login ChatGPT e SSO do Google continuam desligados no release (`enabled = BuildConfig.DEBUG`).
- Agente KeyFlow da fase 1 (chat + ferramentas de skin com confirmação; MCP desligado) — **só se a
  V1-4 fechar**; senão ele entra como "Em breve".

### Entra como "Em breve" (visível, rotulado, sem nada que pareça funcionar)

- Abas **Visão, Fala, Fluxos, Computador e Notificação**: um cartão "Em breve" no lugar do conteúdo —
  nenhum campo, botão ou permissão. Hoje Visão/Fala/Fluxos/Computador têm tela e `Settings`, mas nenhum
  motor (vieram do trabalho do Gemini, sem spec); a Computador tem um "Testar conexão" simulado
  (`delay(600)`) e guarda chave privada SSH.
- Origem **Créditos** (Transcrição, Raciocínio, KeyFlow): desabilitada, com "Em breve"; sem recarga.

### Sai do build de release

- `UnlockSheet` ("Desbloquear skins", "Pagar R$ 15 — uma vez", "Recarregar R$ 20 em créditos") e o
  "Recarregar créditos" do `CreditsCard`: botão com preço que não cobra é o pior caso na revisão
  (funcionalidade quebrada e cobrança fora do Play Billing).
- Fica para depois da v1: monetização (MZ, v1.1), `vision-capture`, `theme-system` (spec a revisar —
  ver Estado atual), fases 2/3 do agente e os motores de Fala/Fluxos/Computador (sem spec).

### Regras da Play que moldam o recorte (lidas na fonte em 2026-10-07)

- **Minimum Functionality** (answer/9898783): proíbe app "designed to do nothing or have no function" e
  funcionalidade quebrada ("crash, force close, freeze, or otherwise function abnormally"). Não fala de
  "em breve". O núcleo da v1 funciona; um "Em breve" honesto não é funcionalidade quebrada — um botão
  que não faz nada é.
- **Deceptive Behavior** (answer/9888077): sem "false or misleading information or claims, including in
  the description, title, icon, and screenshots" → a listagem (V1-7) mostra só o que entra. E o §5
  proíbe recurso "hidden, dormant, or undocumented" feito para ser ligado remotamente → um "Em breve"
  vira recurso **numa versão nova do app**, nunca por config remota.
- **Teste obrigatório** (answer/14151465): conta **pessoal** criada depois de 13/11/2023 precisa de teste
  fechado com **12 testadores por 14 dias seguidos** antes de pedir produção (análise de até ~7 dias).

### Tasks da v1 (em ordem; uma por vez, gate verde)

- ~~**V1-0** — Mesclar a PR #7 (skins até 120 fps, mascote, provedores Anthropic/Google, agente fase 1)
  com o CI verde.~~ **Feito em 2026-10-10** (merge `4cd4ee4`, CI #35 verde sem repetição). No caminho: repo
  público, regra "main protegida", gate que repete 1× o que falha, esteira de instabilidade (TESTING.md).
- **V1-1** — "Em breve" e fim das cascas inertes (listas acima). Instrumentado + goldens das rotas.
- **V1-2** — Higiene do release, pendências herdadas do trabalho do Gemini: logs que imprimem o texto
  ditado (`KeyFlowController`, `OverlayCoordinator` — `take(30)`) só no debug; leitura dos segredos fora
  da main thread (`settingsFlow` → `flowOn(Dispatchers.IO)`); chave de API num lugar só (o cofre
  `keyflow_auth`, contra AUTH-02). Os três logins por assinatura (OpenAI, Anthropic, Google) já são
  `enabled = BuildConfig.DEBUG`; conferir no AAB que o da Anthropic não vai junto — a política dela
  proíbe login de assinatura em produto de terceiros (Bloco 10), e código dormente pesa no §5 acima.
- **V1-3** — Build de release (PS-5): R8 (`isMinifyEnabled`) com keep rules (AIDL dos processos
  `:inference`/`:reasoning`, sherpa-onnx, LiteRT-LM, AndroidSVG), AAB, `versionName` 1.0.0, smoke do
  release no A32. **[YAN]**: chave de upload (Play App Signing) e o `applicationId` (decisão 2).
- **V1-4** — Agente na v1: recorte da AGT-T10 para a fase 1 (abuso: injeção, segredo, argumentos,
  flood) + validação real com a chave do Yan **[YAN]**. Não fechou → agente vira "Em breve".
- **V1-5** — Política de privacidade (PS-2): áudio sob demanda para a origem escolhida, modelos locais,
  provedores de raciocínio por chave (`PLAYSTORE_CHECKLIST.md` §1b), agente, skins importadas; sem
  anúncios nem compras na v1. URL permanente **[YAN decide onde]** + link no app.
- **V1-6** — Prominent disclosure no onboarding (PS-3). Instrumentado + golden.
- **V1-7** — Listing (PS-6) só com o que entra **[YAN aprova textos]**; rascunho campo a campo do Data
  Safety (PS-4) e da declaração dos serviços em primeiro plano (`microphone`, `dataSync`) — o Yan
  preenche no Console **[YAN]**.
- **V1-8** — Trilha interna → teste fechado → produção (PS-7) **[YAN]**. Antes dela, os roteiros
  manuais pendentes no A32 do que entra: GT-T8, NM-T13/T18, RSN-T19 (Gemma/Qwen: tempo e RAM) e o das
  skins.

### Decisões do Yan que a v1 precisa

1. **Conta de desenvolvedor**: pessoal ou organização (Arateki). Organização exige D-U-N-S (grátis na
   Dun & Bradstreet, até 30 dias) e, portanto, uma empresa registrada. Conta pessoal que monetiza
   mostra o **endereço completo** na Play (answer/13628312).
2. **`applicationId` definitivo** antes do 1º upload — depois dele não muda: `com.keyflow` (atual; não
   há app público com esse id, mas só o Console confirma) ou `com.arateki.keyflow`. Muda junto o
   `PACKAGE_NAME` da API.
3. **Temas "pagos, liberados por ora"** (Aero, Glitch, Rose Gold, mascote) numa v1 sem cobrança:
   (a) grátis para sempre, só temas futuros pagos — não tira nada de quem instalou; (b) grátis na v1 e
   pagos na v1.1, preservando quem já tem; (c) "Em breve" na v1.
4. Onde hospedar a política de privacidade.

**Pronto quando**: app aprovado em produção; teste fechado cumprido; nenhum botão que não faz nada;
listing só com o que existe.

### Em paralelo: API em staging (pré-requisito da v1.1)

A API-T13 não bloqueia a v1. O staging sobe já com `INTEGRITY_RELAXED=true` (Postgres + domínio +
provedor de transcrição); a service account do Google depende do app existir no Play Console (V1-3/V1-8)
e entra depois. A v1.1 = MZ-T1..T15 + API-T14 em cima da v1 publicada.

## Estado atual (2026-10-07)

| Milestone | Feature | Status |
| --------- | ------- | ------ |
| M0–M2 | PoC, MVP `voice-keyboard`, `redesign-atelie` | ✅ concluídos |
| M4 | `orb-gestures` (reconciliação) | ✅ concluído |
| M5 | `keyboard-canvas` | ✅ concluído (KC-T1..T10, grade Canvas promovida a padrão, teclado antigo removido) |
| M6 | `shortcut-bars` | ✅ concluído (SB-T1..T10; manual do Yan no A32 confirmado em 2026-09-09) |
| — | Ajustes pós-uso no A32 (Q8 + emendas) | ✅ dimensionamento em paisagem, modais dos editores, crash das prefs cifradas — ver Bloco 1 |
| — | `gesture-tour` | GT-T1..T10 feitos (2026-09-24); falta só o manual do GT-T8 no A32 **[YAN]** |
| — | `native-voice-models` | ✅ código completo (NM-T1..T12, T14..T17 + núcleo da T18; PR #3). CI verde em `5db6d75`: 240/240 instrumentados, telas 84/84. Desde o PR #5 o download é da fila do app (IPv4 primeiro), não do DownloadManager — desvio da G6 no design. Faltam os roteiros manuais T13/T18 no A32 **[YAN]** (`manual-validation.md`) |
| — | Teclado pós-uso no A32 (PR #5) | ✅ fluidez (sem chamada síncrona por letra, escorregar, rollover, vibração, prévia na janela), acentos no jeito do Samsung, fileira de 56 dp, cores "Sistema" padrão, corretor próprio com troca no espaço (Q9), cursor no meio do texto |
| — | `vision-capture` | spec/design/tasks prontos — nada do motor implementado; fora da v1. ⚠️ A aba Visão do trabalho do Gemini já existe com "Origem da visão" (Créditos/endpoint próprio), contra a spec ("nenhum byte sai do aparelho") — decidir antes da VC-T11 |
| M7 | `keyflow-api` | API-T1..T12 ✅ + T13 parcial (Dockerfile + `api/docs/deploy.md`), **na `main` desde 2026-09-28** (PR #4); T13 parou nas credenciais/staging **[YAN]** |
| M7 | `monetization` | spec/design/tasks prontos — nada implementado (depende da API em staging; MZ-T1/T2/T5/T11 podem adiantar) |
| M8 | `theme-system` | spec de 2026-07-04, nada implementado — **defasada**: Aero, Glitch e Rose Gold saíram por fora (quick 013/015/016, pintores no IME como `KeySurface`/`RoseGoldPainter`) e as skins por pacote existem; não há `ThemeRegistry`. Revisar a spec antes de executar. Fora da v1 |
| — | Play Store | **v1 = recorte acima** (V1-0..V1-8). `PLAYSTORE_CHECKLIST.md`; PS-1 (targetSdk 36) feito; PS-2..PS-7 viram V1-3/V1-5..V1-8 |
| — | `reasoning-models` (aba Raciocínio) | **na `main` desde 2026-09-30**: RSN-T0..T17 feitos e o texto de privacidade da RSN-T18 em `PLAYSTORE_CHECKLIST.md` §1b (a traceability RPRV-01..04 ainda diz "In Tasks"). Faltam só **[YAN]**: repositório público, elegibilidade do login ChatGPT (RPRV-04) e o roteiro RSN-T19 no A32 — ver Bloco 10 |
| — | `skin-packs` (skins importáveis) + skin mascote | SKP-T1..T9 feitos (motor AndroidSVG, importação nas abas Teclado e Sobrepor, pacote de exemplo, orbe de pacote até 120 fps) e o mascote nativo (quick 018); na `main` local e na **PR #7**. Falta o roteiro no A32 **[YAN]** (pacote dele, orbe animada no Helio G80, IME real) |
| — | `agent-harness` (agente KeyFlow) | fase 1 (AGT-T1..T5: núcleo, Anthropic, chat, ferramentas de skin, Ajustes; MCP desligado) na `main` local e na **PR #7**. Pendentes: AGT-T6 (ferramentas de app), T7 (OpenAI/Google), T8 (skins por modelo), T9 (MCP), T10 (abuso + validação real **[YAN]**) — o recorte da T10 para a fase 1 é a V1-4 |
| — | Trabalho do Gemini (abas KeyFlow, Fluxos, Computador, Fala, Visão; provedores Anthropic/Google) | commitado e na PR #7. Provedores e aba KeyFlow funcionam; **Fluxos, Computador, Fala e Visão são só tela + `Settings`, sem motor e sem spec** → "Em breve" na v1 (V1-1). Pendências de segurança/release herdadas → V1-2 |
| — | CI | 2026-10-07: cache do AAR do sherpa-onnx nos dois jobs (um 503 do GitHub derrubou a PR #7 antes dos testes) |

---

## Bloco 0 — Portões do Yan em aberto **[YAN]**

Nada aqui é task de agente; são confirmações pendentes no Galaxy A32:

- [x] Globo cicla PT→EN→ES de verdade; seletor do sistema mostra só 1 "KeyFlow"; prévia de
      tamanho reage ao arrasto. **Yan confirmou (2026-07-04)**.
- [x] Fluidez da grade Canvas → **Yan aprovou promover a padrão (2026-07-04)** — feito, ver Bloco 1.
- [x] Specs Draft (gesture-tour, native-voice-models, vision-capture, keyflow-api, monetization,
      theme-system) — **Yan confirmou (2026-07-04)** que validou cada uma no momento em que as criou;
      inclui as ⚠️ S3/S4/S5 do theme-system.

## Bloco 1 — Pendências rápidas (quick mode, `.specs/quick/`)

Pequenas, sem spec própria; fazer em quick mode (TASK.md + SUMMARY.md + commit):

- [x] **Q1 — Duração máxima de gravação de verdade**: CONCLUÍDA (2026-07-02). `Settings.maxRecordingSec`
      agora é aplicado de verdade — ao atingir o limite, `OverlayCoordinator` pausa e mostra banner
      "Enviar/Descartar" (2 ações, decisão do Yan). Escopo real foi maior que "quick" (job agendável +
      `ButtonStateMachine.onDiscard()` + wiring no `OverlayService` + texto na `VozPanel`); registrado em
      `.specs/quick/001-limite-max-gravacao/TASK.md`. Gate `full`: unit verde; instrumentado só com as
      falhas ambientais já conhecidas (`SystemSurfacesScreenshotTest`) + 1 flake confirmado
      (`KeyFlowKeyboardBarsTest.gridReleaseOutsideTheFrameCancelsWithoutEmitting`, passou 6/6 no rerun
      isolado — instabilidade de toque sintético sob carga, não regressão). Pendente **[YAN]**: validar
      o banner no Galaxy A32.
- [x] **Q2 — Margem lateral dos menus**: CONCLUÍDA (2026-07-02). `ConfigScreen.kt` — padding
      horizontal da `TabBar` e do corpo do painel 12dp → 16dp. Goldens `config-*` (exceto
      `config-teclado`) regenerados e aprovados seletivamente (visual conferido, sem corte/
      sobreposição). `config-teclado`/`config-teclado-dark` **não** aprovados aqui — já carregam
      uma mudança visual anterior (dígitos/Milestone 5) pendente de revisão do Yan (ver STATE.md).
      **Achado novo, fora do escopo desta task**: `keyboard-canvas-expanded-dark`/`-symbols-dark` e
      `onboarding-dark` também aparecem com diff grande no `diff-screens.sh`, sem relação com esta
      mudança (não tocamos nesses arquivos) — provavelmente a mesma leva de mudanças visuais não
      aprovadas do Milestone 5. Não aprovados; registrado para o Yan decidir quando revisar aquele
      lote. Prints espelhados em `/tmp/keyflow-prints/`.
- [x] **Q3 — Bug do Termius**: DIAGNOSTICADO e CORRIGIDO (2026-07-02). Causa raiz: **não era o
      `PhysicalKeyEmitter`/atalhos** — era a composição de texto do corretor (`setComposingText` por
      letra, grade Canvas) invisível em campos `TYPE_NULL` (terminal/raw-input, ex.: Termux/Termius —
      confirmado por Yan: texto selecionável + pinça de zoom, típico de terminal em WebView). O dado
      chegava (Enter enviava o texto certo), só não aparecia enquanto digitava. Fix: `handleKey()` agora
      comita cada letra direto (`commitText`) em campos `TYPE_NULL`, pulando a composição/corretor (função
      pura `KeyFlowKeyboardService.shouldCompose`, testada em unit). Ver `.specs/quick/003-bug-termius/`.
      **Pendente [YAN]**: confirmar no Termius de verdade (a causa raiz foi confirmada só por inferência
      + reprodução manual num `EditText` de debug, não no app real).
- [x] **Q4 — Promover grade Canvas a padrão**: CONCLUÍDA (2026-07-04). Flag `kbd_canvas`/
      `useCanvasGrid` removida por completo (`KeyFlowKeyboardService.kt`, `KeyFlowKeyboardView.kt` —
      teclado antigo inteiro deletado —, `TecladoPanel.kt`, `RouteCatalog.kt`). Goldens: 6 PNGs do
      teclado antigo removidos; `keyboard-canvas-*` e `config-modo[-dark]` regenerados/aprovados.
      Fecha KC-T10 no `keyboard-canvas/tasks.md`. Gate: 241 unit + instrumentado `ime` 28/29 (1 falha
      ambiental conhecida).

### Q5..Q8 — ajustes pedidos pelo Yan usando o app no A32 (2026-07-06..09-07)

- [x] **Q8 — Modal do editor de grade + picker sem rolagem** (`.specs/quick/008-modal-grade-picker/`).
      Junto vieram: dimensionamento de grade/barras/QWERTY pelo espaço real em paisagem
      (`GridSizing`), os modais do editor de barras e o `+` fora da faixa rolável.
      **Emenda**: crash em loop das prefs cifradas depois de restore de backup (`SecurePrefs` +
      regras de backup) — achado ao reinstalar o app durante os testes.
      Pendente **[YAN]**: goldens das rotas novas (`grids-editor-modal`, `bars-editor`) não foram
      gerados — dependem da revisão do lote de goldens abaixo.

- [x] **Q9 — Sugestões do corretor** (`.specs/quick/009-sugestoes-nao-funcionam/`): FEITA (2026-09-28,
      PR #5). Sondado no A32: o corretor do sistema (o do Samsung) responde "palavra certa, sem
      sugestões" para tudo — o `SystemSpellChecker` saiu. Dicionário próprio (listas do AOSP LatinIME,
      `scripts/build-dictionaries.py`), sugestões em etiquetas, troca no espaço com interruptor na aba
      Teclado e backspace que desfaz. Ainda fora: sugestões para o que entra por teclado físico e voz.

- [x] **Q10 — A suíte instrumentada no aparelho do Yan** (`.specs/quick/010-suite-no-aparelho/`).
      As 12 falhas do A32 no gate de 07/09 não eram do IME "não grudar": `connectedAndroidTest`
      desinstala o app no fim, e a grade Canvas não é medida com o teclado **recolhido** (estado que o
      IME persiste). `UserSettingsGuard` passou a preservar também esse estado, e `ime reset` saiu dos
      testes — era ele que devolvia o aparelho do Yan ao teclado da Samsung.
      Pendente **[YAN]**: nenhuma barra da config dele declara o slot `Candidates`, então a faixa de
      sugestões não tem onde aparecer — adicionar "Candidatos" (`abc`) em Config → Barras para ver.
      Junto vieram: **bug de produção** — `PermissionsChecker` lia `ENABLED_INPUT_METHODS`, bloqueado
      no Android 14+ para `targetSdk >= 34`, então o app dizia "teclado não habilitado" em TODO aparelho
      14+ (agora lê pelo `InputMethodManager`); o `SystemSurfacesScreenshotTest` subia o `OverlayService`
      por uma intent sem action e derrubava o processo de teste; e mais dois testes que assumiam aparelho
      zerado (`PermissionsCheckerTest`, `DocsPanelTest`). Suíte completa: 156/156 no A32 e no emulador.
      Criado um 3º gate — emulador **Android 17 (API 37, páginas de 16 KB)** — que revelou libs de
      instrumentação velhas demais para a API 37 (atualizadas). Pendente **[YAN]**: no Android 17 a
      plataforma não exibe a janela do IME em contexto instrumentado, então os 27 testes de teclado não
      rodam lá (o app funciona; é harness) — **conclusão errada, corrigida na Q11**. Continua em
      `.specs/quick/011-target-36-e-harness-a17/`.

- [x] **Q11 — targetSdk 36 + teclado no Android 17** (`.specs/quick/011-target-36-e-harness-a17/`).
      RESOLVIDA (2026-09-09). O target 36 foi commitado em `9bc8a96` (ver PS-1). As 27 falhas do
      teclado no A17 não eram do harness: o emulador se declara com teclado físico e, com
      `show_ime_with_hard_keyboard = 0`, o `InputMethodService` padrão nunca mostra o teclado — nem
      manualmente (medido por `screencap`). O `UserSettingsGuard` passou a ligar a chave nos testes e
      restaurar o valor da pessoa, e o produto ganhou a opção "Mostrar com teclado físico" na aba
      Teclado (ligada por padrão). A17 fechou 163/163.

## Bloco 2 — Fechar `shortcut-bars` (Milestone 6)

Spec: `.specs/features/shortcut-bars/` — seguir o `tasks.md` de lá:

- [x] **SB-T8** — `PhysicalKeyPicker` (commit `29983a2`). Deixou de ser bottom sheet no Q8: virou
      `ConfigModal`, e o teclado ANSI escala por linha em vez de rolar.
- [x] **SB-T9** — Editores WYSIWYG de grades + barras no `TecladoPanel` (commit `09487bf`).
- [x] **SB-T10** — a11y e goldens automatizáveis ✅ (commits `00846e0`/`1a1a339`); promoção a padrão
      e **remoção da barra antiga** ✅ (2026-09-07, commits `b0afdcb`/`0cfbf9f` — a flag `kbd_bars`
      não existe mais). Manual no A32 **confirmado pelo Yan em 2026-09-09**. Milestone 6 fechado.

## Bloco 3 — `gesture-tour` (prévia de gestos + Docs + apresentação)

Spec: `.specs/features/gesture-tour/`. Ordem sequencial do `tasks.md`:
GT-T1 → T2 → T3 → T4 → T5 → T6 → T7 → T9 → T10 → T8 (fecho c/ goldens + **[YAN]** A32).

**Status (2026-09-24)**: GT-T1..T10 feitas (PR #1); falta só o manual do GT-T8 no A32 **[YAN]**.

- Cobre também os itens do `TODO`: índice/feedback na Docs, "?" por permissão na apresentação,
  gif de dedo na aba Modo.

## Bloco 4 — `native-voice-models` (STT offline + transcrição parcial)

Spec: `.specs/features/native-voice-models/`. Ordem sequencial do `tasks.md`:
NM-T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T14 → T15 → T16 → T17 → T18 → T12 →
T13 (**[YAN]** validação A32 + medições).

**Status (2026-09-27)**: código completo (PR #3), CI verde em `5db6d75`. Faltam só os roteiros
manuais da T18 e da T13 no A32 **[YAN]** — `.specs/features/native-voice-models/manual-validation.md`.

- Atenção: NM-T12 escreve regras de backup que a monetization (MZ-T1) também toca — quem chegar
  segundo reconcilia (identidade INCLUÍDA, `models/` EXCLUÍDO).

## Bloco 5 — `vision-capture` (copiar texto/imagem/tradução da tela)

Spec: `.specs/features/vision-capture/`. Ordem sequencial do `tasks.md`:
VC-T1 → T2 → ... → T13 → T14 (**[YAN]** validação A32).

**Status (2026-09-27)**: nada implementado — é o próximo bloco da Frente A.

## Bloco 6 — `keyflow-api` (Rust, pasta `api/` do monorepo) — Milestone 7, parte 1

Spec: `.specs/features/keyflow-api/` (fica onde está; **API-T1 cria a pasta `api/`** na raiz).
Ordem: API-T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T12 → T13.

**Status (2026-09-27)**: API-T1..T12 feitas (2026-07-02/03) e T13 parcial (Dockerfile + guia
`api/docs/deploy.md`), **na `main` desde 2026-09-28** (PR #4; a branch ficou só local até 27/09 —
a regra "merge a cada task" não foi seguida). Gate `rust` verde no CI (111 testes). Parada no gate
**[YAN]** da T13 (checklist no fim do `deploy.md`).

- Gate próprio (rodar dentro de `api/`): `cargo fmt --check && cargo clippy --all-targets -- -D
  warnings && cargo test` (+ Postgres do `api/docker-compose.yml`). Sem device Android; **não
  tocar em `app/`, goldens ou STATE.md**.
- **[YAN]** na API-T13: credenciais reais (service account do Play, chave de provedor,
  domínio/TLS). API-T14 (e2e) só fecha junto com MZ-T15 no Bloco 7.
- **Pode rodar em paralelo com os Blocos 3–5**: `api/` é disjunta do Android (o Gradle não a
  enxerga). Com dois agentes, a frente da API trabalha numa **worktree própria** (ver seção
  multi-agente); senão, nesta posição da fila.

## Bloco 7 — `monetization` (app) — Milestone 7, parte 2

Spec: `.specs/features/monetization/`. **Pré-requisito: API em staging (API-T13).** Ordem:
MZ-T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T12 → T13 → T14 → T15.

- **[YAN]**: produtos no Play Console + license testers (antes de MZ-T3 testar de verdade);
  conta/unidades AdMob + SSV (antes de MZ-T10/T12 saírem dos test ads); MZ-T15 = e2e com compras
  de teste no device (junto com API-T14).
- MZ-T1..T2, T5 e T11 (lógica pura) podem começar antes do staging existir.

## Bloco 8 — `theme-system` — Milestone 8

Spec: `.specs/features/theme-system/`. Ordem:
TS-T1 → T2 → T3 → T4 → T5 → T6(⚠️) → T7 → T8(⚠️) → T9(⚠️) → T10 → T11 → T12 → T13 → T14 (**[YAN]**).

- **⚠️ Checkpoints**: validar S3/S4 com o Yan antes de TS-T6/T9 e S5 antes de TS-T8 (estão nas
  tasks). Se ele estiver AFK, implementar a assunção recomendada e marcar pra revisão.
- Depois da monetization, o gating usa o `EntitlementsProvider` real; antes dela, stub bloqueado
  (o design cobre os dois casos — a ordem recomendada aqui evita o stub).
- TS-T1..T5 são independentes da monetization e podem ser adiantadas se o Bloco 7 travar em
  credenciais do Yan.

## Bloco 9 — Publicação na Play Store

Base: `PLAYSTORE_CHECKLIST.md` (raiz). **Desde 2026-10-07 estes itens são executados pelo recorte da
v1** (topo deste arquivo): PS-5 → V1-3, PS-2 → V1-5, PS-3 → V1-6, PS-4/PS-6 → V1-7, PS-7 → V1-8. Na v1
não há anúncios nem compras — o que abaixo cita monetização/AdMob entra na v1.1.

- [x] **PS-1 — targetSdk 36**: FEITO em 2026-09-09 (`9bc8a96`). `compileSdk`/`targetSdk` 36, sem
      mudança de código necessária; 252 unitários, 163/163 no A32 e 163/163 no emulador A17.
      `PLAYSTORE_CHECKLIST.md` já marca o bloqueante 0 como resolvido. Falta só **[YAN]**: o passe
      manual usando o app no A32 — **confirmado pelo Yan em 2026-09-09**, funcionando normal.
- [ ] **PS-2 — Política de privacidade pública**: redigir (conteúdo mínimo do checklist: áudio
      sob demanda, endpoint do usuário/proxy, sem keystrokes; agora também: créditos/identidade
      anônima [monetization], modelos locais [native-voice-models], captura de tela efêmera
      [vision-capture], AdMob) + publicar em URL permanente **[YAN decide onde]** + link dentro
      do app (aba Docs/Sistema).
- [ ] **PS-3 — Prominent disclosure**: tela de consentimento explícito no onboarding (texto do
      checklist + botão de aceite; o "?" por permissão do gesture-tour complementa, não
      substitui). Instrumented + golden.
- [ ] **PS-4 — Data Safety Form**: preencher no Play Console **[YAN]** com a tabela do checklist
      (+ ads/purchases/device IDs da monetização — o agente prepara o rascunho campo a campo).
- [ ] **PS-5 — Build de release**: keystore de assinatura **[YAN]**, `minifyEnabled`/R8 (conferir
      keep rules p/ AIDL/sherpa/Billing), AAB, `versionCode/Name`, smoke do release no A32.
- [ ] **PS-6 — Listing**: screenshots (overlay + teclado em uso — reusar galeria), descrição
      ("teclado de voz", "transcrição sob demanda"), categoria Productivity/Tools **[YAN aprova
      textos]**.
- [ ] **PS-7 — Internal testing → produção**: subir AAB na trilha interna, rodar o e2e completo
      de compras/ads em faixa de teste, corrigir o que a revisão apontar, promover **[YAN]**.

## Bloco 10 — `reasoning-models` (aba Raciocínio) — fora da fila original

Spec: `.specs/features/reasoning-models/` (`spec.md` 45 requisitos, `design.md`, `tasks.md`, e `openai-siwc-notes.md` com o
texto literal da doc da OpenAI). Ordem: RSN-T0 → T1 → … → T19 (uma por vez, um commit por task).

- **Escopo**: só CONECTAR modelos de linguagem + botão Testar. Quem consome o raciocínio (limpar ditado, comandos, resumo)
  é feature futura. Origens: **Local** (Qwen3 0.6B e Gemma 4 E2B pelo LiteRT-LM, processo `:reasoning`) e **OpenAI**
  (chave de API ou login "Continue with ChatGPT"). Anthropic fica de fora: a política dela proíbe o login com assinatura em
  produto de terceiros — entra depois **só por chave de API**.
- **O login mora em `auth/`** e é trocável: o núcleo não conhece UI, `Settings` nem provedor; cada provedor é uma pasta em
  `auth/providers/` mais uma linha no registro (`AuthProviders`). O `AuthIsolationTest` confere isso lendo os imports.
- **Independe dos outros blocos** (só reusa o downloader/`ModelStore` do Bloco 4). Entra no build de release da PS-5: se o
  login da OpenAI for ligado, a decisão de elegibilidade abaixo precisa estar tomada antes.
- **[YAN]**: (1) tornar o repositório público — o caminho aberto da OpenAI (registro dinâmico, sem client ID a pedir) parte
  dessa premissa; (2) **decidir a elegibilidade** (RPRV-04): o KeyFlow é open source e local, mas planeja créditos, skins
  pagas e anúncios — `OpenAiAuthConfig.enabled = BuildConfig.DEBUG` mantém o login desligado no release até lá (a chave de
  API funciona sempre); (3) rodar o roteiro `manual-validation.md` no A32 (login real com uma conta de plano, o Gemma/Qwen no
  aparelho e o consumo de RAM).
- **Não faz parte do bloco**: a aceitação REAL do registro dinâmico pela OpenAI não é provada por teste nenhum daqui — só a conta
  de plano no aparelho prova (RSN-T19).

---

## Resumo da fila (visão de uma linha)

```
feito: Q1..Q11 → SB → GT → NM → RSN → SKP → AGT fase 1 (faltam só manuais [YAN])
agora: V1-1..V1-8 → v1 na Play Store (V1-0, PR #7, mesclada em 2026-10-10)
em paralelo: API-T13 (staging) → [conta Play + service account] → v1.1 = MZ-T1..T15 + API-T14
depois: VC (visão) · TS (temas, spec a revisar) · AGT fases 2/3 · Fala/Fluxos/Computador (sem spec)
```

**Posição atual (2026-10-07)**: o próximo marco é a **v1** (recorte no topo deste arquivo). A PR #7
está no CI; depois dela vem a V1-1. A API sai do gate **[YAN]** da API-T13 em paralelo: o staging sobe
sem as credenciais do Google (`INTEGRITY_RELAXED=true`), e a service account entra quando o app existir
no Play Console.

> Até 2026-09-28 a fila era GT → NM → VC → API → MZ → TS → PS, com a loja no fim. O recorte da v1
> (2026-10-07) puxou a loja para a frente; o racional abaixo vale para a ordem **depois** da v1.

**Racional da ordem**: primeiro fechar o que está em voo (barras) e o que não depende de nada
externo (tour/Docs — também é pré-requisito de conteúdo da loja); depois as features grandes de
produto (modelos locais, visão) que só dependem do app; então a cadeia com dependências externas
(API → monetização → temas, que consome `skinsUnlocked`); a loja por último porque depende de
monetização (billing/data safety) e de tudo estar estável. Trocas seguras: VC↔NM (independentes);
API pode andar em paralelo desde o Bloco 3; TS-T1..T5 podem adiantar. **Não** inverta MZ↔API nem
TS-T9↔MZ sem decisão do Yan.

---

## Execução paralela (multi-agente)

O paralelismo aqui é limitado por **recursos físicos compartilhados**, não por código:

1. **O emulador/device é UM.** Gates `full`/`screenshot` não rodam em paralelo (TESTING.md:
   instrumented não é parallel-safe — device, IME ativo e permissões são estado compartilhado).
   Não subir um 2º emulador neste host (Keystore já é patológico sob carga).
2. **`verification/golden/` é binário e compartilhado** — só UMA frente pode regenerar/aprovar
   goldens. PNG não tem merge.
3. **`GRADLE_USER_HOME` compartilhado** tem contenção de lock — evitar 2 builds Android
   simultâneos.
4. **`.specs/project/STATE.md` é escrito só pela Frente A** (arquivo mais quente do repo; a
   Frente B registra progresso no Status do `tasks.md` da keyflow-api e no `api/README.md`).

### Divisão recomendada: 2 frentes (modelo: Sonnet 5)

- **Frente A — Android** (este repo; worktree opcional): executa a fila inteira MENOS o Bloco 6.
  Dona exclusiva do emulador, dos goldens e do STATE.md.
- **Frente B — keyflow-api** (mesmo repo, **worktree própria**, tocando SÓ `api/`,
  `.github/workflows/api.yml`, `.gitignore` e o Status do `tasks.md` da feature): Bloco 6
  completo. Pastas disjuntas do Android → merge pro master trivial e frequente (a cada task).
- **Sincronização**: Bloco 7 (MZ) precisa da API em staging (API-T13); MZ-T1/T2/T5/T11 (lógica
  pura) podem adiantar sem staging.
- **Frente C (opcional, ganho modesto)**: worktree Android fazendo APENAS tasks unit-puras de
  gate `quick` sem goldens (TS-T1, TS-T5, GT-T1 — arquivos novos, conflito baixo). Merge revisado
  pelo Yan ANTES de a Frente A entrar nos mesmos pacotes. Não dar a esta frente nenhuma task com
  gate `full`/`screenshot`.

### Tasks espinhosas (atenção extra)

NM-T8 (AIDL + processo `:inference`), TS-T4 (migração de tokens com goldens bit-idênticos) e
API-T11 (proxy streaming). Regra para qualquer frente: **travou após 2 tentativas honestas →
registrar o estado no STATE.md (ou README da API) e parar** — não insistir às cegas nem
"contornar" desviando da spec em silêncio.

### Prompts de kickoff (colar no agente novo)

**Frente A (Android):**
```
Leia /home/yan/Projects/keyflow/.specs/DEVELOPMENT-ORDER.md e execute a fila a partir do
Bloco 1, uma task por vez, seguindo as Regras de Ouro do arquivo. Você é a ÚNICA frente com
direito ao emulador, aos goldens (verification/golden/) e ao STATE.md. NÃO execute o Bloco 6
(keyflow-api — é de outro agente). Em todo item marcado [YAN], prepare o que der, avise e siga
para a próxima task que não dependa dele.
```

**Frente B (API Rust, worktree do monorepo):**
```
Implemente a KeyFlow API seguindo .specs/features/keyflow-api/ (spec.md, design.md, tasks.md)
deste repositório, começando pela API-T1: criar a pasta api/ na raiz do monorepo (cargo init)
— a spec fica onde está. Trabalhe numa worktree própria e toque APENAS api/,
.github/workflows/api.yml, .gitignore e o Status do tasks.md da feature — NUNCA app/, goldens
ou .specs/project/STATE.md (são de outra frente). Gate, rodando dentro de api/: cargo fmt
--check && cargo clippy --all-targets -- -D warnings && cargo test (integração usa o Postgres
do api/docker-compose.yml). Nunca fabrique formato de API do Google — confira a doc oficial e
fixe em wiremock. Faça merge pro master a cada task concluída. Pare nos itens [YAN]
(credenciais/deploy) e documente o que ficou pendente.
```
