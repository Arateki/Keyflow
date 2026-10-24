# Vision Capture — Tasks

**Spec**: `spec.md` · **Design**: `design.md` · **Contexto**: `context.md` (V1–V6)
**Status**: Em implementação na branch `equipe/visao` (2026-10-24) — VC-T1 feita; as demais seguem a ordem
abaixo. Decidido: 3 direções + tap na orbe = cancelar/retorno à gravação; propostas ⚠️ restantes: toast sem
input p/ imagem; V3/V6. A aba Visão que já existe no app (origem Créditos/Local/OpenAI/…) contradiz a
spec: ver `STATE.md` › "vision-capture — aba Visão existente × spec" (a VC-T11 a reconcilia).

> Gate no sandbox: `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon <task>`
> Instrumentado escopado: `ANDROID_SERIAL=emulator-5554`. Antes de instalar no device do Yan:
> **build completo** (`installDebug`/`assembleDebug`), nunca só `compileDebugKotlin`.
> Consentimento de captura em teste = UiAutomator best-effort; senão manual (design §Riscos).
> Pacote novo: `vision/` (fluxo/captura/engines/overlays) + aba em `ui/config/`.

## Execution Plan

```
Fase A (fundação):    VC-T1 ──┐          VC-T2 ──┐
Fase B (fluxo):       VC-T1 → VC-T3 → VC-T4 → VC-T5
Fase C (engines):     VC-T2 → VC-T6 [P-unit]
Fase D (ações):       (T4,T5,T6) → VC-T7 → VC-T8
Fase E (tradução):    (T2,T7) → VC-T9
Fase F (cópia):       (T7,T9) → VC-T10
Fase G (config/UI):   (T1,T9) → VC-T11 → VC-T12
Fase H (loja/valid.): (T3,T8) → VC-T13;  (T10,T11,T12,T13) → VC-T14
```
Ordem prática sequencial: T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T12 → T13 → T14.
**`[P]`**: partes unit de T6 podem andar em paralelo com a Fase B; o resto serializa no device
(TESTING.md).

---

## Fase A — Fundação

### VC-T1 — `OrbShortcutAction.VISION_MODE` + Settings ✅ CONCLUÍDA (2026-10-24)
- **What**: novo caso no enum (+ rótulo/descrição na `ShortcutActionSheet`/aba Sobrepor);
  `Settings` += `visionTranslateTarget: String = "pt"`. (A exceção de gating — atalho válido
  durante gravação — é da VC-T4, não daqui.)
- **Where**: `core/Models.kt`, `data/Settings.kt`, `data/SettingsRepository.kt`,
  `ui/config/ShortcutActionSheet.kt`.
- **Depends on**: —. **Requirement**: MODE-01.
- **Done when**: unit de `resolveShortcut`/labels com o caso novo; instrumented do repo persiste
  o campo novo; suíte existente verde.
- **Tests**: unit + instrumented (camada `data`). **Gate**: `full`.
- **Feito**: `VISION_MODE` no enum (`core/Models.kt`), rótulo/descrição/linha na `ShortcutActionSheet` (PT/EN/ES), ramo
  inerte no `OverlayService.handleOrbShortcut` até a VC-T4. **`Settings.visionTranslateTarget` já existia** (o painel Visão
  anterior o criou, com persistência no `SettingsRepository`): nada a acrescentar, só teste — `SettingsShortcutTest`
  (VISION_MODE nas 3 rajadas, overflow, padrão `"pt"`), `ShortcutActionTextTest`, roteiro do tour
  (`GestureScriptCatalogTest`), `SettingsRepositoryTest` (persistência do atalho e do destino; valor desconhecido →
  padrão) e `ShortcutActionSheetTest` (instrumentado, Compose).

### VC-T2 — Dependências ML Kit no build
- **What**: `com.google.mlkit:text-recognition` (bundled), `com.google.mlkit:translate`,
  `com.google.mlkit:language-id`; delta de tamanho do APK registrado; termos/atribuições
  levantados p/ a Docs (STO-V03 parcial).
