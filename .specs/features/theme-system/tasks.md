# Theme System — Tasks

**Spec**: `spec.md` · **Design**: `design.md` · **Contexto**: `context.md` (S1–S7)
**Status**: Draft (aguardando aprovação do Yan)

> ⚠️ **Antes de TS-T6 e TS-T9, validar com o Yan as assunções S3/S4/S5** (gating das skins,
> recorte das variantes, efeitos no MVP) — mudam pouco código, mas mudam o que essas tasks fazem.
> Gate no sandbox: `GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon <task>`
> Instrumentado escopado: `ANDROID_SERIAL=emulator-5554`. Antes de instalar no device do Yan:
> **sempre `installDebug`/`assembleDebug`**, nunca só `compileDebugKotlin`.
> **Rede de segurança da migração**: TS-T3/T4 só fecham com `diff-screens.sh` provando goldens
> existentes **bit-idênticos** (Ateliê = visual atual). A fonte de verdade é
> `verification/golden/`, nunca o mirror em `/tmp`.

## Execution Plan

```
Fase A (tokens):     TS-T1 [P-unit] → TS-T2 → TS-T3 → TS-T4
Fase B (catálogo):   T1 → TS-T5 [P-unit];  (T5 + ⚠️S3/S4) → TS-T6
Fase C (efeitos):    (T3,T4) → TS-T7 → TS-T8 (⚠️S5)
Fase D (Sistema):    (T2,T6) → TS-T9 (⚠️S3);  T2 → TS-T10 → TS-T11 → TS-T12
Fase E (fecho):      (T8,T9,T12) → TS-T13 → TS-T14
```
Ordem prática sequencial: T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11 → T12 → T13 → T14.
**`[P]`**: T1/T5 são unit-puros; o resto serializa no device (TESTING.md).

---

## Fase A — Fundação de tokens

### TS-T1 — `ThemeSpec`/`ThemeRegistry` + Ateliê + espelho ARGB `[P]`
- **What**: tipos do design (§Fundação): `PaletteSource.Derive/Own`, `ThemeSpec`,
  `ThemeRegistry.resolve(themeId, dark) → ResolvedTheme(palette, argb, effects)`; entrada
  **Ateliê** (`Derive` sem overrides) reproduzindo `LightPalette`/`DarkPalette` atuais;
  `ArgbTokens` derivado mecanicamente (+ tokens hoje só nas views, inventariados do código); id
  desconhecido → Ateliê; `reduceEffects`/animator-scale → `EffectsSpec.NONE` no resolve.
- **Where**: `ui/theme/ThemeCatalog.kt`, `ui/theme/ArgbTokens.kt`; unit em `test/.../ui/theme/`.
- **Depends on**: —. **Covers**: TOK-01, TOK-04, THM-01 (resolve).
- **Done when**: unit — Ateliê claro/escuro == paletas atuais (igualdade estrutural campo a
  campo); Own vs Derive; fallback de id; ARGB consistente (`toArgb` de cada token); um tema fake
  registrado no teste resolve sem tocar em rendering (prova TOK-03 no nível do registro).
- **Tests**: unit. **Gate**: `quick`.

### TS-T2 — `Settings.themeId`/`reduceEffects` + leitura leve
- **What**: campos novos persistidos (`themeId: String = "atelie"`, `reduceEffects = false`) +
  `themeIdOnce()` (padrão `themeDarkOnce()` — só DataStore, sem EncryptedSharedPreferences no
  caminho do IME).
- **Where**: `data/Settings.kt`, `data/SettingsRepository.kt`.
- **Depends on**: TS-T1 (id default referencia o catálogo). **Covers**: SUR-04 (dados).
- **Done when**: instrumented do repo persiste/lê (padrão `SettingsRepositoryTest`); unit de
  default; suíte existente verde.
- **Tests**: unit + instrumented (camada `data`). **Gate**: `full`.

### TS-T3 — App Compose + notificação consomem o registro
- **What**: `SetupActivity`/`KeyFlowTheme` resolvem via registry (em vez de `paletteFor(dark)`
  direto); `RecordingNotification.build` recebe `ArgbTokens` (cores via `setColorFilter`/
  `setTextColor`, técnica atual). **Com Ateliê o visual é bit-idêntico.**
- **Where**: `ui/SetupActivity.kt`, `ui/theme/KeyFlowTheme.kt`, `overlay/RecordingNotification.kt`,
  `overlay/OverlayService.kt` (ponto de resolve).
- **Depends on**: TS-T1, TS-T2. **Covers**: SUR-01, TOK-02 (parcial).
- **Done when**: gate `screenshot` — goldens existentes das rotas de config/onboarding/notificação
  **bit-idênticos**; suíte instrumentada existente verde.
