# Q12 — Layout refinado do app + ícones próprios da barra do teclado

## Contexto

Pedido do Yan (2026-09-28): deixar a interface do **app aberto** mais elegante, luxuosa e sofisticada,
e trocar os ícones "amadores" da **barra de atalhos do teclado** por um conjunto próprio que combine
com o resto. Cores ficam como estão (paleta Ateliê). Sem mockup: implementar, fotografar a galeria no
emulador e iterar (decisão do Yan nesta sessão).

## Decisões

- **Ícones valem no app E no teclado** (escolha do Yan): a prévia do Modo Teclado e o tour usam o
  `KeyFlowKeyboardView` real, então trocar só no editor do app faria a prévia "mentir". Fonte única:
  `ui/icons/KfIcons.kt` (paths SVG num grid 24×24, só dado), desenhada por `KfIcon` (Compose),
  `KfIconDrawer` (`android.graphics`, com cache de `Path`) e `KfIconDrawable` (ícone composto de
  `TextView`). O mapa botão → ícone é `ime/bars/BarIcons.kt`, usado pela `BarView` e pelos chips do
  `BarsEditor`.
- **Rótulos em texto continuam** (`MicVisuals.visuals`, `ShortcutKey.label`): são o nome acessível do
  botão e o que os instrumentados procuram (`By.text("🎙")`, `micLabelForTest`). Só o desenho troca.
- **Só a barra**: as teclas da grade Canvas (⇧ ⌫ ⏎ globo) e a notificação ficam como estão.
- **Config = ajustes (dois trilhos com cursor)** no lugar da engrenagem: o sol do tema já ocupa o
  desenho radial no cabeçalho.
- **Linguagem do layout** (sem trocar cor nem família tipográfica):
  - menos caixas: abas sem moldura (ícone + nome, traço curto sob a ativa sobre um fio), idioma e tema
    numa cápsula só, chips de status como etiqueta de fio com ponto;
  - hierarquia por tamanho e peso, não por cor: `AreaTitle` (16sp leve, tracking fechado),
    `SettingTitle`, `SettingSub` (Light, entrelinha folgada) e `Eyebrow` (caixa-alta espaçada em
    Grotesk no lugar do mono em negrito);
  - seleção "que sobe" no `Segmented` (papel claro com fio) em vez do bloco preto; interruptor vazado
    quando desligado; `KfSlider` de trilho fino com botão de papel e aro teal; `KfRadio` próprio;
  - cantos amplos (12–24dp) em cartões, modais e botões; `PrimaryButton`/`SecondaryButton`/`InfoNote`
    compartilhados em `ConfigFields.kt`;
  - Space Mono só em valor técnico (token, tempos, tamanhos), no peso Regular de verdade
    (`FontWeight.Light` → `space_mono_regular`) — os miúdos seguem no Bold do UAT.

## Achados durante a execução

1. **A rota `config-teclado` da galeria saía travada** (aba esmaecida, somente-leitura) desde que o
   gating da aba passou a exigir o KeyFlow como teclado ativo: a rota passava `keyboardEnabled` mas não
   `keyboardActive`. O golden mostrava a camada de bloqueio, não a tela. Corrigido na rota
   (`keyboardActive = keyboardEnabled`).
2. **Emoji no seletor do mic**: os chips "⏸ pausar"/"▶ retomar"/"✕ cancelar" do seletor por hold (IME) e
   do tour viravam emoji colorido. Agora ícone composto + texto.
3. **Tag em linha sem semântica mesclada**: a `InfoNote` nova punha a `testTag` na `Row` e o texto num
   filho — o `BarsEditorTest` achava o nó da tag, mas sem texto. `semantics(mergeDescendants = true)` na
   linha. (Chips, botões e linhas clicáveis já mesclam pelo `clickable`.)
4. **Teste com medida de layout fixa**: o `DocsPanelTest` cravava o respiro de 13dp antes de cada seção
   da Docs; o respiro virou `DOCS_SECTION_GAP` (22dp), usado pelo painel e pelo teste.

## Verificação (emulador `keyflow_api34`)

- Unit: **483/483** (novos: `KfIconsTest`, `BarIconsTest`).
- Instrumentado (`am instrument`, suíte inteira): **249 testes, 3 falhas** na 1ª rodada — os achados 3 e
  4 acima; depois da correção, `BarsEditorTest` + `DocsPanelTest` **22/22**.
- Goldens: 52 regenerados e revisados (config-*, onboarding*, bar-*, bars-editor*, keyboard-canvas-*,
  grids-editor-modal*, shortcut-key-picker*, tour-keyboard-mic*). Orbe, overlay, notificação e tour da
  orbe ficaram **bit-idênticos** (0 px); `tour-finger-*` (rotas provisórias) não aprovados.
  `diff-screens.sh`: 84/84.