- **Where**: `app/build.gradle.kts`.
- **Depends on**: —. **Requirement**: STO-V03 (parcial).
- **Done when**: `assembleDebug` verde; delta anotado.
- **Tests**: none (deps de build). **Gate**: `build`.

## Fase B — Fluxo efêmero

### VC-T3 — Consent + FGS type `mediaProjection` por acionamento
- **What**: `VisionConsentActivity` transparente (`createScreenCaptureIntent` → resultado);
  no `OverlayService`: consent OK → re-`startForeground` com `MICROPHONE or MEDIA_PROJECTION`
  (**ordem Android 14+**, guardas p/ minSdk 24) → `getMediaProjection` + callback de revogação;
  fim do fluxo → re-`startForeground` só `MICROPHONE`. Negado → callback de aborto (MODE-06).
  Manifest: permissão + type.
- **Where**: `vision/VisionConsentActivity.kt`, `overlay/OverlayService.kt`, `AndroidManifest.xml`.
- **Depends on**: VC-T1. **Requirement**: CAP-01, MODE-06 (parcial), STO-V01 (parcial).
- **Done when**: unit das decisões (negado/revogado) com fakes; instrumented: consent via
  UiAutomator (best-effort no emulador) → sessão ativa e type somado/removido sem
  `SecurityException`; **manual**: conceder/negar no device.
- **Tests**: unit + instrumented (camada `overlay/service`) + manual. **Gate**: `full` + manual.

### VC-T4 — `VisionFlow` (orquestrador) + pause/restauração + `ScreenFrameGrabber`
- **What**: máquina efêmera `VisionFlowState {NONE, CONSENT, SELECTING, ACTIONS, COPYING}`
  sobreposta ao `ButtonState` (nunca o altera além do PAUSE): atalho em RECORDING →
  `onIntent(PAUSE)` e abre (MODE-02; TOGGLE e PTT); SENDING/CANCELLING → ignora (MODE-03);
  qualquer saída converge no epílogo único (parar projeção → remover type → limpar
  `cacheDir/vision/` → restaurar IDLE/PAUSED — MODE-04, CAP-04). Orbe renderiza glifo/accent por
  fase (MODE-05, token de paleta claro/escuro). `ScreenFrameGrabber`: `VirtualDisplay` +
  `ImageReader` → frame com UI própria oculta → recorte pelo `Rect` (CAP-03); frame preto
  (`FLAG_SECURE`) → aviso + epílogo.
- **Where**: `vision/VisionFlow.kt`, `vision/ScreenFrameGrabber.kt`,
  `overlay/OverlayCoordinator.kt`, `overlay/OrbButtonView.kt`, `ui/theme/Palette.kt`.
- **Depends on**: VC-T3.
- **Requirement**: MODE-02, MODE-03, MODE-04, MODE-05 (parcial), MODE-06, CAP-03 (parcial), CAP-04.
- **Done when**: unit da máquina cobre — pause automático (TOGGLE e PTT), ignore em
  SENDING/CANCELLING, restauração p/ IDLE e p/ PAUSED em TODAS as saídas (concluiu/cancelou/
  negou/revogou/erro), epílogo idempotente; instrumented: dirigir o `OverlayService` real via
  intents (padrão `OverlayServiceCancelTest`) — atalho durante gravação real pausa e restaura;
  captura de frame real = **manual** (exige projeção).
- **Tests**: unit + instrumented + manual. **Gate**: `full` + manual.

### VC-T5 — `RegionSelectView` (seleção sobre a tela viva)
- **What**: overlay fullscreen transparente sobre a tela real: retângulo com 4 handles + máscara
  escura fora + ⛶ tela-inteira + ✕ cancelar; geometria/hit-test/clamp puros
  (`RegionGeometry.kt`); confirmar → `Rect` de tela p/ o `VisionFlow`; rotação/resize → cancela
  com oferta de recomeço.