- **Tests**: instrumentados existentes + screenshot. **Gate**: `full` + `screenshot`.

### TS-T4 — Views do IME/overlay consomem `ArgbTokens`
- **What**: substituir as cores hardcoded por tokens em: `KeyboardGridView` (+preview/acentos),
  `KeyFlowKeyboardView` (tray/mic/faixas), `BarView` (+lente/grade), `CandidatesView`,
  `OrbButtonView`/`VoiceOrb` (acento/estados), `DragArrowsView`. Assinaturas passam a receber
  `ArgbTokens` resolvido no ponto de criação (service/overlay). **Nenhuma mudança visual com
  Ateliê.**
- **Where**: `ime/keyboard/`, `ime/bars/`, `ime/KeyFlowKeyboardView.kt`, `overlay/`,
  `ui/orb/VoiceOrb.kt`.
- **Depends on**: TS-T3. **Covers**: TOK-02, SUR-02/03 (tokens).
- **Done when**: gate `screenshot` — TODOS os goldens existentes bit-idênticos (é o gate da
  task); grep sem `Color.parseColor`/literais ARGB nas views migradas (exceções documentadas);
  suíte instrumentada existente verde.
- **Tests**: instrumentados existentes + screenshot. **Gate**: `full` + `screenshot`.

## Fase B — Catálogo

### TS-T5 — Variantes de acento (dados) `[P]`
- **What**: `atelie-azul/roxo/verde/ambar` como `Derive` com `AccentOverrides` (teal e derivados:
  seleção, chips, orbe, mic) — **só dados** (prova TOK-03 fim-a-fim).
- **Where**: `ui/theme/ThemeCatalog.kt`.
- **Depends on**: TS-T1 (pode andar antes de T3/T4; visível de verdade só depois delas).
- **Covers**: THM-02, TOK-03.
- **Done when**: unit — cada variante troca o acento e derivados, mantendo papel/tinta; nenhuma
  mudança fora do arquivo de catálogo (git diff da task é a prova).
- **Tests**: unit. **Gate**: `quick`.

### TS-T6 — Temas especiais (paletas; efeitos na T8) ⚠️ valida S3/S4 antes
- **What**: **checkpoint com o Yan das assunções S3/S4** (registrar em `context.md` a resposta);
  depois: Vaporwave/Glitch/Cyberpunk/Rosa como `Own` por modo (direções do S5; Cyberpunk pode
  dar `light == dark`), `locked` conforme decisão S3.
- **Where**: `ui/theme/ThemeCatalog.kt`, `context.md`.
- **Depends on**: TS-T5. **Covers**: THM-03 (paletas), TOK-04 (uso real do Own).
- **Done when**: unit — 4 temas resolvem nos 2 modos com paletas próprias; contraste mínimo
  sanity (tinta vs papel legível — asserção simples de luminância); decisão S3/S4 registrada.
- **Tests**: unit. **Gate**: `quick`.

## Fase C — Efeitos

### TS-T7 — `EffectsSpec` + runtime por superfície + "reduzir efeitos"
- **What**: tipos declarativos (app/orb/keyboard) + interpretadores: fundo do app (Compose,
  `LocalOrbClock`), camada de efeito do orbe, keypress/tray no teclado (ticker injetável da view,
  pré-alocação, invalidação por região — regras FX-03 do design). Toggle `reduceEffects` +
  animator-scale do sistema forçam NONE (FX-04). Nenhum tema usa ainda (specs NONE) — runtime
  entra inerte.
- **Where**: `ui/theme/Effects.kt`, `ui/` (fundo), `ui/orb/VoiceOrb.kt`,
  `ime/keyboard/KeyboardGridView.kt`.
- **Depends on**: TS-T3, TS-T4. **Covers**: FX-01, FX-02, FX-04.
- **Done when**: unit — resolve com reduce/animator-scale devolve NONE; instrumented — clock
  congelado → frame estável (rota de galeria provisória com um efeito de teste); goldens
  existentes seguem bit-idênticos (efeitos inertes).
- **Tests**: unit + instrumented + screenshot. **Gate**: `full` + `screenshot`.

### TS-T8 — Efeitos dos 4 especiais (com orçamento) ⚠️ valida S5 antes
- **What**: **checkpoint S5 com o Yan**; implementar os efeitos por tema/superfície (Vaporwave:
  gradiente/grid/glow; Glitch: jitter/scanlines/ruído ocasional; Cyberpunk: glow neon; Rosa: mais
  cor que efeito) dentro das regras de orçamento; degrade a estático por superfície quando não
  couber (FX-03).
