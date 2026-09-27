# Theme System — Design

Arquitetura do sistema de temas + aba Sistema. Decisões e assunções em `context.md` (S1–S7);
requisitos em `spec.md`. **Status**: Draft (aguardando aprovação do Yan).

> ⚠️ **Três assunções da spec ainda não validadas com o Yan** (S3 gating das skins, S4 recorte
> das "cores normais", S5 efeitos em todas as superfícies no MVP). O design isola cada uma num
> ponto único (THM-04 / catálogo / EffectsSpec) para que mudar a resposta custe pouco. **Validar
> antes de TS-T6/TS-T9** — está marcado nas tasks.

## Princípio condutor

**Tema é dado; rendering é função.** Um único registro resolve `(themeId, dark)` → paleta Compose
+ espelho ARGB + descritor de efeitos. As superfícies (app, notificação, orbe, teclado) só
conhecem tokens — nunca cores literais. Ateliê resolvido pelo registro reproduz **bit-idêntico**
o visual atual (migração invisível: goldens existentes continuam verdes sem regeneração). Um tema
novo = registrar dados (TOK-03).

```
Settings.themeId ─┐
Settings.themeDark ┤──► ThemeRegistry.resolve(themeId, dark): ResolvedTheme
                   │        ├─ palette: KeyFlowPalette      (Compose — app/config/onboarding)
                   │        ├─ argb:    ArgbTokens           (views: teclado/orbe/setas/notif)
                   │        └─ effects: EffectsSpec          (por superfície; ausência = estático)
                   ▼
  app Compose (KeyFlowTheme) · RecordingNotification · OrbButtonView/VoiceOrb ·
  KeyFlowKeyboardView/KeyboardGridView/BarView/CandidatesView · DragArrowsView
```

## Fundação de tokens (TOK-01..04)

### `ThemeSpec` / `ThemeRegistry` (`ui/theme/ThemeCatalog.kt` + pacote `ui/theme/`)

```kotlin
data class ThemeSpec(
    val id: String,                        // "atelie", "atelie-azul", "vaporwave", ...
    val displayName: String,
    val locked: Boolean,                   // true = skin da monetização (THM-04, assunção S3 ⚠️)
    val light: PaletteSource,              // como o tema aplica cada modo (TOK-04):
    val dark: PaletteSource,               //   Derive(base + overrides de acento) | Own(paleta completa)
    val effects: EffectsSpec = EffectsSpec.NONE,
)
sealed class PaletteSource {               // os DOIS mecanismos do S6
    data class Derive(val base: BaseMode, val accents: AccentOverrides) : PaletteSource()
    data class Own(val palette: KeyFlowPalette) : PaletteSource()
}
```

- **`ThemeRegistry.resolve(themeId, dark): ResolvedTheme`** — função pura, unit-testável;
  id desconhecido (import de versão futura, entitlement perdido) → Ateliê.
- **`ArgbTokens`**: espelho `Int`-ARGB derivado da `KeyFlowPalette` resolvida (conversão
  mecânica `Color.toArgb()`) + tokens que hoje só existem nas views (ex.: sombras/pressed da
  grade). Fonte única: a paleta Compose; o espelho é **derivado**, nunca mantido à mão.
- Um tema pode dar `light == dark` (Cyberpunk sempre escuro) — escolha do TEMA (TOK-04).

### Migração das views (TOK-02) — o grosso do trabalho

Inventário de cores hardcoded a substituir por `ArgbTokens` (S2): `KeyboardGridView` (teclas,
tinta, bordas, preview, popup de acentos), `KeyFlowKeyboardView` (tray, mic, faixas), `BarView`
(botões, alavanca, lente/grade), `CandidatesView`, `OrbButtonView`/`VoiceOrb` (acento/estados),
`DragArrowsView`, `RecordingNotification` (RemoteViews). A migração é **por superfície, com
golden como rede**: cada task termina com `diff-screens.sh` provando bit-igualdade no Ateliê.

- As views já recebem `dark: Boolean` na criação — passa a receber `ArgbTokens` resolvido (o
  ponto de criação — service/overlay — resolve via registry). Assinaturas mudam uma vez.

## Catálogo (THM-01..04)