- **Where**: `vision/RegionSelectView.kt`, `vision/RegionGeometry.kt`.
- **Depends on**: VC-T4 (fiação; a View testa isolada).
- **Requirement**: CAP-02, CAP-03 (parcial).
- **Done when**: unit da geometria (arrasto de handles, limites, tela-inteira); instrumented:
  handles respondem, confirmar entrega o `Rect` esperado, ✕ aciona o cancelamento do fluxo.
- **Tests**: unit + instrumented. **Gate**: `full`.

## Fase C — OCR

### VC-T6 — `OcrEngine` (ML Kit) `[P-unit]`
- **What**: wrapper do Text Recognition v2 bundled; `recognize(bitmap)` → texto com quebras por
  blocos/linhas; vazio → `Empty` (OCR-02). Zero rede (OCR-03).
- **Where**: `vision/OcrEngine.kt`.
- **Depends on**: VC-T2. **Requirement**: OCR-01, OCR-02, OCR-03.
- **Done when**: unit da montagem de texto (fixture fake → quebras corretas); instrumented:
  bitmap gerado no teste (strings em `Canvas`) → extrai; em branco → `Empty`.
- **Tests**: unit + instrumented. **Gate**: `full`.

## Fase D — Ações por gesto

### VC-T7 — Fase de gestos (`VisionDragTargets`) + Copiar texto
- **What**: reusar o componente DragArrows (alvos/setas com ícones — dados, não fork), **3
  direções**: 🖼 imagem · 📋 texto · 🌐 traduzir; **tap na orbe = cancelar/retorno à gravação**
  (epílogo → MODE-04); orbe travada p/ reposição (MODE-05); soltar no alvo executa; fora de alvo
  → continua. **Copiar texto**: OCR (T6) → clipboard (ACT-02); `Empty` → aviso + epílogo
  (OCR-02). Ações imagem/traduzir chegam em T8/T9 (alvos presentes, fiação depois).
- **Where**: `overlay/DragArrowsView.kt` (modo de alvos), `vision/VisionFlow.kt`.
- **Depends on**: VC-T4, VC-T5, VC-T6.
- **Requirement**: ACT-01, ACT-02, MODE-05, OCR-02 (fluxo).
- **Done when**: unit do mapeamento direção→ação e do travamento; instrumented: arrasto sintético
  até cada alvo dispara a ação certa (padrão dos testes de gesto existentes); copiar texto com
  OCR fake põe no clipboard.
- **Tests**: unit + instrumented. **Gate**: `full`.

### VC-T8 — Copiar imagem (FileProvider + ClipData)
- **What**: bitmap da área → PNG em `cacheDir/vision/` → `FileProvider` escopado ao cache
  (manifest + `res/xml/file_paths`) → `ClipData.newUri` (ACT-04); limpeza no epílogo (CAP-04).
- **Where**: `vision/`, `AndroidManifest.xml`, `res/xml/`.
- **Depends on**: VC-T7. **Requirement**: ACT-04, CAP-04, STO-V02 (parcial).
- **Done when**: instrumented: ação gera arquivo + clipboard com URI de imagem legível; epílogo
  limpa `cacheDir/vision/`.
- **Tests**: instrumented. **Gate**: `full`.

## Fase E — Tradução

### VC-T9 — `TranslateEngine` + ação Traduzir
- **What**: Language ID (confiança baixa → aviso + original copiável — TRD-03) + `Translator`
  (origem→`visionTranslateTarget`); pacote ausente → oferta inline com ~tamanho → download →
  traduz → clipboard (ACT-03, TRD-02); falha de download → erro + oferecer copiar original;
  on-device (TRD-04).
