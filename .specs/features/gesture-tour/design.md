# Gesture Tour — Design

Arquitetura do tour de gestos. Decisões em `context.md` (T1–T8); requisitos em `spec.md`.

## Princípio condutor

**Roteiro é dado; player é motor; cena é a UI real.** O gesto demonstrado é uma lista declarativa
de keyframes (posição do dedo + ação + reação da cena + descrição). O player toca qualquer lista.
As cenas são as superfícies reais do app (`VoiceOrb`, grade Canvas) em miniatura — o tour nunca
dessincroniza do produto, e roteiros novos (Visão, parcial) entram como dados, sem tocar o motor.

```
Settings ──► GestureScriptCatalog (funções puras)
               buildOrbScripts(settings)      TOGGLE/PTT · atalhos 1/2/3 · cancelar/desfazer · arrastar
               buildKeyboardScripts(settings) retrair/expandir · mic tap · mic hold-seletor
                     ▼ List<GestureScript>
GestureTourPlayer (Composable isolado, clock injetável)
  ├─ Scene: ORB → VoiceOrb real (estado dirigido pelo passo)
  │         KEYBOARD → grade Canvas NOVA em miniatura (estado dirigido)
  ├─ FingerPuppet: vetor do dedo (halo/ripple/trilha) animado por keyframes
  └─ rememberGestureTourState: índice · título · descrição · next()/prev() · autoplay
                     ▼ consumidores
ModoPanel (cards, via DemoFrame+setas)  ·  [futuro: onboarding, Docs, tooltips]
```

## Componentes (pacote `ui/tour/`)

### 1. Modelo de dados — `GestureScript` (SCR-01)
```kotlin
enum class TourScene { ORB, KEYBOARD }
enum class FingerAction { MOVE, TAP, MULTI_TAP /*count*/, PRESS, RELEASE, DRAG /*path*/ }

data class SceneReaction(          // o que a cena mostra a partir deste passo
    val orbState: OrbState? = null,           // Idle/Listening/Paused…
    val micState: ButtonState? = null,        // mic do teclado
    val keyboardExpanded: Boolean? = null,
    val overlayHint: TourOverlayHint? = null, // setas de arrasto/banner desfazer (representações)
)

data class GestureStep(
    val at: Offset,                 // posição relativa (0..1) na cena
    val action: FingerAction,
    val durationMs: Int,
    val reaction: SceneReaction = SceneReaction(),
    val dragPath: List<Offset> = emptyList(),
    val tapCount: Int = 1,
)

data class GestureScript(
    val id: String,                 // "orb-toggle-record", "kbd-mic-hold"…
    val title: String,              // vai na barra PRÉVIA
    val description: String,        // vai no texto à direita
    val scene: TourScene,
    val steps: List<GestureStep>,
)
```
Serializável por construção (data classes puras) — features futuras adicionam roteiros como dados.

### 2. `GestureScriptCatalog` (SCR-02..06) — funções puras
- `buildOrbScripts(settings)`: básicos por `overlayRecordingMode` (TOGGLE: toque=gravar/parar,
  segurar=pausar/retomar · PTT: segurar=falar, soltar=enviar, toque-rápido-na-janela=pausar,
  segurar-de-novo=retomar) → cancelar/desfazer (arrasto ao alvo + banner com desfazer) →
  reposicionar (arrasto livre) → atalhos de `shortcut1/2/3Tap` (rótulo real da ação; skip NONE;
  1 toque só no PTT — mesmas regras de `resolveShortcut`). Descrições reusam/absorvem
  `pauseHintFor` (fonte textual única).
- `buildKeyboardScripts(settings)`: retrair/expandir → mic toque (gravar/enviar) → mic segurar
  (seletor pausar/cancelar).
- 100% unit-testável (Settings → lista esperada).

### 3. `FingerPuppet` (TOUR-02)
Composable que desenha o dedo: disco com halo (idle), **ripple** em TAP/MULTI_TAP (n pulsos),
preenchimento "pressionado" em PRESS, **trilha esmaecida** em DRAG. Posição/fase interpoladas dos
keyframes pelo **clock injetável** (`rememberTourClock(enabled)` — mesmo padrão do
`rememberOrbClock`/`LocalOrbClock`; galeria congela o tempo → TOUR-05). Interpolação em função
pura (`tourFrameAt(script, timeMs): TourFrame`) — unit-testável sem Compose.

### 4. `GestureTourPlayer` (TOUR-01/03/04)
```kotlin
@Composable fun GestureTourPlayer(
    scripts: List<GestureScript>,
    state: GestureTourState = rememberGestureTourState(scripts),
    modifier: Modifier = Modifier,
)
class GestureTourState { val index; val title; val description; fun next(); fun prev(); … }
```
- Renderiza a cena do roteiro corrente (ORB → `VoiceOrb` com o estado do passo; KEYBOARD → grade
  **Canvas nova** miniatura com mic/expansão dirigidos) + `FingerPuppet` por cima.
- **Autoplay em loop** na ordem; `next()/prev()` interrompem a animação corrente de forma limpa
  (reset do passo) e **pausam o autoplay ~6 s** antes de retomar (T4).
- Estado (índice/título/descrição) exposto ao consumidor — a descrição fica ONDE o consumidor
  quiser (no card: área direita). Player não conhece ModoPanel (REU/TOUR-01).
- Sai de cena → clock para (padrão `onRelease` da demo atual; sem vazamento).
- A11y: container com `contentDescription` = título+descrição do passo; setas focáveis.