- **Where**: `ui/theme/ThemeCatalog.kt` (specs), interpretadores da T7.
- **Depends on**: TS-T7, TS-T6. **Covers**: THM-03 (completo), FX-03 (implementação).
- **Done when**: unit dos specs; instrumented — cada tema com clock congelado rende frame
  determinístico; zero alocação em `onDraw` do teclado (verificação por code review documentado +
  strictmode no teste se viável); jank real é gate da T14.
- **Tests**: unit + instrumented. **Gate**: `full`.

## Fase D — Aba Sistema

### TS-T9 — `ConfigTab.SISTEMA` + seletor de temas ⚠️ depende da decisão S3
- **What**: aba nova (sem gating de modo) com seletor em grid: cards com **preview miniatura
  real** (mini-orbe + teclas + chip com a paleta resolvida, clock congelado), nome, cadeado nos
  `locked` sem `skinsUnlocked` (via `EntitlementsProvider` da monetization; **stub local
  `false`** se a monetization ainda não estiver no build) → tap bloqueado abre upsell
  (UnlockSheet real ou inerte conforme o build); seleção persiste `themeId`; toggle "reduzir
  efeitos"; seção "Skins" da aba Teclado vira atalho pra cá (THM-04).
- **Where**: `ui/config/ConfigTab.kt`, `TabBar.kt`, `ui/config/SistemaPanel.kt`,
  `ConfigScreen.kt`, `ui/config/TecladoPanel.kt`.
- **Depends on**: TS-T2, TS-T6. **Covers**: SYS-01, THM-04.
- **Done when**: instrumented Compose — seleção persiste e reflete no app ao vivo; cadeado/upsell
  conforme entitlement fake on/off; toggle persiste; TabBar com a aba extra não quebra (validar
  visual); suíte existente da config verde.
- **Tests**: unit (estado do seletor) + instrumented (camada `ui`). **Gate**: `full`.

### TS-T10 — `ConfigCodec` + Exportar (SAF, tokens opt-in)
- **What**: codec JSON versionado puro (Settings completos + barras/grades + tema/efeitos +
  idioma; `tokens?` opcional; **nunca** identidade/saldo/entitlements/purchaseTokens — SYS-05);
  UI de export na aba Sistema via `ACTION_CREATE_DOCUMENT`; checkbox "incluir tokens" só aparece
  com token configurado (desligado por padrão + aviso); cancelou SAF → nada parcial.
- **Where**: `data/ConfigCodec.kt`, `ui/config/SistemaPanel.kt`, `ui/SetupActivity.kt` (SAF).
- **Depends on**: TS-T2. **Covers**: SYS-02, SYS-05.
- **Done when**: unit — roundtrip codec completo com/sem tokens; campos proibidos ausentes por
  construção (asserção); instrumented — export gera JSON válido; checkbox condicional.
- **Tests**: unit (grosso) + instrumented. **Gate**: `full`.

### TS-T11 — Importar (validação/versão/relatório)
- **What**: `ACTION_OPEN_DOCUMENT` → validação por seção (aplica válido, relata ignorado);
  `schemaVersion` maior → rejeita com aviso; menor → migra; tokens → storage criptografado; tema
  `locked` sem entitlement → padrão com aviso; corrompido → erro claro sem aplicar nada.
- **Where**: `data/ConfigCodec.kt`, `ui/config/SistemaPanel.kt`.
- **Depends on**: TS-T10. **Covers**: SYS-03 + edges de import.
- **Done when**: unit — todos os edges (corrompido/versão futura/versão antiga/seção inválida/
  tema bloqueado); instrumented — roundtrip export→limpar→import reproduz a config (incl. barras/
  grades e tokens quando incluídos).
- **Tests**: unit + instrumented. **Gate**: `full`.

### TS-T12 — Restaurar de fábrica
- **What**: confirmação explícita → zera Settings/barras/grades/tema/flags de UI pros defaults;
  "apagar também os tokens" opcional (só se houver); gravação em andamento → cancelamento seguro
  antes (reusar o caminho CANCELLING do coordinator); identidade/saldo da monetização intocados.
- **Where**: `ui/config/SistemaPanel.kt`, `data/SettingsRepository.kt` (reset),
  `overlay/OverlayCoordinator.kt` (hook de cancelamento).
- **Depends on**: TS-T10 (mesma tela; defaults do codec reusados). **Covers**: SYS-04.
- **Done when**: instrumented — reset devolve defaults (asserção ampla via repo); com tokens +
  checkbox → tokens somem; sem checkbox → ficam; unit da decisão de cancelamento (gravando →
  cancela primeiro).
- **Tests**: unit + instrumented. **Gate**: `full`.

## Fase E — Fecho