- **Ateliê** (`atelie`): `Derive` dos dois modos com zero override — resolve exatamente
  `LightPalette`/`DarkPalette` atuais (teste de igualdade estrutural trava isso, THM-01).
- **Variantes de acento** (S4 ⚠️): `atelie-azul/roxo/verde/ambar` — `Derive` trocando
  teal→acento novo (e derivados: seleção, chips, orbe). Só dados; prova o TOK-03 (THM-02).
- **Especiais** (S3 ⚠️, `locked = true`): Vaporwave, Glitch, Cyberpunk, Rosa — paletas `Own` por
  modo (direções visuais no S5) + `EffectsSpec` próprios (THM-03).
- **Gating** (THM-04): o seletor consome a interface **`EntitlementsProvider`** (definida na
  monetization MZ-T4). **Se a monetization ainda não estiver no build**: stub local
  `skinsUnlocked=false` (tudo bloqueado com preview+cadeado→upsell inerte) — a dependência é da
  interface, não da feature. A seção "Skins" da aba Teclado vira um atalho pra aba Sistema.

## Efeitos (FX-01..04)

```kotlin
data class EffectsSpec(
    val app: AppEffect?,        // fundo: gradiente animado, scanlines, grid retrô...
    val orb: OrbEffect?,        // glow/pulso extra sobre a animação base do orbe
    val keyboard: KeyEffect?,   // keypress (glow/ripple curto) + tratamento do tray
)
```

- **Declarativo e fechado**: cada efeito é um enum/data class interpretado pelo renderer da
  superfície — tema NÃO carrega código (mantém "tema = dados" e o orçamento auditável).
- **Clock injetável** (FX-02): app/orbe usam `LocalOrbClock`/`rememberOrbClock` (existentes);
  teclado usa o ticker próprio da view com `frozenSeconds`-like injetável (padrão do timer do
  mic). Clock congelado → frame determinístico → goldens por tema.
- **Orçamento no IME** (FX-03): regras de implementação obrigatórias — objetos de desenho
  pré-alocados (zero alocação em `onDraw`), invalidação **por região** da tecla animada (nunca a
  árvore), efeito de keypress com vida ≤ ~200 ms; qualquer efeito que não couber degrada a
  estático **naquela superfície** (o resolve devolve `keyboard = null` p/ aquele tema em modo
  degradado). Validação real: digitação no A32 (TS-T14).