### 5. Cenas reais
- **Orbe**: `VoiceOrb` (já injetável por clock). Alvos de arrasto/banner de desfazer aparecem
  como **representações leves** (`TourOverlayHint` — setinhas/pílula desenhadas no estilo Ateliê),
  não os componentes reais de overlay (que exigem janela/serviço).
- **Teclado**: a demo atual usa `KeyFlowKeyboardView` completa (View híbrida) — **manter a View
  real** porém com a grade Canvas **nova** forçada (`useCanvasGrid=true`; MODO-04), `scale`
  reduzido como hoje, callbacks no-op, estado do mic dirigido pelo passo (`updateMicState`), e
  `key(dark)` preservado (tema).

### 6. Integração na aba Modo (MODO-01..04)
- `ModeCard.demo` recebe o player; `DemoFrame` ganha o rodapé de navegação: **‹ título ›**
  (MODO-02).
- Área direita do card: descrição sincronizada do passo (substitui `pauseHint` estático e as
  `features` genéricas **durante** o tour; MODO-03). `pauseHintFor` segue existindo p/ a Docs.
- Regeneração por Settings: os roteiros são `remember(settings.overlayRecordingMode,
  settings.shortcut1Tap, …) { buildOrbScripts(settings) }` — recompõe ao mudar config (SCR-07).

### 7. Docs (DOC-01..06)
- **Conteúdo como dado**: `DocsPanel` passa a renderizar de uma lista `docsSections`
  (`DocSection(id, title, content)`) em vez de chamadas soltas de `Section(...)` — o **índice**
  (DOC-02) é gerado dessa mesma lista (fonte única) e as seções novas de detalhes (DOC-03:
  Gestos da orbe · Teclado KeyFlow · Notificação de gravação · Erros & tempo limite) entram como
  itens.
- **Âncoras/rolagem**: cada seção registra sua posição Y via `onGloballyPositioned`; tocar o item
  do índice chama `scrollState.animateScrollTo(y)` — posição medida do layout real, não offset
  fixo (edge case do índice).
- **Feedback (DOC-04/05)**: seção final com duas ações; `Intent(ACTION_SENDTO, "mailto:...")`
  com destinatário `FEEDBACK_EMAIL` (constante única), assunto "KeyFlow — bug" / "KeyFlow —
  sugestão" e corpo com diagnóstico (`BuildConfig.VERSION_NAME`, `Build.VERSION.RELEASE`,
  `Build.MODEL`). `ActivityNotFoundException` → snackbar/aviso com o endereço (sem crash).
- Textos das seções de gesto alinhados às descrições dos roteiros (mesma fonte textual onde der).

### 8. Apresentação — "?" por permissão (PERM-01..04)
`ChecklistRow` do `OnboardingScreen` ganha um ícone "?" tocável após o status: estado
`expandedPermission: String?` no topo da tela (no máximo um aberto — tocar outro "?" troca; tocar
o mesmo fecha) expande a explicação inline sob a linha (`AnimatedVisibility`), estilo Ateliê
(texto `faint`, fonte menor). Textos por permissão conforme PERM-03, consistentes com a seção
Privacidade da Docs. Test tags por linha (`onboarding-why-<permissão>`); TalkBack: o "?" anuncia
"por que pedimos <permissão>" e o texto expandido é lido. O host (`SetupActivity`) não muda —
é UI pura dentro da tela.

## O que NÃO muda
Orbe/teclado/gestos reais (o tour é só representação), `ButtonStateMachine`, gating/toggles dos
cards, `DemoFrame` além do rodapé de navegação, galeria/goldens existentes (rotas novas à parte).

## Plano de testes (TESTING.md)

- **Unit (JVM)**: catálogo por Settings (TOGGLE vs PTT; atalhos NONE somem; 1 toque só no PTT;
  ordem pedagógica); interpolação `tourFrameAt` (posição/fase por tempo, fim de passo, loop);
  lógica do estado (next/prev com pausa de autoplay; retomada).
- **Instrumented (Compose, clock congelado)**: player com 2 roteiros fake — descrição/título
  trocam com as setas; autoplay avança com clock manual; sair de cena para o clock; ModoPanel
  integrado (testes existentes ajustados — a demo nova não quebra toggles/permissões); a11y do
  container; Docs — tocar item do índice rola até a seção (posição do scroll > 0 e seção
  visível), ação de feedback dispara o intent `mailto:` esperado (intent capturado, sem abrir
  e-mail de verdade); onboarding — "?" expande/troca/fecha a explicação certa.
- **Screenshot**: rotas `tour-orb-*`/`tour-keyboard-*` + `config-modo` atualizada, com clock
  congelado em t fixo por rota (frames-chave determinísticos), claro/escuro.
- **Manual (Galaxy A32)**: fluidez dos 2 tours simultâneos na aba (jank), leitura em voz do
  TalkBack.

## Riscos / mitigação
- **Duas animações contínuas na mesma aba** (custo de recomposição) → keyframes leves, 1 clock
  compartilhado por aba; medir no A32 (task final); fallback: autoplay só no card visível.
- **Demo do teclado com View real + grade nova** → já validado pelo padrão atual (View real na
  demo); a flag da grade nova precisa estar acessível à demo (independente do prefs de debug).
- **Descrição substituindo o texto estático do card** → validar visualmente que permissões/toggle
  do card continuam claros (golden captura).
- **Sincronia texto-animação** → descrição é do ROTEIRO (não de sub-passo) — simples e suficiente.
