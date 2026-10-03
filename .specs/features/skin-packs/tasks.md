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
- **Status**: Done (2026-10-02) — `SkinPackNames`, `SkinPackManifest`, `SkinPackReader` (+ `SvgTextCheck`, `ImportReport`); 102 testes
  novos, 929 unitários verdes. Os exemplos do FORMAT.md são lidos pelo teste (`SkinPackFormatDocTest`) e o pacote da árvore do §1
  importa sem avisos. **Mutação**: tirar a recusa de `..`, a contagem de bytes lidos (total e por arquivo), a de DOCTYPE e a de
  `href` externo derrubou 13 testes. Decisões onde o spec era omisso e correções do FORMAT.md: `STATE.md`.

### SKP-T3 — Store e ids (`pack:<id>`)
- **What**: `SkinPackStore` (instalar atômico via `.tmp`+rename, listar, remover, reimportar com mesmo id, load lazy do
  `SkinPack`), `SkinIds`, render de teste dos SVGs no install (usa `SvgRender`), fallback quando falta/corrompe.
- **Done when**: instalar→listar→remover; reimportar atualiza sem mudar o id; pacote corrompido em disco → `null` sem crash;
  nenhum resíduo após erro; id estável e ASCII para nomes com acento/emoji.
- **Tests**: instrumentado `SkinPackStoreTest` (+ unit do slug). **Gate**: unit + instrumentado escopado.
- **Status**: Done (2026-10-02) — `SkinIds`, `SkinPack`, `SkinPackStore`, `ReportText`; `SkinIdsTest` (9) na JVM e `SkinPackStoreTest` (21) no
  emulador, com os 20 do `SvgRenderTest`: **41/41** em `com.keyflow.skins`; 938 unitários verdes. A troca da pasta é por `renameTo`
  (a antiga só sai depois da nova no lugar), o desenho de teste acontece ANTES de gravar qualquer coisa e sobras de processo morto são varridas.

### SKP-T4 — Teclado: `PackKeySurface`, tray e ligação com `pack:<id>`
- **What**: `PackKeySurface : KeySurface` (sprites por tamanho/estado/role em `SpriteCache`, 9-slice), `PackBackdrop`,
  `KeySurface.draw(..., role)` com implementação padrão, `KeyboardColors.forPack`, resolução de `pack:<id>` no service
  (`onCreateInputView`, `applyGlassWindow`, `appliedLiveConfig`) com fallback ao Sistema.
- **Done when**: instalando um pacote de teste por código e pondo `keyboardSkin = "pack:<id>"`, o IME real desenha as faces
  do SVG (pixel conferido), tray, `ink`, `popup`; trocar skin ao vivo reconstrói; pacote removido → Sistema; **goldens
  existentes bit-idênticos**.
- **Tests**: instrumentado (`PackKeySurfaceTest`, ajuste do teste de skin do service) + `diff-screens.sh`. **Gate**: unit + instrumentado + goldens.
- **Status**: Done (2026-10-02) — `PackKeySurface`, `PackBackdrop`, `PackKeyboard` (+ `KeyRole`/`KeySurface.draw(…, role)` com padrão, `SpriteCache.Key.variant`,
  `KeyboardColors.forPack`, `SkinPackStore.changes`); `PackKeySurfaceTest` 19/19, `KeyFlowKeyboardPackSkinTest` 5/5 **no IME real** (pixel das faces, bandeja
  e tinta; troca ao vivo; pacote removido/sumido/quebrado → Sistema; reimportação ao vivo); pacote `com.keyflow.ime` 44/45 (1 flaky, 13/13 nas repetições) e
  **goldens**: 152/154 rotas idênticas ao baseline pixel a pixel, as 2 restantes são a aba Docs com a licença nova (7×5 px no indicador de rolagem);
  `diff-screens.sh` 150/150. Medidas de custo contra as nativas e a mitigação (9-slice por `Picture`) no `STATE.md`.
  **SPEC_DEVIATION** (design, "Renderização e desempenho"): o design mandava "renderizar o SVG em 9 recortes (clip + matriz)"; a biblioteca percorre a
  árvore inteira a cada render, e nove renders custavam 10–20× as skins nativas. O documento é gravado **uma vez** num `Picture` e as nove células o
  reproduzem (clip + matriz, como o design pedia) — pixel a pixel igual (teste contra o re-render, diferença máxima 0). Também `KeyboardColors.forPack(pack)` ficou sem o `density`
  do design (não precisa) e `SkinPackStore.changes` é novo (o design não dizia como o teclado/orbe sabem que o pacote selecionado foi reimportado/removido).