### TS-T13 — Aplicação ao vivo nas 3 superfícies + goldens por tema
- **What**: fiação final do SUR-04: app (recomposição), notificação (re-post no render),
  orbe/setas (observação existente do service), **teclado** (observa `themeId`/`themeDark` leve →
  `setInputView(onCreateInputView())`, mecanismo da troca de idioma). Rotas de galeria por tema
  (config + teclado + orbe, claro/escuro, clock congelado — 2 temas especiais + 1 variante) +
  rota `config-sistema`; regenerar/aprovar goldens novos (existentes bit-idênticos).
- **Where**: `ime/KeyFlowKeyboardService.kt`, `overlay/OverlayService.kt`, `RouteCatalog.kt`,
  `verification/golden/`.
- **Depends on**: TS-T8, TS-T9, TS-T12. **Covers**: SUR-01..04, FX-02 (goldens por tema).
- **Done when**: instrumented — trocar tema com overlay ativo muda o orbe sem restart; IME
  reconstrói com o tema novo (padrão do teste de idioma); `verify-screens.sh`+`diff-screens.sh`
  verdes.
- **Tests**: instrumented + screenshot. **Gate**: `full` + `screenshot`.

### TS-T14 — Validação no A32 (jank/FX-03) + traceability
- **What**: roteiro manual no Galaxy A32: digitação com Glitch/Cyberpunk ativos (sem jank
  perceptível — FX-03; reprovado → degrade daquele efeito e re-teste), troca ao vivo nas 3
  superfícies, export→reset→import real via SAF, "reduzir efeitos". Traceability do `spec.md` →
  Verified; goldens finais espelhados em `/tmp/keyflow-prints/` p/ o Yan.
- **Where**: device físico (RX8T10DERKW); `spec.md`.
- **Depends on**: TS-T13. **Covers**: success criteria da spec.
- **Done when**: roteiro documentado; gate `full` fresco verde; requisitos → Verified.
- **Tests**: manual. **Gate**: `full` + manual.

---

## Validações (pré-aprovação)

### Diagram ↔ Depends-on
| Task | Depends on (corpo) | Diagrama | ✓ |
| ---- | ------------------ | -------- | - |
| T1 | — (`[P]`) | raiz A | ✅ |
| T2 | T1 | T1→T2 | ✅ |
| T3 | T1, T2 | T2→T3 | ✅ |
| T4 | T3 | T3→T4 | ✅ |
| T5 | T1 (`[P]`) | T1→T5 | ✅ |
| T6 | T5 (+⚠️S3/S4) | (T5+⚠️)→T6 | ✅ |
| T7 | T3, T4 | (T3,T4)→T7 | ✅ |
| T8 | T6, T7 (+⚠️S5) | T7→T8 | ✅ |
| T9 | T2, T6 (+⚠️S3) | (T2,T6)→T9 | ✅ |
| T10 | T2 | T2→T10 | ✅ |
| T11 | T10 | T10→T11 | ✅ |
| T12 | T10 | T11→T12 (corpo: T10) | ✅ |
| T13 | T8, T9, T12 | →T13 | ✅ |
| T14 | T13 | T13→T14 | ✅ |

### Test co-location (matriz TESTING.md)
| Task | Camada | Matriz exige | Task diz | ✓ |
| ---- | ------ | ------------ | -------- | - |
| T1 | lógica pura (tema) | unit | unit | ✅ |
| T2 | `data` | instrumented | unit+instrumented | ✅ |
| T3 | `ui`/notif | instrumented+screenshot | instrumented+screenshot | ✅ |
| T4 | `ime`/`overlay` views | instrumented+screenshot | instrumented+screenshot | ✅ |
| T5 | lógica pura | unit | unit | ✅ |
| T6 | lógica pura | unit | unit | ✅ |
| T7 | `ui`+views+lógica | unit/instrumented | unit+instrumented+screenshot | ✅ |
| T8 | views/efeitos | instrumented (+manual T14) | unit+instrumented | ✅ |
| T9 | `ui` | instrumented | unit+instrumented | ✅ |
| T10 | lógica pura + `ui` | unit / instrumented | unit+instrumented | ✅ |
| T11 | lógica pura + `ui` | unit / instrumented | unit+instrumented | ✅ |
| T12 | `ui`+`data` | instrumented | unit+instrumented | ✅ |
| T13 | service/IME + screenshot | instrumented+screenshot | instrumented+screenshot | ✅ |
| T14 | e2e device | manual | manual | ✅ |

**Saída**: fonte única de tokens (fim das cores duplicadas), catálogo extensível por dados
(Ateliê pixel-igual + variantes grátis + 4 skins com efeitos e orçamento de performance), troca
ao vivo nas 3 superfícies e aba Sistema com tema/export/import/reset.
