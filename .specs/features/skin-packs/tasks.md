# Skins importáveis — Tasks

Spec: `spec.md` · Design: `design.md` · Formato: `FORMAT.md`. Uma task por vez, na ordem; **commit atômico por task**
(mensagem em português, sem citar Claude nem trailers). Gate de cada task no fim dela. Atualizar o Status aqui e a
traceability do `spec.md` ao concluir.

## Fase 1 — Motor (sem UI)

### SKP-T1 — Dependência AndroidSVG + `SvgRender` + prova de renderização
- **What**: adicionar `com.caverock:androidsvg-aar` (versão estável mais recente conferida no Maven Central — não inventar
  versão) ao `app/build.gradle.kts`; criar `skins/SvgRender.kt` (parse com cache, `renderTo(canvas, w, h)`, `toBitmap`,
  9-slice por recortes); registrar a licença (Apache-2.0) onde o app lista licenças (há `i18n`/`DocsPanel`/RSN-T18 — procure).
- **Done when**: SVG de cor sólida renderiza o pixel esperado; gradiente linear/radial conferido; 9-slice mantém o canto
  com proporção; `<filter>` NÃO derruba (é ignorado); tempo de render medido e anotado em `.specs/project/STATE.md`.
- **Tests**: instrumentado `SvgRenderTest`. **Gate**: `assembleDebug` + instrumentado escopado.
- **Status**: Done (2026-10-02) — `androidsvg-aar:1.4` (única `release` do Maven Central além da 1.3), `skins/SvgRender.kt` + `SliceInsets.kt`,
  licença em `app/licenses/` e na aba Docs; `SvgRenderTest` 20/20 no emulador API 34; 827 unitários verdes. Achados do AndroidSVG
  (entidades internas LIGADAS por padrão; `width`/`height` do `<svg>` mandavam no tamanho; sem `viewBox` não escala) e medidas no `STATE.md`.

### SKP-T2 — Núcleo puro: manifest, nomes, reader/validador, relatório
- **What**: `SkinPackManifest`, `SkinPackNames`, `SkinPackReader`, `ImportReport` (FORMAT.md inteiro; limites do §5;
  sanitização do design). **Sem** `android.graphics` aqui (JVM puro; `org.json` real já está nos testes JVM).
- **Done when**: todos os casos de borda do spec.md têm teste; os exemplos de JSON/nomes do FORMAT.md parseiam (SKP-DOC-01);
  zip-slip, zip bomb (bytes lidos), entidade/DOCTYPE, `<script>`, `href` externo → erro; `<filter>` → aviso.
- **Tests**: unit (`SkinPack*Test`). **Gate**: `testDebugUnitTest`.
- **Status**: Pending

### SKP-T3 — Store e ids (`pack:<id>`)
- **What**: `SkinPackStore` (instalar atômico via `.tmp`+rename, listar, remover, reimportar com mesmo id, load lazy do
  `SkinPack`), `SkinIds`, render de teste dos SVGs no install (usa `SvgRender`), fallback quando falta/corrompe.
- **Done when**: instalar→listar→remover; reimportar atualiza sem mudar o id; pacote corrompido em disco → `null` sem crash;
  nenhum resíduo após erro; id estável e ASCII para nomes com acento/emoji.
- **Tests**: instrumentado `SkinPackStoreTest` (+ unit do slug). **Gate**: unit + instrumentado escopado.
- **Status**: Pending

### SKP-T4 — Teclado: `PackKeySurface`, tray e ligação com `pack:<id>`
- **What**: `PackKeySurface : KeySurface` (sprites por tamanho/estado/role em `SpriteCache`, 9-slice), `PackBackdrop`,
  `KeySurface.draw(..., role)` com implementação padrão, `KeyboardColors.forPack`, resolução de `pack:<id>` no service
  (`onCreateInputView`, `applyGlassWindow`, `appliedLiveConfig`) com fallback ao Sistema.
- **Done when**: instalando um pacote de teste por código e pondo `keyboardSkin = "pack:<id>"`, o IME real desenha as faces
  do SVG (pixel conferido), tray, `ink`, `popup`; trocar skin ao vivo reconstrói; pacote removido → Sistema; **goldens
  existentes bit-idênticos**.
- **Tests**: instrumentado (`PackKeySurfaceTest`, ajuste do teste de skin do service) + `diff-screens.sh`. **Gate**: unit + instrumentado + goldens.
- **Status**: Pending

### SKP-T5 — Orbe: `PackOrb`, `OrbStyle.PACK`, ligação com `pack:<id>`
- **What**: frames por estado/tempo (`frameIndex` puro), cache LRU com teto de bytes + pré-aquecimento fora do `onDraw`,
  `OrbStyle.PACK` + `LocalOrbPack`, ramo `PACK` no `drawOrb`, `centerInk`, `OverlayService`/`ConfigScreen` provendo o pack,
  fallback ao Ateliê.
- **Done when**: com um pacote de teste, a orbe anima por estado (relógio congelado → frame determinístico); estado sem
  frames usa `idle`; centro (tempo/glifos/arrasto) continua legível na `centerInk`; **goldens existentes bit-idênticos**;
  custo medido (tempo por frame, memória do cache) e anotado no `STATE.md`.
- **Tests**: unit `frameIndex`; instrumentado `PackOrbTest`; `diff-screens.sh`. **Gate**: unit + instrumentado + goldens.
- **Status**: Pending

## Fase 2 — Produto

### SKP-T6 — UI de importação (abas Sobrepor e Teclado)
- **What**: `ui/config/SkinPacksSection.kt`: botão **Importar skin** (SAF), diálogo de relatório, cartões das skins
  instaladas com preview real (miniatura do SVG), selecionar, remover com confirmação; plugar em `ConfigFields`/abas com
  edição cirúrgica; strings `tr()` + EN/ES; rotas da galeria.
- **Done when**: fluxo completo na UI (importar → relatório → cartão → selecionar → aplica ao vivo → remover → volta ao padrão);
  cancelar o seletor não deixa nada; `TranslationsTest` verde; goldens existentes das abas só mudam se a seção Skins ganhar o
  botão — nesse caso **aprovar seletivamente** só os que mudaram por isso e justificar no commit.
- **Tests**: instrumentado Compose (`SkinPacksSectionTest`) + goldens novos. **Gate**: unit + instrumentado + goldens.
- **Status**: Pending

### SKP-T7 — Pacote de exemplo + FORMAT.md final
- **What**: `skin-packs/exemplo-neon/` (SVGs reais: orbe com 4 estados e frames, teclado completo com 9-slice, manifest) +
  `scripts/build-skin-pack.sh <pasta> <saida.zip>` + ajuste do FORMAT.md ao que o código realmente aceita.
- **Done when**: o `.zip` importa **sem avisos**; teste unit confere o exemplo com o validador; FORMAT.md sem divergência.
- **Tests**: unit + instrumentado do import do exemplo. **Gate**: unit + instrumentado escopado.
- **Status**: Pending

### SKP-T8 — Validação ponta a ponta e fechamento
- **What**: no **emulador** (nunca no aparelho do Yan sem ele pedir): instalar o exemplo pela UI/SAF, selecionar teclado e orbe,
  `screencap` do IME real e da orbe nos 4 estados; suíte unit completa + instrumentada relevante + `verify-screens`/`diff-screens`;
  atualizar `STATE.md`, traceability, `DEVELOPMENT-ORDER.md` (linha da feature).
- **Done when**: tudo verde, evidências citadas (comandos + contagens), pendências do Yan listadas (roteiro manual no A32 **[YAN]**).
- **Status**: Pending