### SKP-T5 — Orbe: `PackOrb`, `OrbStyle.PACK`, ligação com `pack:<id>`
- **What**: frames por estado/tempo (`frameIndex` puro), cache LRU com teto de bytes + pré-aquecimento fora do `onDraw`,
  `OrbStyle.PACK` + `LocalOrbPack`, ramo `PACK` no `drawOrb`, `centerInk`, `OverlayService`/`ConfigScreen` provendo o pack,
  fallback ao Ateliê.
- **Done when**: com um pacote de teste, a orbe anima por estado (relógio congelado → frame determinístico); estado sem
  frames usa `idle`; centro (tempo/glifos/arrasto) continua legível na `centerInk`; **goldens existentes bit-idênticos**;
  custo medido (tempo por frame, memória do cache) e anotado no `STATE.md`.
- **Tests**: unit `frameIndex`; instrumentado `PackOrbTest`; `diff-screens.sh`. **Gate**: unit + instrumentado + goldens.
- **Status**: Done (2026-10-03) — `PackOrb`, `OrbTiming`, `ByteBudgetLru`, `OrbStyle.PACK`/`LocalOrbPack`/`drawPackOrb`, `rememberOrbPack`, `OrbButtonView.setOrbPack`, `OverlayService.openOrbPack`;
  unit 956 (`OrbTimingTest` 6, `ByteBudgetLruTest` 9, `VoiceOrbTest` +3); instrumentados `PackOrbTest` 16, `PackOrbComposeTest` 6 (no `VoiceOrb` real, relógio congelado, pixel conferido) e
  **`OverlayPackOrbTest` 2 na janela flutuante de verdade** (screenshot do emulador: frame do pacote, reimportar ao vivo, voltar ao Ateliê, remover selecionado → Ateliê, pacote inexistente/`pack:../../x` → Ateliê);
  **goldens: 152/154 rotas idênticas ao baseline pixel a pixel** (as 2 são `config-docs[-dark]`, a licença da T1), nenhum aprovado. Custo nativo × pacote medido e anotado no `STATE.md`.
  **SPEC_DEVIATION** (design, "Orbe"): o design pedia "cache LRU com teto de bytes". O LRU é o pior caso de uma animação em loop maior que o cache (o menos usado é sempre o próximo a aparecer: errou 100% numa
  simulação), então o despejo é **consciente da volta**: sai primeiro o que não está tocando e, entre os que estão, o mais longe à frente do cursor ([`ByteBudgetLru.chooseVictim`]). O teto de ~16 MB e o
  pré-aquecimento fora do `onDraw` são como no design; o pré-aquecimento cobre até metade do cache, e a fila de fundo tem prioridades (frame de agora > próximos > aquecimento).

## Fase 2 — Produto

### SKP-T6 — UI de importação (abas Sobrepor e Teclado)
- **What**: `ui/config/SkinPacksSection.kt`: botão **Importar skin** (SAF), diálogo de relatório, cartões das skins
  instaladas com preview real (miniatura do SVG), selecionar, remover com confirmação; plugar em `ConfigFields`/abas com
  edição cirúrgica; strings `tr()` + EN/ES; rotas da galeria.
- **Done when**: fluxo completo na UI (importar → relatório → cartão → selecionar → aplica ao vivo → remover → volta ao padrão);
  cancelar o seletor não deixa nada; `TranslationsTest` verde; goldens existentes das abas só mudam se a seção Skins ganhar o
  botão — nesse caso **aprovar seletivamente** só os que mudaram por isso e justificar no commit.
