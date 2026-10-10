# Skins importáveis — Design

Requisitos em `spec.md`; formato em `FORMAT.md`. Esta página decide **como** encaixar no código de hoje.

## Diagnóstico do código atual (2026-10-02)

| Superfície | Ponto de encaixe | Estado |
| ---------- | ---------------- | ------ |
| Teclado — faces | `ime/KeySurface.kt` (interface `draw(canvas, bounds, radius, tint, pressed)`, `labelPasses`, `useSheet`, `setEffect`); `KeyboardGridView`/`BarView` só chamam `colors.surface(...)` | **Pronto**: uma skin nova é só mais uma implementação |
| Teclado — fundo | `KeyboardBackdrop` (**enum**: `drawable()`, `surface()`, `navigationBarColor()`) e `KeyboardColors.backdrop` | Precisa de um caminho dinâmico (enum é fechado) |
| Teclado — escolha | `KeyboardSkin` (enum) → `KeyboardColors.of(skin, dark)`; o service lê `keyboardSkinOnce()` e reconstrói a view quando muda (`appliedLiveConfig`) | Precisa aceitar `pack:<id>` |
| Orbe — desenho | `VoiceOrb.kt`: `drawOrb(t, state, style, hasCenter)` em `DrawScope`, `t` em ms (relógio injetável), `OrbState` = Idle/Listening/Paused/Transcribing; centro (tempo/glifos) é Compose por cima | Frames-por-estado casam naturalmente; `OrbStyle` é enum com `when` |
| Orbe — escolha | `Settings.orbSkin` → `orbStyleFor(id)`; `OverlayService` → `floatingButton.setOrbStyle`; `LocalOrbStyle` na config | Precisa aceitar `pack:<id>` |
| SVG | **nada** no app (nenhuma lib) | Adicionar **AndroidSVG** (`com.caverock:androidsvg-aar`, Apache-2.0, Maven Central) |

Mapa `ButtonState → OrbState` já existe em `OrbButtonView.orbStateFor`: RECORDING→Listening, PAUSED/CANCELLING→Paused,
SENDING→Transcribing, resto (inclui ERROR) →Idle. É exatamente a tabela do FORMAT.md §2.

## Módulos (tudo novo em `com.keyflow.skins`)

```
skins/
  SkinPackManifest.kt   # data class + parse (org.json) + defaults + validação de campo       [puro/JVM]
  SkinPackNames.kt      # "orb_listening_003.svg" -> (Orb, LISTENING, 3) | Keyboard(part, state) [puro/JVM]
  SkinPackReader.kt     # InputStream(zip) -> SkinPackContent | ImportReport (limites, zip-slip,
                        #   zip bomb por bytes lidos, sanitização textual do SVG)               [puro/JVM]
  ImportReport.kt       # installed[] / warnings[] / errors[] (strings já em PT via tr())
  SkinPackStore.kt      # install/list/remove em filesDir/skins/<id>/, id = slug+hash, atômico
                        #   (grava em <id>.tmp e renomeia)                                       [Android Context]
  SkinPack.kt           # pack instalado: manifest + arquivos; load() lazy; ids "pack:<id>"
  SvgRender.kt          # fina camada sobre AndroidSVG: render(svg, canvas, w, h) / toBitmap,
                        #   9-slice, cache de SVG parseado; ÚNICO arquivo que importa com.caverock
  PackKeySurface.kt     # KeySurface: sprites por (w,h,estado,role) via SpriteCache + 9-slice
  PackBackdrop.kt       # Drawable do tray (esticado) / cor lisa
  PackOrb.kt            # frames por estado -> bitmap/Picture em cache LRU com teto de bytes;
                        #   frameIndex(t, fps, n) puro e testado
```

Regra: **só `SvgRender.kt` conhece a biblioteca** — trocar o renderizador depois não vaza.

## Ligações nos arquivos existentes (mínimas)

- **ID de skin**: `object SkinIds { fun isPack(id); fun packId(id); fun forPack(id) = "pack:$id" }`. Nenhum id antigo muda.
- **Teclado**: `KeyboardColors` ganha uma fábrica `KeyboardColors.forPack(pack, density)` e os campos mínimos para carregar
  `surfaceFactory`/`backdropDrawable`/`navigationBarColor` de pacote **ao lado** do enum (`backdrop` continua sendo o enum
  para as skins de hoje). `KeyboardSkin.of(id)` não pode quebrar: o service resolve `pack:<id>` **antes** de cair no enum,
  com fallback ao `SYSTEM` se o pacote não carregar (SKP-STO-03). `KeySurface.draw` precisa saber se a tecla é de letra ou
  de função: adicionar **método com implementação padrão** (`draw(..., role)` chamando o `draw` atual) para não tocar os 3
  pintores existentes.
- **Orbe**: `OrbStyle` continua enum; entra `OrbStyle.PACK` + um `CompositionLocal` (`LocalOrbPack`) com o pack ativo;
  `orbStyleFor("pack:x")` devolve `PACK` e o `OverlayService`/`ConfigScreen` provêm o pack. `drawOrb` ganha um ramo
  `PACK -> drawPackOrb(...)` **antes** dos outros (nenhum desenho existente muda). `LocalCenterInk` usa `orb.centerInk`.