## Verificação no A32 (`RX8T10DERKW`, Android 13)

- `installDebug installDebugAndroidTest` + `am instrument` (suíte inteira): **249 testes, 5 falhas**,
  todas do mesmo tipo e **alheias a esta mudança**: a config de barras salva do Yan (2026-09-28) não tem
  o mic (`candidates · paste · joystick · grade · Esc · toggleExpand`, espelhada), e cinco testes do
  teclado assumem o mic na barra — `KeyFlowKeyboardBarsTest.flagOnRendersBarViewsWithFactoryParityAndLiveMic`
  (`micLabelForTest` = null), `KeyFlowKeyboardCanvasGridTest.canvasGridDoesNotDisturbTheMicButton` e três
  do `KeyFlowKeyboardSelectorTest` (gravação nunca inicia). Os mesmos cinco passam no emulador, com a
  config de fábrica. Mesma família do achado 2 de `010-suite-no-aparelho` ("testes que dependem da faixa
  precisam montar a config"): esses testes deveriam montar uma barra com mic em vez de ler a do usuário.
- Prints da galeria no A32 (config-modo/voz/teclado/overlay/docs, onboarding) e do teclado real com a
  config do Yan: ícones novos na barra de verdade (colar, alavanca, grade, Esc, retrair).
- Efeito colateral medido: o `force-stop` da captura derruba o processo do KeyFlow e o Android volta o
  teclado padrão para o Samsung; restaurado com `ime set` no fim.

## Rodada 2 (2026-09-28, pedidos do Yan depois de ver no A32)

- **Navegação lateral** (`NavRail`, em `TabBar.kt`): as abas saíram do topo e ficaram empilhadas à
  esquerda — recolhida (padrão) só com ícones; a setinha do topo abre POR CIMA do painel com os nomes
  (sem refluir o conteúdo) e fecha ao escolher uma aba ou tocar fora. Motivo do Yan: vão entrar mais
  menus, e uma coluna cresce para baixo. Rota nova de galeria: `config-menu-aberto`.
- **Aba Modo sem rolagem no A32** (requisito do Yan — antes cabia): etiquetas de permissão em caixa
  normal e menores ("Microfone · concedido", 2 por linha), cartões mais justos, cena do tour 132→116dp.
  Conferido no app real do A32 (font_scale 1,1 × 1,3 do app, barras de status e navegação): cabe.
  `ModoPanelTest` passou a comparar "concedido/pendente" sem caixa.
- **Prévia**: a bolinha do tour virou uma mão apontando (`KfIcons.Hand`, papel com contorno de tinta; a
  ponta do indicador é o ponto do toque e "afunda" ao tocar). A moldura tem altura FIXA
  (`DEMO_FRAME_HEIGHT`) e o título do gesto reserva sempre 2 linhas — não salta mais entre gestos.
- **Respiro nas demais abas**: `SettingsGroup` (título da área fora, opções num cartão de papel com
  fios entre elas), `GroupItem`, `GroupDivider` e `GROUP_GAP` (32dp entre áreas). Voz, Teclado
  (novo grupo "Digitação"), Sobrepor e Docs (índice em uma coluna, cada seção um cartão) reorganizadas.
  O `Segmented` ajusta a fonte à largura (a origem da transcrição cortava "Créditos"/"Custom").
- **Prévia de tamanho do teclado**: passa a usar a largura do PAINEL (a da tela invadia o menu lateral);
  a altura segue a real. `TecladoPanelTest` reescrito para "não vaza do painel e fica centralizada".

## Suíte no aparelho em estado de fábrica (pedido do Yan)

- `FactorySettingsListener` (androidTest, registrado como `listener` no `<instrumentation>` do manifest
  de teste — vale no `am instrument` sem argumentos) + `DeviceSettingsSnapshot`: no início da rodada
  guarda a config do dono (DataStore + prefs do teclado + teclado padrão) num arquivo e zera tudo para a
  fábrica; no fim, devolve. Rodada que morre no meio: a próxima usa o arquivo como a config real.
- Achado: com o KeyFlow já de pé como teclado padrão quando a config é zerada, os testes do teclado
  esperavam 30 s e a view nunca anexava (no A32; no emulador o padrão é outro IME). Com outro teclado
  como padrão no início, o mesmo teste passa — então o snapshot troca o padrão para outro IME habilitado
  durante a rodada e devolve o KeyFlow no fim.
- `SettingsRepository.rawPreferencesOnce()`/`replaceRawPreferences()` (internal) existem só para isso.

## Verificação da rodada 2

- Unit **483/483**.
- Emulador (suíte inteira, estado de fábrica): **252 testes, 2 falhas** — `VozPanelTest` procurava o
  seletor de duração máxima sem rolar (a aba ficou mais longa); com `performScrollTo`, **10/10**.
- A32 (suíte inteira, estado de fábrica): **254 testes, 1 falha** — `KeyboardMicHoldMotionArtifactTest`
  (soltar em "retomar" não emitiu a ação); isolado, **3/3 verdes** → instabilidade de toque na rodada
  longa, não regressão. Os 5 testes do mic que falhavam pela config do Yan agora passam. Config do Yan e
  KeyFlow como teclado padrão conferidos depois de cada rodada.
- Goldens: 30 regenerados + 2 novos (`config-menu-aberto[-dark]`); `diff-screens.sh` 86/86.

## Rodada 3 (2026-09-28)

- Menu lateral mais estreito (Yan): 60 → 48dp recolhido, ícone no MESMO tamanho (21dp) — encolheram
  só as folgas (item 38dp de faixa do ícone, 44dp de altura, destaque mais justo; setinha 32dp). O
  painel e o resto do layout mantêm o respiro da rodada 2.
- A aba "Voz" passou a se chamar **Transcrição** (Yan) — no menu e nas frases que citam a aba (erro de
  provedor/modelo, Docs, porquê do microfone no onboarding). "Voz no aparelho" (seção da Docs sobre o
  modelo local) não é nome de aba e ficou.
- Verificação: instrumentados `com.keyflow.ui` **121/121** no emulador; 24 goldens `config-*`
  regenerados; `diff-screens.sh` 86/86.

## Rodada 4 (2026-09-28, pedidos do Yan)

- **Gatilho de grade** (`BarsEditor`): sob cada modo (Só grade, Sequência, Chord) uma frase curta do que
  ele envia; as três reservam 3 linhas, então trocar de modo não mexe no layout (`Segmented` ganhou o
  parâmetro `hint`). O prefixo desceu para baixo das opções e só aparece em Sequência e Chord; confirmar
  em "Só grade" não guarda um prefixo escondido. Rotas novas `bars-editor-grid-form[-sequencia]`.
- **Faixa de sugestões 30% menor**: peso 4 → 2,8 (`BarGeometry`) — cabe mais um botão normal sem apertar
  os outros. Ditado "Continua em 30%" entendido como "Diminua em 30%".
- **Prévia de tamanho** (aba Teclado): barra de fábrica sem a faixa de sugestões — cabia só rolando.
  Ditado "barra senha de sugestões" entendido como "barra sem a de sugestões".
- **Nome da aba no cabeçalho**: linha fixa abaixo de "KeyFlow", centralizada e menor. No A32 a linha
  custaria ~25dp (fonte do app ×1,3 e do sistema ×1,1 também a aumentam); as folgas de cima ficaram
  menores e o acréscimo caiu para ~12dp. **Pendente**: conferir no A32 se a aba Modo ainda cabe sem
  rolar. A simulação no emulador (densidade 450, fonte 1,1, 3 botões) não decide: nela a aba já passava
  ~10dp antes da mudança, e no A32 cabia.
- **Sem limite (∞)** no tempo de resposta e na duração máxima da gravação (0 = sem limite). O glifo "∞"
  da fonte saía miúdo: virou ícone (`KfIcons.Infinity`), com "Sem limite" para o leitor de tela. Na
  espera sem limite, saem o `callTimeout`, o `readTimeout` do client base, a rede de segurança do
  coordinator e o piso de 180s da origem Local.

### Verificação da rodada 4 (emulador `keyflow_api34`)

- Instrumentados `com.keyflow.ui` **124/124**; `BarsEditorTest` 16/16 (2 novos), `ConfigScreenTest` 10/10,
  `VozPanelTest` 11/11, `TecladoPanelTest` 7/7.
- Unit: suíte inteira **486/486**. Arquivos tocados (`BarGeometryTest`, `OverlayCoordinatorTest` 30, `TranscriptionClientTest`
  10, `EffectiveConfigTest` 13, `LocalRoutingTest` 5, `KfIconsTest`).
- Goldens: 12 pela faixa menor (teclado, barra expandida, aba Modo, tour do mic), 24 telas de config pelo
  cabeçalho, 2 da Transcrição pelo texto do ∞, 4 novos do formulário; `diff-screens.sh` **90/90**.

### Achados

- Com o cabeçalho mostrando o nome da aba, a Transcrição fica "Transcrição" (cabeçalho) logo acima da
  seção "Transcrição" do painel — decidir com o Yan se a seção muda de nome.
- O `readTimeout` fixo de 60s do client base já cortava a opção "2m" se o servidor ficasse calado mais de
  60s; não mexido (só o ∞ tira o `readTimeout`).
