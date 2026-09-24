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

## Estado atual (2026-09-09)

| Milestone | Feature | Status |
| --------- | ------- | ------ |
| M0–M2 | PoC, MVP `voice-keyboard`, `redesign-atelie` | ✅ concluídos |
| M4 | `orb-gestures` (reconciliação) | ✅ concluído |
| M5 | `keyboard-canvas` | ✅ concluído (KC-T1..T10, grade Canvas promovida a padrão, teclado antigo removido) |
| M6 | `shortcut-bars` | ✅ concluído (SB-T1..T10; manual do Yan no A32 confirmado em 2026-09-09) |
| — | Ajustes pós-uso no A32 (Q8 + emendas) | ✅ dimensionamento em paisagem, modais dos editores, crash das prefs cifradas — ver Bloco 1 |
| — | `gesture-tour` | GT-T1..T10 feitos (2026-09-24); falta só o manual do GT-T8 no A32 **[YAN]** |
| — | `native-voice-models` | em execução — NM-T1 (AAR sherpa-onnx) feito (2026-09-24); próxima NM-T2 |
| — | `vision-capture` | spec/design/tasks prontos (Draft) — nada implementado |
| M7 | `keyflow-api` + `monetization` | specs/designs/tasks prontos (Draft) — nada implementado |
| M8 | `theme-system` | spec/design/tasks prontos (Draft; ⚠️ S3/S4/S5 a validar) |
| — | Play Store | `PLAYSTORE_CHECKLIST.md`; **PS-1 (targetSdk 36) feito**, PS-2..PS-7 abertos |

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

- [ ] **Q9 — Sugestões do corretor nunca funcionaram** (`.specs/quick/009-sugestoes-nao-funcionam/`).
      A faixa está pronta e rolável; falta produzir sugestão: só a grade do KeyFlow alimenta o
      corretor (teclado físico e voz não), e o `SystemSpellChecker` nunca foi visto respondendo no
      A32. Registrado a pedido do Yan para depois.

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
      rodam lá (o app funciona; é harness). Continua em `.specs/quick/011-target-36-e-harness-a17/`.

- [ ] **Q11 — targetSdk 36 + harness de IME no Android 17** (`.specs/quick/011-target-36-e-harness-a17/`).
      ⚠️ `app/build.gradle.kts` está com `compileSdk`/`targetSdk` **36 NÃO COMMITADOS** — spike validado
      (A32 159/159, API 34 159/159, build limpo), decidir entre commitar ou reverter. O prazo da Play
      VENCEU em 31/08/2026 (exige >= 36 para publicar e atualizar; extensão até 01/11). Bloqueio aberto:
      sob instrumentação no Android 17 a janela do teclado não abre (task sem foco) — 3 hipóteses já
      refutadas, documentadas lá para não se repetirem.

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

- Cobre também os itens do `TODO`: índice/feedback na Docs, "?" por permissão na apresentação,
  gif de dedo na aba Modo.

## Bloco 4 — `native-voice-models` (STT offline + transcrição parcial)

Spec: `.specs/features/native-voice-models/`. Ordem sequencial do `tasks.md`:
NM-T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T14 → T15 → T16 → T17 → T18 → T12 →
T13 (**[YAN]** validação A32 + medições).

- Atenção: NM-T12 escreve regras de backup que a monetization (MZ-T1) também toca — quem chegar
  segundo reconcilia (identidade INCLUÍDA, `models/` EXCLUÍDO).

## Bloco 5 — `vision-capture` (copiar texto/imagem/tradução da tela)

Spec: `.specs/features/vision-capture/`. Ordem sequencial do `tasks.md`:
VC-T1 → T2 → ... → T13 → T14 (**[YAN]** validação A32).

## Bloco 6 — `keyflow-api` (Rust, pasta `api/` do monorepo) — Milestone 7, parte 1

Spec: `.specs/features/keyflow-api/` (fica onde está; **API-T1 cria a pasta `api/`** na raiz).
Ordem: API-T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T12 → T13.

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

Base: `PLAYSTORE_CHECKLIST.md` (raiz). Executar como quick tasks / tasks avulsas:

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

---

## Resumo da fila (visão de uma linha)

```
[YAN gates] → Q1..Q4 → SB-T8..T10 → GT (tour/Docs) → NM (modelos locais+parcial) → VC (visão)
→ API (Rust, pode paralelizar) → MZ (monetização) → TS (temas, ⚠️ checkpoints) → PS-1..PS-7 (loja)
```

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