- **Where**: `vision/TranslateEngine.kt`, `vision/VisionFlow.kt`.
- **Depends on**: VC-T2, VC-T7.
- **Requirement**: ACT-03, TRD-01, TRD-02 (fluxo), TRD-03, TRD-04.
- **Done when**: unit da orquestração com fakes (detect→pacote→traduz; detect falha; download
  falha); instrumented com pacote presente ou opt-in (`assume()`); download real = **manual**.
- **Tests**: unit + instrumented (+manual). **Gate**: `full` + manual.

## Fase F — Estado de "cópia"

### VC-T10 — `CopyToast` + `EphemeralInput`
- **What**: toast overlay (~3 s, padrão visual/timer do banner de desfazer atual, clock
  injetável): preview do texto ou "print copiado" (imagem sem input — proposta ⚠️); **tap na
  orbe** (ícone da ação) acelera; **tap no toast** → `EphemeralInput` (overlay focável próprio e
  efêmero — atenção ao `InputConnection` de terceiros, design §9): texto editável + Copiar
  (re-copia) + fechar. Só ao encerrar o COPYING roda o epílogo (ACT-06 → MODE-04).
- **Where**: `vision/CopyToast.kt`, `vision/EphemeralInput.kt`, `vision/VisionFlow.kt`.
- **Depends on**: VC-T7, VC-T9.
- **Requirement**: ACT-05, ACT-06, MODE-04 (liberação).
- **Done when**: unit do timer/aceleração; instrumented: toast aparece pós-cópia; tap na orbe
  encerra antes dos 3 s e restaura o estado; tap no toast abre o input; editar+Copiar atualiza o
  clipboard; fechar → epílogo; smoke de que um `EditText` de app externo (`ImeProbeActivity`)
  segue funcional após o input efêmero.
- **Tests**: unit + instrumented. **Gate**: `full`.

## Fase G — Config + goldens

### VC-T11 — Aba Visão (`ConfigTab.VISAO` + `VisaoPanel`)
- **What**: 6ª aba (gating padrão): dropdown idioma-destino (persiste), gerenciador de pacotes
  (`RemoteModelManager`: baixado/baixar/deletar + ~tamanho), `HelperNote` de privacidade, stub
  "Origem multimodal — em breve". TabBar com 6 abas (rolagem/compactação se apertar — validar
  visualmente).
- **Where**: `ui/config/ConfigTab.kt`, `TabBar.kt`, `VisaoPanel.kt`, `ConfigScreen.kt`.
- **Depends on**: VC-T1, VC-T9.
- **Requirement**: CFG-V01, CFG-V02, TRD-02 (gerência).
- **Done when**: instrumented Compose (fakes): destino persiste; estados dos pacotes; deletar
  volta a "baixar"; gating; suíte de config existente verde.
- **Tests**: unit (estado da lista) + instrumented (camada `ui`). **Gate**: `full`.

### VC-T12 — Rotas de galeria + goldens
- **What**: rotas `config-visao` / `config-visao-pacotes` (claro/escuro, fakes determinísticos);
  regenerar + aprovar goldens; espelhar em `/tmp/keyflow-prints/`.
- **Where**: `RouteCatalog.kt` (debug), `verification/golden/`.
- **Depends on**: VC-T11. **Requirement**: CFG-V03.
- **Done when**: `verify-screens.sh` + `diff-screens.sh` verdes (existentes bit-idênticos).
- **Tests**: screenshot. **Gate**: `screenshot`.

## Fase H — Loja + validação

### VC-T13 — Prontidão de loja (manifest/licenças/Docs)
- **What**: revisão final: permissão + FGS type `mediaProjection`, FileProvider escopado,
  atribuições ML Kit na Docs/licenças; smoke no emulador Android 14 (consent→type sem exceção).
- **Where**: `AndroidManifest.xml`, `ui/config/DocsPanel.kt`, `app/licenses/`.
- **Depends on**: VC-T3, VC-T8. **Requirement**: STO-V01, STO-V02, STO-V03.
- **Done when**: `build` verde; smoke ok; Docs cita ML Kit.
- **Tests**: none (config; smoke coberto). **Gate**: `build` + manual (smoke).