- **"Reduzir efeitos"** (FX-04): `Settings.reduceEffects: Boolean` — força `EffectsSpec.NONE` no
  resolve; também respeitado quando `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (checado no
  ponto de resolve — cores/textura ficam).

## Superfícies ao vivo (SUR-01..04)

- **App/config**: `SetupActivity` já coleta `settingsFlow()` → só passa a resolver o tema
  (recomposição natural). **Notificação**: `RecordingNotification.build` recebe `ArgbTokens`
  (mesmo caminho vivo do claro/escuro atual).
- **Orbe/overlay**: `OverlayService` observa settings (já observa) → re-resolve → orbe/setas
  re-renderizam (o token `OrbAccent` vira token do tema).
- **Teclado** (SUR-04, o caso difícil): hoje tema aplica só na recriação da view. Reusar o
  mecanismo **já validado** da troca de idioma (`setInputView(onCreateInputView())` observando o
  DataStore leve — `themeIdOnce()`/flow no service): mudança de tema/modo com teclado aberto →
  reconstrói a view. Leitura no caminho crítico segue o padrão `*Once()` (só DataStore, sem
  EncryptedSharedPreferences — lição do `keyboardScalePercentOnce`).

## Aba Sistema (SYS-01..05)

- **`ConfigTab.SISTEMA`** + `SistemaPanel`: sem gating de modo (sempre acessível). Conteúdo:
  1. **Seletor de temas**: grid de cards com **preview em miniatura real** (mini-orbe + 3 teclas
     + chip, renderizados com a paleta resolvida no modo atual — composable puro, clock
     congelado), nome, cadeado nos `locked` sem entitlement → tap abre upsell (UnlockSheet da
     monetization, ou inerte no stub). Persiste `Settings.themeId`.
  2. **"Reduzir efeitos"** (toggle → `reduceEffects`).
  3. **Exportar/Importar** (SAF) e **Restaurar de fábrica**.
- **`ConfigCodec`** (`data/ConfigCodec.kt`, puro): JSON versionado
  `{schemaVersion, settings{...}, barsConfig, shortcutGrids, tokens?{...}}` com **todas** as
  opções modificáveis (Settings completos, barras/grades do Trabalho 3, tema/efeitos, idioma).
  - **Export** (SYS-02): checkbox "incluir tokens" só aparece se houver token configurado
    (desligado por padrão, aviso de sensibilidade); **nunca** exporta identidade/saldo/
    entitlements/purchaseTokens (SYS-05 — nem são lidos pelo codec).
  - **Import** (SYS-03): `schemaVersion` maior → rejeita com aviso; menor → migra (espírito das
    migrações de chave do DataStore); validação por seção — aplica o válido, relata o ignorado;
    tema `locked` sem entitlement → cai no padrão com aviso; tokens → direto pro storage
    criptografado. Aplicação tudo-ou-nada **por seção** validada (edge).
  - **Reset** (SYS-04): confirmação explícita; zera Settings/barras/grades/tema/flags; "apagar
    também os tokens" opcional (só se houver); gravação em andamento → cancela com segurança
    antes (via `OverlayCoordinator`, padrão CANCELLING); identidade/saldo intocados.
  - SAF (`ACTION_CREATE_DOCUMENT`/`ACTION_OPEN_DOCUMENT`): cancelou → nada parcial (escrita só
    no callback com URI).

## Dados

- `Settings` += `themeId: String = "atelie"`, `reduceEffects: Boolean = false` (persistidos;
  leitura leve `themeIdOnce()` p/ o IME). `themeDark` continua como está (coexiste, S6).

## O que NÃO muda

`ButtonStateMachine`/gestos, fluxo de gravação/inserção, layout/geometria do teclado, estrutura
das abas existentes (só entra a 6ª/7ª aba Sistema), toggle claro/escuro (mesmo lugar), fontes
(out of scope).

## Plano de testes (TESTING.md)

- **Unit (JVM)**: resolve do Ateliê == paletas atuais (igualdade estrutural, THM-01); variantes
  de acento trocam teal e derivados; `Own` vs `Derive`; id desconhecido → Ateliê; espelho ARGB
  consistente com a paleta; `ConfigCodec` roundtrip completo (com/sem tokens), rejeição de versão
  futura, migração de versão antiga, seção inválida ignorada com relato; reduceEffects força NONE.
- **Instrumented**: `SettingsRepository` persiste campos novos; `SistemaPanel` Compose (seleção
  persiste, cadeado/upsell, toggle, diálogos de reset); troca de tema ao vivo no `OverlayService`
  real (padrão `OverlayServiceCancelTest`); IME reconstrói com tema novo (padrão do teste de
  idioma); import aplica em estado limpo (roundtrip export→reset→import).
- **Screenshot**: goldens existentes **bit-idênticos** com Ateliê (rede da migração TOK-02);
  rotas novas por tema (config/teclado/orbe × claro/escuro × 2-3 temas com clock congelado) +
  rota da aba Sistema.
- **Manual (A32)**: digitação sem jank com Glitch/Cyberpunk (FX-03); troca ao vivo nas 3
  superfícies; export→reset→import de verdade via SAF.

## Riscos / mitigação

- **Regressão visual na migração TOK-02** → migrar por superfície com `diff-screens.sh` a cada
  task (bit-igualdade é o gate, não opinião).
- **Jank no teclado com efeitos** → regras de orçamento (pré-alocação/invalidação regional) +
  degrade a estático por superfície + validação A32 antes de fechar.
- **Acoplamento com a monetization** → só via `EntitlementsProvider` (stub se ausente); ordem
  recomendada é monetization primeiro (ver `DEVELOPMENT-ORDER.md`), mas TS-T1..T5 não dependem
  dela em nada.
- **Assunções ⚠️ viram retrabalho se o Yan discordar** → S3 muda só THM-04/`locked`; S4 muda só a
  lista de variantes; S5 muda só `EffectsSpec` dos temas. Validar nas tasks marcadas.
- **RemoteViews da notificação tem vocabulário limitado** → tokens de cor aplicam via
  `setColorFilter`/`setTextColor` (mesma técnica atual); efeitos NÃO se aplicam à notificação
  (fora do FX por definição — documentado).