- **Tests**: instrumentado Compose (`SkinPacksSectionTest`) + goldens novos. **Gate**: unit + instrumentado + goldens.
- **Status**: Done (2026-10-03) — `ui/config/SkinPacksSection.kt` (seção "Suas skins": Importar skin pelo `OpenDocument`, relatório em `ConfigModal`, cartões com miniatura REAL do SVG, selecionar, remover com confirmação),
  `RememberPackKeyboard.kt`, `skins/PackPreview.kt`; aparece em Teclado (pacote com teclado) e Sobrepor (com orbe); rotas da galeria e goldens (4 alterados + 10 novos, aprovados à mão; os outros 138 de 154 PNGs idênticos ao APK de `0f0edab`,
  sem fuzz). **R1** (remover o selecionado reseta `keyboardSkin`/`orbSkin`), **R2** (causa: `FactorySettingsListener` devolve a config do dono no fim da rodada; prova do force-stop no app real) e **R3** (aviso para >48 frames) fechados — ver `STATE.md`.
  Unit 972; instrumentados novos `SkinPacksSectionTest` 16, `PackPreviewTest` 6, `KeyboardSizePreviewPackTest` 2, `SkinSettingsPersistenceTest` 2. O seletor do sistema não é exercitado nos testes de UI (registro trocado); a DocumentsUI de verdade foi na T8.
  **SPEC_DEVIATION** (design, "Ligações"): a seção entra com uma linha em `TecladoPanel` e outra em `OverlayPanel` (não "uma linha em `ConfigFields`") e os cartões são uma lista vertical; `ConfigFields` só troca os 5 `selected =` do teclado por `nativeKeyboardSkin`;
  a prévia de tamanho do teclado também segue o pacote (não previsto); `build.gradle.kts` +1 `sourceSets`; `SkinPackStore(nanoClock)`. Achado corrigido: o aviso "pesado" do import dependia do processo estar frio (agora mede de novo o que parece lento).

### SKP-T7 — Pacote de exemplo + FORMAT.md final
- **What**: `skin-packs/exemplo-neon/` (SVGs reais: orbe com 4 estados e frames, teclado completo com 9-slice, manifest) +
  `scripts/build-skin-pack.sh <pasta> <saida.zip>` + ajuste do FORMAT.md ao que o código realmente aceita.
- **Done when**: o `.zip` importa **sem avisos**; teste unit confere o exemplo com o validador; FORMAT.md sem divergência.
- **Tests**: unit + instrumentado do import do exemplo. **Gate**: unit + instrumentado escopado.
- **Status**: Done (2026-10-03) — `skin-packs/exemplo-neon/` ("Neon Noturno": 45 frames em 4 estados + teclado completo com `keyboard.slice`), `skin-packs/gerar-exemplo-neon.py`, `scripts/build-skin-pack.sh`, `skin-packs/README.md` (passo a passo para quem não programa);
  `ExampleSkinPackTest` (JVM: a pasta zipada em memória e o `.zip` do script, sem erro e sem aviso) e `ExampleSkinPackImportTest` (aparelho). Importa SEM avisos também pelo seletor real, a frio (3 de 3). Nenhuma divergência visível entre o rsvg do host e o AndroidSVG do emulador;
  nenhuma limitação nova do AndroidSVG. FORMAT.md: ponteiro para o README (§1) e a regra da segunda medida do "pesado" (§6).

### SKP-T8 — Validação ponta a ponta e fechamento
- **What**: no **emulador** (nunca no aparelho do Yan sem ele pedir): instalar o exemplo pela UI/SAF, selecionar teclado e orbe,
  `screencap` do IME real e da orbe nos 4 estados; suíte unit completa + instrumentada relevante + `verify-screens`/`diff-screens`;
  atualizar `STATE.md`, traceability, `DEVELOPMENT-ORDER.md` (linha da feature).
- **Done when**: tudo verde, evidências citadas (comandos + contagens), pendências do Yan listadas (roteiro manual no A32 **[YAN]**).
- **Status**: Done (2026-10-03), exceto o roteiro no A32 **[YAN]** — no emulador: importado pelo seletor REAL (DocumentsUI, por `adb`), selecionado teclado e orbe, IME real e orbe flutuante real com o exemplo (e depois de `am force-stop`), reimportação ao vivo
  (`exemplo-neon-v2.zip`), remoção do selecionado (volta ao padrão no arquivo, no IME e na tela); a orbe flutuante nos 4 estados em 156/195/280 px (estado posto por `OrbButtonView.render`; gesto e microfone reais não exercitados). Unit 972/972; instrumentados `ui` 199/199, `overlay` 30/30,
  `skins` 128 (flaky de fila medido no baseline também), `ime` 45 (1 flake de toque); goldens 138/154 idênticos a `0f0edab` e `diff-screens.sh` 160/160. Detalhes, números e a causa de cada flaky em `STATE.md`.