### VC-T14 — Validação manual no Galaxy A32 + traceability
- **What**: roteiro e2e: (a) ditando por Toggle → atalho → pausa → selecionar → copiar texto →
  toast → acelerar pela orbe → PAUSED restaurado → retomar e transcrever (clipboard sobrescrito —
  esperado); (b) idem em PTT; (c) traduzir EN→PT com download do pacote; (d) copiar print e colar
  em outro app; (e) input efêmero: editar e re-copiar; (f) negar consent / FLAG_SECURE / revogar
  no meio → aviso + estado restaurado; (g) **modo avião** (OCR + tradução com pacote). Atualizar
  traceability do `spec.md` → Verified.
- **Where**: device físico (RX8T10DERKW); `spec.md`.
- **Depends on**: VC-T10, VC-T11, VC-T12, VC-T13.
- **Requirement**: OCR-03/TRD-04 (modo avião), success criteria.
- **Done when**: roteiro completo documentado; gate `full` fresco verde; requisitos → Verified.
- **Tests**: manual. **Gate**: `full` + manual.

---

## Validações (pré-aprovação)

### Diagram ↔ Depends-on
| Task | Depends on (corpo) | Diagrama | ✓ |
| ---- | ------------------ | -------- | - |
| T1 | — | raiz A | ✅ |
| T2 | — | raiz A | ✅ |
| T3 | T1 | T1→T3 | ✅ |
| T4 | T3 | T3→T4 | ✅ |
| T5 | T4 | T4→T5 | ✅ |
| T6 | T2 | T2→T6 | ✅ |
| T7 | T4, T5, T6 | (T4,T5,T6)→T7 | ✅ |
| T8 | T7 | T7→T8 | ✅ |
| T9 | T2, T7 | (T2,T7)→T9 | ✅ |
| T10 | T7, T9 | (T7,T9)→T10 | ✅ |
| T11 | T1, T9 | (T1,T9)→T11 | ✅ |
| T12 | T11 | T11→T12 | ✅ |
| T13 | T3, T8 | (T3,T8)→T13 | ✅ |
| T14 | T10, T11, T12, T13 | →T14 | ✅ |

### Test co-location (matriz TESTING.md)
| Task | Camada | Matriz exige | Task diz | ✓ |
| ---- | ------ | ------------ | -------- | - |
| T1 | `data`/core | instrumented (+unit) | unit+instrumented | ✅ |
| T2 | build deps | — | none/build | ✅ |
| T3 | `overlay/service` | instrumented+manual | unit+instrumented+manual | ✅ |
| T4 | `overlay` service+lógica | instrumented+manual / unit | unit+instrumented+manual | ✅ |
| T5 | `overlay` view+lógica pura | instrumented / unit | unit+instrumented | ✅ |
| T6 | engine (lógica+device) | instrumented / unit | unit+instrumented | ✅ |
| T7 | `overlay` gesto+lógica | instrumented / unit | unit+instrumented | ✅ |
| T8 | `overlay`/files | instrumented | instrumented | ✅ |
| T9 | engine | instrumented / unit | unit+instrumented+manual | ✅ |
| T10 | `overlay`+IME-adjacente | instrumented (+manual no T14) | unit+instrumented | ✅ |
| T11 | `ui` | instrumented (Compose) | unit+instrumented | ✅ |
| T12 | screenshot | screenshot | screenshot | ✅ |
| T13 | manifest/docs | — | none (build+smoke) | ✅ |
| T14 | e2e device | manual | manual | ✅ |

**Saída**: fluxo Visão efêmero na orbe — pausa a ditação, seleciona área na tela viva, captura, e
por gesto de arrasto copia imagem/texto/tradução com estado de "cópia" manipulável — 100% no
aparelho, estado da orbe sempre restaurado, e a captura bruta pronta p/ a origem multimodal
(fase 2).