- **Settings**: nenhum campo novo (`keyboardSkin`/`orbSkin` já são `String`). O `SettingsRepository` não valida o id.
- **UI** (arquivos novos): `ui/config/SkinPacksSection.kt` (botão importar + cartões + diálogos), plugado em **uma** linha
  em `ConfigFields` (seção Skins) para Teclado e Sobrepor.

## Renderização e desempenho

- **Teclado (IME)**: a face de cada (largura, altura, estado, role) vira `Bitmap` uma vez (`SpriteCache`, mesma chave que
  Glass/Glitch/RoseGold usam) → `drawBitmap` por tecla. 9-slice: renderizar o SVG em 9 recortes (clip + matriz) **dentro da
  geração do sprite**, nunca no `onDraw`. O tray vira bitmap do tamanho da view, refeito só em `onSizeChanged`.
  Carregar o pack no `onCreateInputView` é no caminho crítico do IME: parse do manifest + abrir os ≤ 5 SVGs de teclado
  (pequenos); frames de orbe **não** são tocados no IME.
- **Orbe**: `frameIndex = floor(tMs * fps / 1000) mod n`. Cache LRU por `(estado, frame, ladoPx)` com teto de bytes (~16 MB);
  ao entrar num estado, pré-aquecer em thread de fundo os frames dele (o `onDraw` nunca espera: se o frame não está pronto,
  desenha o anterior do mesmo estado ou o primeiro). Medir no emulador: tempo de `SVG.renderToCanvas` por frame.
- Em repouso nada anima no IME (política `keyboard-motion-events` vale; esta skin não tem movimento v1).

## Segurança (SKP-IMP-04)

- Zip: normalizar cada entrada (`File(name).normalize()` sem escapar de uma raiz virtual; rejeitar `..`, `/`, `\`, drive),
  contar bytes **ao descomprimir** (não confiar em `entry.size`), limite de entradas, ignorar diretórios e arquivos fora do
  vocabulário (aviso).
- SVG: verificação textual **antes** do parse (`<!DOCTYPE`, `<!ENTITY`, `<script`, `<foreignObject`, `href`/`xlink:href` que
  não comece com `#` ou `data:image/png|jpeg;base64,`) → erro. Depois parse + render de teste num bitmap pequeno com
  `try/catch` e orçamento de tempo. AndroidSVG não resolve arquivos externos por padrão — manter assim (não registrar
  `SVGExternalFileResolver`).
- Nada do pacote é executado nem vira nome de arquivo cru: o id é slug ASCII + hash; os arquivos instalados têm nomes
  fixos do vocabulário.

## Decisões

- **D1**: SVG renderizado por **AndroidSVG** (sem alternativa madura que cubra gradientes/clip no Android; Coil/svg usa a mesma). Limitações (sem `filter`) documentadas no FORMAT.md §4 e viram **aviso** no import.
- **D2**: `.zip` (não pasta) — SAF de pasta é pior e o usuário/IA gera zip com facilidade.
- **D3**: pacote **não** tinge as faces: a arte manda. Cores do manifest só para o que o app desenha (rótulos, centro, popups).
- **D4**: skin de pacote é **gratuita e nunca bloqueada** (é do usuário).
- **D5**: uma só aparência (sem claro/escuro), como Aero/Glitch/Rose Gold. Variantes ficam reservadas (§7).
- **D6**: relatório de import em PT via `tr()`; erros citam o nome do arquivo.

## Plano de testes

- **Unit (JVM)**: manifest (defaults, limites, `schemaVersion` futuro, campos desconhecidos), nomes de arquivo (todas as formas do FORMAT.md + rejeições), reader (zip-slip, bomb, duplicados, buracos, frames/total, wrapper folder, estado ausente, `idle` ausente), `frameIndex`, id/slug, **exemplos do FORMAT.md parseiam** (SKP-DOC-01).
- **Instrumented**: `SvgRender` (SVG de cor sólida → pixel esperado; gradiente; 9-slice mantém o canto); `SkinPackStore` (instalar/listar/remover/reimportar/atômico); `PackKeySurface` (pixel da face, pressed, role); `PackOrb` (frame por tempo/estado, fallback p/ idle); fallback quando o pacote some; UI Compose (importar com `Uri` de teste, relatório, cartão, remover).
- **Screenshot**: rotas novas na galeria com o pacote de exemplo (teclado expandido/recolhido, orbe nos 4 estados, aba Sobrepor/Teclado com o cartão); **todos os goldens existentes bit-idênticos** (`diff-screens.sh` sem `--approve` em nada antigo).
- **Real**: IME de verdade no emulador com `screencap` (memória: nunca `mInputShown`).

## Riscos

- **AndroidSVG não cobre o que o autor desenha** (filtros) → relatório de aviso + FORMAT.md §4 explícito; testar o exemplo com degradês.
- **Custo da orbe animada** → cache com teto + pré-aquecimento; medir.
- **Conflito com trabalho em andamento na `main`** (Settings/ConfigFields/ConfigScreen/ConfigTab modificados e não commitados por outra frente) → UI em arquivos novos, edições cirúrgicas, rebase antes do merge.
- **`KeySurface` mudando de assinatura** → só método com padrão.
