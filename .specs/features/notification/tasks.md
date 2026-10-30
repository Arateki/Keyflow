# Notificação (aba Notificação) — Tasks

**Spec**: `spec.md` · **Design**: `design.md`
**Status**: spec e design em rascunho (2026-10-29), tasks **todas pendentes**. **Fora da v1** (só `main`, release seguinte, **depois de Fluxos**). Não toma a vez de frente aberta.

> **Para o agente executor**: leia `spec.md` + `design.md` + este arquivo e `.specs/codebase/TESTING.md`. Uma task por vez, na ordem do plano,
> testes primeiro, gate verde antes de avançar. Cada task é verificável sozinha. Os textos de UI que ela criar vão com ela (`tr("…")` +
> `i18n/en/Notificacao.kt` e `i18n/es/Notificacao.kt`). Desvio da spec: registrar na task, não em silêncio. **A NTF-T1 vem primeiro e
> pode parar a feature** (o D7 não tem plano B).

**Goal**: a notificação vira a orbe (40 dp, na skin escolhida) com, só enquanto há ação em curso, os botões que equivalem aos arrastos da orbe flutuante (Pausar/Retomar, Descartar); recebe 1, 2 e 3 toques (todos remapeáveis), controla a tela bloqueada só se a pessoa permitir,
dispensa sem mexer em configuração, e a aba Notificação deixa o "Em breve" com prévia, skins (inclusive as pagas e as de pacote) e as
interações; com provas de altura, tela bloqueada e dispensar nos aparelhos.

**Tech**: Kotlin · Compose · `RemoteViews` · `org.json` · DataStore · coroutines · AndroidSVG (já no projeto). **Nenhuma dependência nova.**

## Global Constraints

- **Gradle**: `flock /tmp/keyflow-gradle.lock /srv/dev/ferramentas/pool/bin/pool compilar <tarefas>`. Gates: `quick` = `testDebugUnitTest`; `build` = `assembleDebug`;
  `lint` = `:app:lintDebug`; `full` = `pool instrumentar <Classe…>` das classes afetadas (o `ANDROID_SERIAL=emulator-5554` já vem no ambiente do servidor).
- **Goldens**: `pool telas` (`verify-screens.sh` → `diff-screens.sh`). `--approve` copia **tudo**: restaure com `git checkout -- verification/golden/…` o que não for desta mudança.
  Golden existente fica bit-idêntico, **salvo** os `notification-*` e o `config-notificacao`, que mudam de propósito (T15), e o que o `STATE`/lições da task disser.
- **Aparelho do Yan (A32)**: nunca `connectedAndroidTest`; só `installDebug installDebugAndroidTest` + `am instrument` e **com `--confirmo-a32`**. **Nunca** definir PIN, mexer em bloqueio de tela nem
  desinstalar o app no aparelho dele (o bloqueio é roteiro manual, T18). Tab S10 Lite e Vaio: `adb` sem fio, sem `connectedAndroidTest`.
- **Convenções**: `collectAsStateOnMain`; nada de `runBlocking` novo; texto novo por `tr("…")`; `com.keyflow.orbe` e `com.keyflow.notificacao` (menos `notificacao/app`) **sem `android.*`**;
  teste que sobe o `OverlayService` o para no `@After`; nada de segredo nem de **texto ditado** na notificação.
- **Não alterar** a `ButtonStateMachine`, o `OverlayCoordinator`, o gesto/setas da orbe flutuante, nem o `schemaVersion` do pacote (continua 1).
- **Commit** em português, um por assunto, sem citar agentes de IA e sem trailers. Ao fechar a task: `Status` aqui, traceability no `spec.md` e lição em `.specs/project/licoes/` (T19 consolida).
- **Coordenação**: `VoiceOrb.kt`/`MascotOrb.kt` (T6), `OverlayService.kt` (T9–T11, também mexido por Fluxos FLX-T11..T15) e `ConfigScreen.kt` (T13) são tocados por outras frentes: edições cirúrgicas, rebase curto.
  `LugarDaOrbe` é compartilhado com Fluxos (quem chegar primeiro cria `orbe/Interacoes.kt`; a outra reusa).

## Execution Plan

```
Fase 0  prova:                  T1  (para tudo se a orbe não couber nos 48 dp)
Fase A  modelo e dados (JVM):   T2 ──► T3
                                T2 ──► T4
                                T2 ──► T5
Fase B  aparência:              T6 ──► T7 ──► T8
Fase C  integração Android:     (T4, T5, T7) ──► T9 ──► T10 ──► T11
Fase D  telas:                  (T3, T7, T10) ──► T12 ──► T13   (T11 ──► T13)
Fase E  fechamento:             T11 ──► T14      T13 ──► T15      T13 ──► T16
                                (T10, T11, T13) ──► T17 ──► T18 [YAN] ──► T19
Dependente de Fluxos:           (FLX-T28, T10, T12) ──► T20
```

Tudo que usa o emulador serializa (TESTING.md). A Fase A é JVM e pode ser entregue e revisada antes de qualquer tela. T6 e T2..T5 podem andar em paralelo (só unit/refatoração);
T8 depende de T7 (a interface da fonte de orbe).

---

## Fase 0 — Prova

### NTF-T1 — Prova de altura e de toque (descartável, primeiro) — **Status: pendente**
- **What**: protótipo mínimo, só em `androidTest`/debug: o `notif_orb.xml` do design §6 — a linha **`[botão][orbe][botão]`** de 48 dp — com uma orbe Ateliê **desenhada à mão** (círculo teal em `Bitmap`, 40 dp) e dois `ImageButton` de 48×48 dp com glifo vetorial, num `DecoratedCustomViewStyle`, `setCustomContentView` sem `BigContentView`,
  um clique por `PendingIntent.getService` na orbe e um em cada botão. Cobre as **quatro variações** de §6 (sem botão, só direito, esquerdo + direito, e o botão ausente `INVISIBLE` que mantém a orbe no centro). Posta com o serviço de primeiro plano de teste e mede, recolhida e expandida, em **emulador (API 34), Vaio TL10 (Android 13), Tab S10 Lite (Android 16) e A32** (a versão do Android dele: `adb shell getprop ro.build.version.release`):
  `visibleBounds` da orbe e de **cada botão**, captura do trecho, os quatro extremos do círculo, os alvos de toque (48×48 dp), a ausência de sobreposição entre orbe e botões, a largura em tela estreita (320 dp) e o tempo do clique ao serviço.
- **Where**: `app/src/androidTest/java/com/keyflow/notificacao/NotificacaoAlturaSpikeTest.kt`; resultado em `.specs/project/licoes/2026-10-NN-notificacao-altura.md`. Nada disto vai para o produto.
- **Depends on**: —. **Requirement**: NTF-05, NTF-06, NTF-43 (prova antecipada), NTF-50.
- **Done when**: relatório com captura e número por aparelho; para cada um "cabe / corta"; recolhido = expandido; o clique da orbe e o de cada botão chegam ao serviço. **Se algum aparelho corta a orbe ou um botão, a feature PARA aqui** e a decisão (Q9: orbe menor? só um botão? outro layout?) volta ao Yan antes da T2.
- **Tests**: instrumentado/UiAutomator. **Gate**: `pool instrumentar com.keyflow.notificacao.NotificacaoAlturaSpikeTest` (emulador/Vaio/Tab) e, no A32, `installDebug installDebugAndroidTest` + `am instrument` **com `--confirmo-a32`**. **Commit**: `test(notificação): prova de altura e de toque da orbe na notificação`.

---

## Fase A — Modelo e dados (JVM puro)

### NTF-T2 — Interações da orbe, configuração e "copiar" `[P]` — **Status: pendente**
- **What**: `com.keyflow.orbe` (`LugarDaOrbe`, `Interacao`, `SuporteDeInteracoes` com `suporta`/`naoSuportadas`/`motivo`); `com.keyflow.notificacao.Modelo.kt` (`AcaoDaOrbe` com `GravarEnviar`, `ConfigNotificacao` com `acao1Toque/2Toques/3Toques`, `ConfigEfetiva` com `iniciaGravacao`, `efetiva(config, settings)`, mapa `OrbShortcutAction → AcaoDaOrbe`).
  Regra no `ArchitectureRulesTest`: `orbe/` e `notificacao/` (menos `notificacao/app/**`) sem `android.*`/compose/cofre.
- **Where**: `app/src/main/java/com/keyflow/orbe/Interacoes.kt`, `app/src/main/java/com/keyflow/notificacao/Modelo.kt`; testes em `app/src/test/.../orbe/` e `.../notificacao/`.
- **Depends on**: T1 (aprovada). **Requirement**: NTF-08, NTF-10 (remapeável, D16), NTF-12 (efetiva), NTF-39 (modelo), NTF-41.
- **Done when**: unit — a notificação suporta `TOQUE_1..3` e declara não suportadas as outras quatro, cada uma com motivo; a flutuante suporta todas; `efetiva` com "copiar" devolve skin e as ações efetivas de 1/2/3 toques das `Settings` (Alternar: 1 = `GravarEnviar`; PTT: 1 = `shortcut1Tap`, inclusive `NONE`, sem inventar gravação) e `naoTem` (segurar e arrastos, com o destino de cada um); sem "copiar" devolve os valores próprios (qualquer dos três toques pode ser `GravarEnviar` ou `Nenhuma`); `iniciaGravacao` é falso quando nenhum toque grava;
  o catálogo é `Nenhuma`/`GravarEnviar`/`RetrairExpandirTeclado`/`TrocarTeclado`/`LerTela` (+ `RodarFluxo`/`Desconhecida` reservadas) — **sem** pausar/cancelar, que são botões; a regra de arquitetura falha se um arquivo de `orbe/` ou `notificacao/` importar `android.` (e não falha em `notificacao/app/`).
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(notificação): modelo de interações da orbe e configuração da notificação`.

### NTF-T3 — Codec e persistência — **Status: pendente**
- **What**: `CodecNotificacao` (`org.json`, `v:1`, design §4) tolerante; chave `notification_config_json` ao lado de `BARS_CONFIG_JSON` (`SettingsRepository.kt:876`); `configNotificacaoFlow()`, `configNotificacaoOnce()` (leitura leve, sem cofre) e `atualizarNotificacao(f)` (um `dataStore.edit`, sobre o valor atual).
- **Where**: `notificacao/CodecNotificacao.kt`, `data/SettingsRepository.kt`; testes `CodecNotificacaoTest`, `CodecNotificacaoPropertyTest`, `CodecNotificacaoFuzzTest` (unit) e `NotificacaoRepositoryTest` (instrumentado, padrão do `SettingsRepositoryTest`).
- **Depends on**: T2. **Requirement**: NTF-10 (dados dos três toques), NTF-15 (opção persistida), NTF-36, NTF-37, NTF-38, NTF-39.
- **Done when**: unit — padrões com chave ausente ou JSON malformado; `taps.1` ausente usa o padrão `record_toggle`, mas `none` ou desconhecida explícitos permanecem; zero ou vários `record_toggle` fazem round-trip; round-trip com as ações de 1, 2 e 3 toques (inclusive 1 toque remapeado), skin `pack:` e uma ação desconhecida (`flow:x`, `novo`) preservada; padrão do 1 toque = `record_toggle`; campo desconhecido ignorado; skin inexistente → `atelie`; fuzz (~10 mil entradas) nunca lança;
  instrumentado — leitura sem chave **não grava**; primeira alteração grava; duas alterações concorrentes ficam ambas; salvar as `Settings` **não** toca a chave e vice-versa.
- **Tests**: unit + instrumentado. **Gate**: `quick` + `pool instrumentar com.keyflow.data.NotificacaoRepositoryTest`. **Commit**: `feat(notificação): configuração persistida em JSON tolerante`.

### NTF-T4 — Rajada de toques, resolução da interação e estado visual — **Status: pendente**
- **What**: `RajadaDeToques` (`toque()`, `fechar()`), `ResolvedorDeInteracao.resolver(...) → Comando` (design §5, inclusive overflow e portões de estado), `EstadoVisual` (`ButtonState` + Visão → aparência, centro, glifo, descrição `tr`; design §6) e a **regra dos arrastos** (design §3.1): `orbe/ArrastosDaOrbe.kt` (a tabela por estado em Alternar) e `notificacao/BotoesDaNotificacao.kt` (`Botao`, `Botoes`, `para(estado, visaoAtiva)`).
- **Where**: `notificacao/{RajadaDeToques,ResolvedorDeInteracao,EstadoVisual,BotoesDaNotificacao}.kt`, `orbe/ArrastosDaOrbe.kt`; testes homônimos (`ArrastosDaOrbeTest`, `BotoesDaNotificacaoTest`).
- **Depends on**: T2. **Requirement**: NTF-07, NTF-09, NTF-10, NTF-11, NTF-48.
- **Done when**: unit — 1, 2, 3 e 5 toques; a janela reinicia a cada toque; fecha uma vez; cada toque executa **a ação da sua interação** (o toque remapeado para `GravarEnviar` grava, esteja em 1, 2 ou 3; um toque em `Nenhuma` não faz nada; **zero toques em `GravarEnviar` é aceito** e o resolvedor não inventa a ação); `RetrairExpandirTeclado` só com teclado visível; **com a Visão ativa só `GravarEnviar` age**; `NEAREST_DOWN` com 5 toques cai no 3, ou no 2 se o 3 é `Nenhuma` (o 1 toque fora do overflow); `CANCEL` → `Nada`; `Desconhecida`/`RodarFluxo` sem Fluxos → `Nada`; a tabela de estados do design §6 tem um teste por linha, com a descrição traduzida;
  **arrastos**: a tabela de `ArrastosDaOrbe` por estado (design §3.1); `BotoesDaNotificacao`: gravando → Pausar·Descartar, pausado → Retomar·Descartar, transcrevendo → só Descartar, cancelando → só Desfazer, Visão → só Encerrar, ocioso/erro/pendente → nenhum; `ABRIR_APP` não gera botão; **o teste falha se um efeito da tabela (exceto `ABRIR_APP`) não produzir botão**.
- **Tests**: unit (relógio virtual). **Gate**: `quick`. **Commit**: `feat(notificação): contagem de toques e resolução das interações da orbe`.

### NTF-T5 — Visibilidade ("dispensada") e portão da tela bloqueada — **Status: pendente**
- **What**: `VisibilidadeDaNotificacao` (`PARADA/VISIVEL/DISPENSADA`, eventos, design §9) e `PortaoDeToque.avaliar(bloqueada, permitir, precisaDeActivity)` (design §10).
- **Where**: `notificacao/{VisibilidadeDaNotificacao,PortaoDeToque}.kt`; testes homônimos (inclui propriedade).
- **Depends on**: T2. **Requirement**: NTF-16 (regra, vale para a orbe e para os botões), NTF-18 (regra), NTF-41, NTF-49 (portão dos botões).
- **Done when**: unit — a tabela de §9 inteira; propriedade: nenhuma sequência de eventos posta em `DISPENSADA` fora de `Necessaria`/`AbaAberta`, e `SessaoTerminou` sempre leva a `PARADA`; `Portao`: bloqueada+desligada → `RecusarBloqueado`; bloqueada+ligada+Activity → `RecusarPrecisaDesbloqueio`; bloqueada+ligada+sem Activity → `Aceitar`; desbloqueada → `Aceitar`.
- **Tests**: unit. **Gate**: `quick`. **Commit**: `feat(notificação): visibilidade ao dispensar e portão da tela bloqueada`.

---

## Fase B — Aparência

### NTF-T6 — Renderizar a orbe em bitmap (Ateliê e as pagas) `[P]` — **Status: pendente**
- **What**: **spike primeiro**: confirmar que `drawOrb` (Ateliê, Aero, Glitch, Rose Gold) e `drawMascotOrb` (Ouvi) desenham sobre `CanvasDrawScope` de um `Bitmap` ARGB transparente (inclusive o `BlendMode.Plus` do Glitch). Depois: tornar esses desenhos `internal`, expor
  `renderOrbBitmap(style, state, ladoPx, centro)` com **tempo fixo e pose fixa** por estado, e extrair o traçado dos glifos (pausa, lixeira, X) para `DrawScope` compartilhados, mais o **olho** (a partir do `KfIcons.TabVisao`). A orbe flutuante continua usando os mesmos desenhos.
- **Where**: `ui/orb/VoiceOrb.kt`, `ui/orb/MascotOrb.kt`, `notificacao/app/RenderizadorDeOrbe.kt`; testes `RenderizadorDeOrbeTest`.
- **Depends on**: —. **Requirement**: NTF-25, NTF-29.
- **Done when**: instrumentado — cada skin × estado (4 + glifos) gera bitmap não vazio, determinístico (duas chamadas iguais) e com o tamanho pedido; o Glitch não "soma com o fundo"; **todos os goldens da orbe/Sobrepor/onboarding/tour existentes bit-idênticos** (`pool telas`, 0 divergências); a extração dos glifos não muda um pixel da orbe flutuante.
- **Tests**: instrumentado + goldens. **Gate**: `build` + `pool instrumentar com.keyflow.notificacao.RenderizadorDeOrbeTest` + `pool telas`. **Commit**: `refactor(orbe): desenho da orbe e dos glifos utilizável fora do Compose`.

### NTF-T7 — Provedor de aparência: Sistema, cache, orçamento, claro/escuro e fallback — **Status: pendente**
- **What**: `AparenciaDaNotificacao`/`ProvedorDeAparencia` (design §7): resolve `SkinEfetiva` (`orbStyleFor(id, skinsUnlocked)`, Sistema, pacote), skin **Sistema** (círculo liso na cor de destaque do sistema), cache `LruCache` por bytes com a chave e as invalidações do design §7, `OrcamentoDeBitmaps`
  (lado ≤ 192 px, soma ≤ 1 MB, alvo 256 KB), modo noturno do sistema (`Configuration.uiMode`), fallback Ateliê → mínima, tudo fora da thread principal. Define a interface `FonteDeOrbe` que o pacote implementa (T8).
- **Where**: `notificacao/OrcamentoDeBitmaps.kt` (puro), `notificacao/app/{AparenciaDaNotificacao,ProvedorDeAparencia}.kt`; testes `OrcamentoDeBitmapsTest` (unit), `ProvedorDeAparenciaTest` (instrumentado).
- **Depends on**: T6. **Requirement**: NTF-24, NTF-26, NTF-27, NTF-28, NTF-30, NTF-34 (interface).
- **Done when**: unit — lado = `min(192, round(40·densidade))` para 1×…4×; soma de bytes de conteúdo + pública ≤ 1 MB em todas as densidades e ≤ 256 KB nas de teste; instrumentado — skin paga **sem** o direito devolve o Ateliê e **não** apaga a escolha (o direito volta → volta a skin);
  Sistema muda de cor com o modo noturno; Ateliê pinta fundo escuro/claro; as pagas não pintam fundo; skin inexistente e falha de render caem no Ateliê; a chave do cache muda com skin/estado/lado/modo; `SkinPackStore.changes` e `skinsUnlockedFlow` invalidam.
- **Tests**: unit + instrumentado. **Gate**: `quick` + `pool instrumentar com.keyflow.notificacao.ProvedorDeAparenciaTest`. **Commit**: `feat(notificação): aparência da notificação por skin com cache e orçamento de bitmaps`.

### NTF-T8 — Pacote com a seção `notification` — **Status: pendente**
- **What**: o contrato do design §8: `PackFileKind.NotificationFace(state)` em `SkinPackNames`; seção `notification` (`ink`, `background` opaco) em `SkinPackManifest` (conhecida em `knownTop`); `SkinPackReader` (validação, relatório "notificação: N estados", erro "não tem orbe, teclado nem notificação", aviso se a seção não tem arquivos);
  `SkinPack` (`hasNotification`, `notificationFiles`); `SkinPackStore` (instala/lista/abre); `PackPreview` (miniatura); `OrbeDePacote` (a `FonteDeOrbe` da T7, incluindo o **primeiro quadro** da orbe para "copiar" de pacote só de orbe, NTF-34).
  **Em lockstep**: o `FORMAT.md` (§1, §2, §3, §6, §7) com o texto do design §8, o `SkinPackFormatDocTest` e o `SkinFormatAssetTest` verdes, e o pacote de exemplo (`skin-packs/exemplo-neon/` + `scripts/build-skin-pack.sh`) com as 4 peças, importando **sem avisos**. A seção "Pacote de skin" da aba Docs ganha a nota.
- **Where**: `skins/{SkinPackNames,SkinPackManifest,SkinPackReader,SkinPack,SkinPackStore,PackPreview,OrbeDePacote}.kt`, `.specs/features/skin-packs/FORMAT.md`, `skin-packs/exemplo-neon/`, `scripts/build-skin-pack.sh`; testes dos pacotes (estendidos) e fuzz.
- **Depends on**: T7. **Requirement**: NTF-31, NTF-32, NTF-33, NTF-34, NTF-35.
- **Done when**: unit — pacote só de notificação instala e não tem orbe nem teclado; combinações (orbe+teclado, orbe+notificação, as três); estado ausente usa `idle`; `notification.background` translúcido e `ink` mal escrito são **erro** com o caminho do campo; seção sem arquivos → aviso; `viewBox` não quadrado → aviso;
  versão "antiga" (o parser sem a seção, simulado pelo teste) importa o mesmo pacote só com os avisos de desconhecido; os exemplos do FORMAT.md parseiam; a cópia embutida para o agente é igual byte a byte; o fuzz do pacote com as peças novas nunca lança;
  instrumentado — `SkinPackStoreTest` instala/remove/reimporta o pacote com notificação; `OrbeDePacote` devolve o pixel esperado de um SVG de cor sólida; o pacote só de orbe, em "copiar", desenha o 1º quadro; a aba Sobrepor/Teclado continuam listando só quem tem a peça deles.
- **Tests**: unit + instrumentado. **Gate**: `quick` + `pool instrumentar com.keyflow.skins.SkinPackStoreTest com.keyflow.skins.OrbeDePacoteTest`. **Commit**: `feat(skins): seção notification no pacote de skin`.

---

## Fase C — Integração com o app

### NTF-T9 — Layout e construtor da notificação (substitui `RecordingNotification`) — **Status: pendente**
- **What**: `res/layout/notif_orb.xml` (design §6) e `NotificacaoDaOrbe.construir(...)`: `RemoteViews` com a linha `[botão][orbe][botão]` (design §6): a orbe e o `Chronometer` (base deslocada na retomada: REC-06), **os botões do estado** (`BotoesDaNotificacao`, glifos vetoriais tingidos pela skin, `INVISIBLE` quando ausentes), o clique da orbe e **um `PendingIntent` por botão** (`ACTION_NOTIF_PAUSE_RESUME`/`CANCEL`/`UNDO`, `requestCode` próprio), `contentIntent` (`getActivity`), `deleteIntent`, `VISIBILITY_PRIVATE` + `publicVersion` (orbe em repouso e **sem botões** / estado real com botões, conforme a opção),
  descrição de acessibilidade por estado, `setForegroundServiceBehavior(IMMEDIATE)` (confirmar o texto da doc, L12), **sem** `setCustomBigContentView` e **sem parâmetro de texto**. Remove `RecordingNotification.kt`, `notif_recording.xml` e os drawables dos chips que ficarem sem uso; `VisionProjectionSession` recebe o novo construtor pela mesma lambda.
- **Where**: `notificacao/app/NotificacaoDaOrbe.kt`, `res/layout/notif_orb.xml`, `overlay/OverlayService.kt` (pontos que chamam `recordingNotification`), `vision/VisionProjectionSession.kt` (só a lambda), `app/src/androidTest/.../RecordingNotificationTest.kt` (reescrito como `NotificacaoDaOrbeTest`).
- **Depends on**: T4, T5, T7. **Requirement**: NTF-05, NTF-06, NTF-07, NTF-13, NTF-17, NTF-20, NTF-21 (o construtor da mínima), NTF-29, NTF-45, NTF-48, NTF-50.
- **Done when**: instrumentado — `PRIVATE` + `publicVersion` (orbe em repouso, sem `Chronometer` e **sem botões** com a opção desligada; estado real com botões ligada); um clique na orbe e **um por botão presente**, cada um com a ação dedicada; os botões de cada estado batem com a tabela de §6 e a orbe fica no centro (botão ausente `INVISIBLE`); contraste dos glifos legível em claro e escuro nas 7 skins; sem `bigContentView`; `ongoing`; descrição por estado nos 8 estados e em cada botão (traduzida); soma dos bitmaps ≤ 1 MB e o tamanho serializado (`Parcel`) da `Notification` medido e registrado, para a spec citar o limite do Binder (L6); `NotificacaoSemTextoTest` varre descrição, extras e `RemoteViews` e não acha texto fora das chaves `tr`;
  o `Chronometer` conta no `RECORDING`, congela em `PAUSED`/`CANCELLING`; a mínima tem canal `keyflow_overlay_min`, sem ação e `SECRET`. `OverlayServiceCancelTest`/`OverlayServiceStopTest`/`OverlayServiceVisionTest` verdes. `assembleDebug` e `lintDebug` verdes.
- **Tests**: instrumentado. **Gate**: `build` + `lint` + `pool instrumentar com.keyflow.notificacao.NotificacaoDaOrbeTest com.keyflow.overlay.OverlayServiceCancelTest com.keyflow.overlay.OverlayServiceStopTest com.keyflow.overlay.OverlayServiceVisionTest`. **Commit**: `feat(notificação): a notificação passa a ser a orbe e os botões dos arrastos`.

### NTF-T10 — Serviço: toques e ações — **Status: pendente**
- **What**: `ACTION_NOTIF_ORB_TAP` e as ações **dedicadas dos botões** (`ACTION_NOTIF_PAUSE_RESUME`, `ACTION_NOTIF_CANCEL`, `ACTION_NOTIF_UNDO`) em `onStartCommand`; `NotificacaoHost.aoTocarNaOrbe()` e `aoTocarNoBotao(botao)` (design §5; o botão passa pelo **mesmo portão**, **não** entra na rajada, confere o estado e ignora o botão atrasado): `PortaoDeToque` com `KeyguardManager` injetável, `RajadaDeToques` no `serviceScope` (janela `tapBurstWindowMs`, gancho `…ForTest`), `ResolvedorDeInteracao`, execução reaproveitando
  `handleKeyboardMicTap`/`handlePauseResume`/`handleCancel`/`onUndoCancel`/`visionFlow`; extração de `executarAcaoDeAtalho(acao)` de `handleOrbShortcut` (a orbe flutuante usa a mesma função); checagem da permissão de sobreposição nas ações de Activity (L10); `Haptics.tick`; leitura leve e ao vivo de `configNotificacaoFlow()`.
- **Where**: `overlay/OverlayService.kt`, `notificacao/app/NotificacaoHost.kt`; teste `OverlayServiceNotificacaoTest`.
- **Depends on**: T3, T4, T5, T9. **Requirement**: NTF-09, NTF-10, NTF-11, NTF-14, NTF-16, NTF-38, NTF-48, NTF-49.
- **Done when**: instrumentado (serviço real, portas e relógio falsos) — com "Gravar / enviar" no 1 toque (padrão): ocioso inicia, gravando envia, pausado envia, cancelando descarta já, com a Visão encerra; **remapear "Gravar / enviar" para 2 ou 3 toques** grava por ele e o 1 toque em "Nenhuma" não faz nada; configuração sem toque de gravação é aceita; copiar o PTT com 1 = Nenhuma não grava; 3 toques com "Nenhuma" não faz nada; 5 toques cai conforme o overflow;
  **botões**: Pausar pausa e Retomar retoma, Descartar abre a janela de desfazer (e, transcrevendo, cancela a transcrição), Desfazer volta a pausado, Encerrar fecha a Visão; um botão que chega depois de o estado mudar é ignorado sem erro; o botão não conta como toque;
  `ArrastosDaOrbeConformidadeTest` (instrumentado): para cada estado, arrasta a `OrbButtonView` real nas três direções e o efeito bate com `ArrastosDaOrbe`;
  **bloqueada + opção desligada**: nenhum toque é contado e **nenhum botão age**, nenhuma gravação começa, o aviso sai; bloqueada + ligada: grava; bloqueada + ligada + "Ler a tela": recusa com "precisa do aparelho desbloqueado"; sem sobreposição: aviso de L10; trocar a ação na config vale no toque seguinte, sem reiniciar o serviço;
  a orbe flutuante e o `handleOrbShortcut` se comportam como antes (`OrbButtonViewTest`, `SettingsShortcutTest` verdes).
- **Tests**: instrumentado. **Gate**: `pool instrumentar com.keyflow.overlay.OverlayServiceNotificacaoTest com.keyflow.overlay.OrbButtonViewTest`. **Commit**: `feat(notificação): toques e botões da notificação gravam, pausam e descartam`.

### NTF-T11 — Serviço: dispensar, mínima, canais, permissão, modo noturno e coalescência — **Status: pendente**
- **What**: `PostadorDaNotificacao` (design §9, §11): coalescência (último vence), canais (`keyflow_overlay` + `keyflow_overlay_min`), notificação **mínima** (com `mostrar = false`, no `startForeground` sem aparência pronta, e no fallback total), `ACTION_NOTIF_DISMISSED` → `VisibilidadeDaNotificacao`, eventos `Necessaria` nas entradas de `ensureForeground()`/início de gravação/Visão,
  `ACTION_NOTIF_SHOW` (aba aberta, só com o serviço rodando), `areNotificationsEnabled()`, `onConfigurationChanged` → re-post, troca de `Settings.themeDark` por modo noturno do sistema, aquecimento do cache no `onCreate`. `promoteToForeground` pede a notificação inicial ao postador. **Confirmar na fonte do AOSP** a taxa de `notify` (L11) e anotar no `STATE`/lições.
- **Where**: `notificacao/app/PostadorDaNotificacao.kt`, `overlay/OverlayService.kt`; testes `PostadorDaNotificacaoTest` (JVM, com relógio virtual) e `OverlayServiceNotificacaoTest` (estendido).
- **Depends on**: T5, T9, T10. **Requirement**: NTF-18, NTF-19 (lado do serviço), NTF-20, NTF-21, NTF-22, NTF-23, NTF-28, NTF-42.
- **Done when**: unit — 20 pedidos em 1 s viram poucos `notify` e o último estado é o que fica; instrumentado — dispensada (simulando o `deleteIntent`) **não** re-posta em pausa/envio e **nenhuma** preferência do DataStore muda; `Necessaria` (nova gravação, `ACTION_START_OVERLAY`, `ACTION_VISION`) e `ACTION_NOTIF_SHOW` repõem; `mostrar = false` posta só a mínima (sem orbe, sem ação) e o `startForeground` nunca recebe nulo;
  permissão negada: nada falha; mudar o modo noturno do sistema re-posta; render que falha cai no Ateliê e depois na mínima, sem derrubar o serviço; o modo só-teclado ainda encerra o primeiro plano ocioso (`maybeStopWhenIdle` intacto).
- **Tests**: unit + instrumentado. **Gate**: `quick` + `pool instrumentar com.keyflow.overlay.OverlayServiceNotificacaoTest com.keyflow.overlay.OverlayServiceStopTest`. **Commit**: `feat(notificação): dispensar sem mexer em configuração, notificação mínima e re-posts coalescidos`.

---

## Fase D — Telas

### NTF-T12 — Painel: estado, aparência e interações — **Status: pendente**
- **What**: `NotificacaoPanel` (design §13), seções **Estado** (permissão com o chip do `ModeCards`, interruptor "Mostrar a notificação da orbe", linha de quando existe), **Aparência** (o interruptor "Copiar a configuração da orbe principal", a lista de skins: `SkinPacksSection` com a superfície `NOTIFICACAO`, paga travada sem o direito) e **Interações**
  (seletores de **1, 2 e 3 toques** com o catálogo da NTF-11 sobre `ShortcutActionSheet`; somente leitura com "copiar"; o aviso "Nenhum toque inicia a gravação" quando for o caso, sem bloquear; o aviso de **Ler a tela / Trocar o teclado** (item em aberto D14); a confirmação genérica ao substituir qualquer ação atribuída (D16); a lista do que a notificação **não tem**, dizendo onde cada interação foi parar — botões, corpo ou nada —; e a nota dos botões automáticos). Grava por `atualizarNotificacao`. **A aba ainda não sai do "Em breve"** (T13).
- **Where**: `ui/config/{NotificacaoPanel,NotificacaoSkins,NotificacaoInteracoes}.kt`, `ui/config/SkinPacksSection.kt` (a superfície nova), `i18n/{en,es}/Notificacao.kt`; testes `NotificacaoPanelTest`.
- **Depends on**: T3, T7, T10. **Requirement**: NTF-02, NTF-10, NTF-11 (catálogo na tela), NTF-12, NTF-22 (aviso), NTF-24, NTF-40, NTF-45.
- **Done when**: instrumentado — permissão negada mostra a explicação e o pedido; desligar "mostrar" grava `mostrar=false`; com "copiar" ligado skin e ações aparecem somente leitura (Alternar: 1 toque = Gravar/enviar; PTT: `shortcut1Tap`, inclusive Nenhuma, sem inventar gravação) e a lista diz onde foram parar segurar (não existe), ↑ e lateral (botões) e ↓ (corpo); desligar "copiar" deixa editar e mantém os valores copiados; escolher "Gravar / enviar" no 2 toques e "Nenhuma" no 1 grava as ações; sem nenhum toque em "Gravar / enviar" o aviso aparece e **nada é bloqueado**; mudar qualquer ação atribuída nomeia a anterior e o toque, e cancelar não grava; o aviso de Ler a tela / Trocar o teclado aparece ao lado das duas ações;
  skin paga travada sem o direito; pacote com notificação aparece e um pacote só de orbe **não**; `TranslationsTest` verde; alvos ≥ 48 dp; cada ação com `contentDescription`.
- **Tests**: instrumentado (Compose) + unit (`TranslationsTest`). **Gate**: `quick` + `pool instrumentar com.keyflow.ui.config.NotificacaoPanelTest`. **Commit**: `feat(notificação): painel com estado, aparência e interações`.

### NTF-T13 — Painel: prévia, tela bloqueada, sistema e ligar a aba — **Status: pendente**
- **What**: a **Prévia** (`NotificacaoPrevia`: a `RemoteViews` real **com os botões do estado** por `AndroidView`, seletor de estado, tempo congelado), a seção **Tela bloqueada** (interruptor, padrão desligado, com o texto da consequência), **Sistema** (botão que abre os ajustes do canal) e o `ACTION_NOTIF_SHOW` ao painel ficar visível (só se o serviço já roda).
  `ConfigScreen` (`:512-514`) passa a chamar `NotificacaoPanel` no ramo `NOTIFICACAO` (mantém `FLUXOS`/`COMPUTADOR` no cartão enquanto Fluxos não ligar); ajustar `ConfigScreenTest.deferredTabsShowComingSoonCardWithoutControls` e `ConfigTabTest` se preciso. `docsSectionFor` continua `null` até a T16.
- **Where**: `ui/config/{NotificacaoPanel,NotificacaoPrevia}.kt`, `ui/config/ConfigScreen.kt`, `app/src/androidTest/.../ConfigScreenTest.kt`.
- **Depends on**: T11, T12. **Requirement**: NTF-01, NTF-03, NTF-04, NTF-15, NTF-19, NTF-23 (ajustes do canal), NTF-40.
- **Done when**: instrumentado — a aba mostra `NOTIFICACAO_PANEL_TAG` e o cartão "Em breve" some só dela; a prévia muda com o estado (e mostra Pausar·Descartar, Retomar·Descartar, Descartar, Desfazer ou nenhum botão), a skin e o modo; o interruptor de tela bloqueada grava e re-posta; "Ajustes do sistema" dispara o intent do canal; abrir a aba com o serviço rodando e a notificação dispensada a repõe, e **sem** serviço não o inicia; `assembleDebug` verde.
- **Tests**: instrumentado. **Gate**: `build` + `pool instrumentar com.keyflow.ui.config.ConfigScreenTest com.keyflow.ui.config.NotificacaoPanelTest`. **Commit**: `feat(notificação): aba Notificação sai do Em breve`.

---

## Fase E — Fechamento

### NTF-T14 — Provas no aparelho: altura, tela bloqueada e dispensar — **Status: pendente**
- **What**: os três testes de design §12 (`NotificacaoAlturaTest`, `NotificacaoTelaBloqueadaTest`, `NotificacaoDispensarTest`) com a notificação **real**, — a altura mede a orbe **e os botões** nos estados com botão, e o bloqueio testa também **os botões** — mais as verificações de L10, L11 e L12 (Activity a partir do serviço com e sem sobreposição, que alimenta o **item em aberto D14**; 20 mudanças em 1 s; atraso do primeiro plano) e as decisões abertas: o `Toast` aparece sobre o bloqueio? (A12); o `deleteIntent` dispara no 14+? (plano B de §9); o Samsung desenha igual?
  Resultados em `.specs/project/licoes/`; ajustes pequenos que a prova mandar (aviso, plano B) entram aqui; mudança maior volta ao Yan.
- **Where**: `app/src/androidTest/java/com/keyflow/notificacao/`; `.specs/project/licoes/2026-10-NN-notificacao-provas.md`.
- **Depends on**: T11 (T13 para a prova que usa a aba). **Requirement**: NTF-06, NTF-16, NTF-17, NTF-18, NTF-20, NTF-43, NTF-49, NTF-50.
- **Done when**: emulador — os três testes verdes (bloqueio por deslizar com `sleep()`/`wakeUp()`, sem PIN); Tab S10 Lite e Vaio — altura e dispensar (o Vaio, Android 13, **não** dispensa); A32 — altura e, **só se o Android dele for ≥ 14** (a NTF-T1 mede a versão), dispensar, pelo `am instrument` **com `--confirmo-a32`**; se for < 14 a notificação **não dispensa**, como no Vaio; **o bloqueio no A32 fica para o roteiro da T18**; cada critério de §12 registrado com número e captura.
- **Tests**: instrumentado/UiAutomator. **Gate**: `pool instrumentar com.keyflow.notificacao.NotificacaoAlturaTest com.keyflow.notificacao.NotificacaoTelaBloqueadaTest com.keyflow.notificacao.NotificacaoDispensarTest` (emulador) e, nos aparelhos, `am instrument` escopado. **Commit**: `test(notificação): provas de altura, tela bloqueada e dispensar nos aparelhos`.

### NTF-T15 — Rotas de galeria e goldens — **Status: pendente**
- **What**: as rotas do design §13 (as 6 da aba — inclusive `config-notificacao-sem-gravar` — e as 7 `notification-orb-*`, estas **com os botões do estado**), claro e escuro, fixtures determinísticos (tempo congelado, ids fixos); **remove** `notification-recording`/`notification-paused` antigas e regenera `config-notificacao` (o "Em breve" sai).
- **Where**: `app/src/debug/java/com/keyflow/ui/gallery/RouteCatalog.kt`, `verification/golden/`.
- **Depends on**: T13. **Requirement**: NTF-44.
- **Done when**: `pool telas` gera as rotas novas × 2 temas; `--approve` e depois `git checkout -- verification/golden/…` do que não é desta task; os demais ficam bit-idênticos; revisão visual das imagens (sem corte em 360 dp; a orbe inteira nas `notification-orb-*`).
- **Tests**: screenshot. **Gate**: `pool telas`. **Commit**: `test(notificação): rotas de galeria e goldens`.

### NTF-T16 — Docs e site — **Status: pendente**
- **What**: seção `aba-notificacao` na `DocsPanel` (o que é a orbe da notificação, 1/2/3 toques e o que não existe, copiar, tela bloqueada e o aviso, dispensar e quando volta, notificação mínima, skins e pacote, a permissão), `docsSectionFor(NOTIFICACAO)` aponta para ela; **reescreve** o texto antigo da notificação (`DocsPanel.kt:318`, `:331`, `:363-370`: botões, pausar/enviar/cancelar); traduções; `website/_fonte/doc.json` regenerado.
- **Where**: `ui/config/DocsPanel.kt` (+ o arquivo de seções), `i18n/{en,es}/Docs.kt`, `website/_fonte/doc.json`.
- **Depends on**: T13. **Requirement**: NTF-46.
- **Done when**: `DocsSectionsTest`, `DocsSiteExportTest` (com `KEYFLOW_REGERAR_DOC_SITE=1` uma vez) e `TranslationsTest` verdes; o "?" do cabeçalho da aba abre a seção; nenhuma frase da Docs promete botões na notificação, segurar, arrastar nem tile.
- **Tests**: unit + instrumentado leve. **Gate**: `quick`. **Commit**: `docs(notificação): seção da aba Notificação na Docs e no site`.

### NTF-T17 — Instrumentados ponta a ponta com motores falsos — **Status: pendente**
- **What**: `NotificacaoEndToEndTest` no serviço real: gravar e enviar pela notificação com gravador/STT falsos; os botões Pausar/Retomar, Descartar e Desfazer (e Descartar durante a transcrição); o 1 toque remapeado (ex.: Retrair/expandir no 1 e Gravar/enviar no 2) e a configuração sem nenhum toque de gravação, que não assume Gravar/enviar; "copiar" ligado seguindo a orbe principal ao vivo, inclusive o PTT sem inventar gravação; trocar a skin e o direito de skins re-posta; pacote só de notificação; tela bloqueada recusando; dispensar e voltar; `mostrar = false` com a orbe flutuante ligada; Visão encerrada pelo botão Encerrar e pelo toque em Gravar/enviar; tela bloqueada recusando também os botões.
- **Where**: `app/src/androidTest/.../notificacao/NotificacaoEndToEndTest.kt`.
- **Depends on**: T10, T11, T13. **Requirement**: NTF-09..12, NTF-16, NTF-18, NTF-21, NTF-24, NTF-48, NTF-49 (e confirma o conjunto).
- **Done when**: cenários verdes no emulador; nenhum `Thread.sleep` (espera por estado/`KeyFlowController`); janela de contagem e do cancelamento alongadas por gancho `…ForTest`; o serviço é parado no `@After` (regra do `ArchitectureRulesTest`).
- **Tests**: instrumentado. **Gate**: `pool instrumentar com.keyflow.notificacao.NotificacaoEndToEndTest`. **Commit**: `test(notificação): notificação de ponta a ponta com motores falsos`.

### NTF-T18 — Validação manual **[YAN]** — **Status: pendente**
- **What**: roteiro `manual-validation.md` nesta pasta, no **A32** (e no Vaio/Tab quando ele quiser): (1) a orbe inteira na notificação, recolhida e expandida, claro e escuro do sistema; (2) gravar e enviar por 1 toque e a sensação de latência da janela, e **Pausar, Retomar, Descartar e Desfazer pelos botões**; (3) 1, 2 e 3 toques com cada ação do catálogo (inclusive Gravar/enviar remapeado e a configuração sem nenhum toque de gravação); (4) **tela bloqueada** com o PIN dele: opção desligada recusa, ligada controla, "Ler a tela" recusa, a versão pública não mostra estado; (5) **dispensar** (Android 14+): serviço vivo, nada muda nas configurações, volta numa gravação e ao abrir a aba;
  (6) Aero, Glitch, Rose Gold, Ouvi e o pacote dele na notificação; (7) "copiar" ligado e desligado, inclusive o PTT sem inventar gravação e o aviso genérico ao substituir uma ação (D16, já decidida, sem reabrir); (8) `mostrar` desligado (só a mínima); (9) **Ler a tela / Trocar o teclado** sem e com a permissão de sobreposição e desbloqueado — o que a plataforma deixa fazer, para o **item em aberto D14**. Resultados e as dúvidas abertas (Q3, Q5, Q7, Q8, Q9, Q11, Q12) registrados.
- **Where**: `.specs/features/notification/manual-validation.md`, `.specs/project/licoes/`.
- **Depends on**: T14, T17. **Requirement**: Success Criteria da spec, NTF-43.
- **Done when**: o Yan assina o roteiro (ou lista os desvios); as dúvidas abertas respondidas ou transformadas em task; o item em aberto D14 revisado; a D16 permanece decisão registrada.
- **Tests**: manual. **Gate**: manual. **Commit**: `docs(notificação): roteiro de validação manual e resultados`.

### NTF-T19 — Fechamento — **Status: pendente**
- **What**: Status de todas as tasks neste arquivo, traceability do `spec.md` (Pending → Verified), lições em `.specs/project/licoes/` (altura nos aparelhos, o desenho da orbe fora do Compose, dispensar e `deleteIntent`, tela bloqueada e o `Toast`), `DEVELOPMENT-ORDER.md` (Bloco 15 → concluído) e as notas de supersessão:
  a traceability da `orb-gestures` (NOTIFCTL-01/02/03 substituídos por NTF-05..12/NTF-28/NTF-48: ● gravar, ■ parar e ⚙ saem; pausar/retomar/descartar/desfazer ficam como botões equivalentes aos arrastos) e os itens 13.1–13.5 do `uat-e2e-scenarios.md`.
- **Where**: `.specs/features/notification/*`, `.specs/DEVELOPMENT-ORDER.md`, `.specs/features/orb-gestures/spec.md`, `.specs/uat-e2e-scenarios.md`, `.specs/project/licoes/`.
- **Depends on**: T18. **Requirement**: NTF-47, regra de ouro 6.
- **Done when**: gate `full` verde (unit + instrumentados afetados + goldens 0 divergências além das rotas desta feature); `ArchitectureRulesTest`, `TranslationsTest` e `lintDebug` verdes; requisitos → Verified (ou Parcial com o motivo).
- **Tests**: gate completo. **Gate**: `full`. **Commit**: `docs(notificação): fecha a frente da Notificação`.

### NTF-T20 — "Rodar fluxo" na notificação (depende de Fluxos) — **Status: pendente**
- **What**: o lado da notificação do gancho NTF-39: `ResolvedorDeInteracao` recebe a `ResolucaoDeFluxo` do `RoteadorGatilhos.resolverToques(NOTIFICACAO, …)` de Fluxos (com "copiar" ligado, consulta `Orbe(FLUTUANTE, n)`, FLX-60); `Comando.RodarFluxo`/`FluxoIncompleto` executados pelo host (dispara pelo `FluxoHost`, aviso "Fluxo incompleto: abra a aba Fluxos");
  o catálogo do painel (T12) ganha "Rodar fluxo …" (lista dos fluxos válidos; fluxo apagado deixa a interação sem efeito, com aviso) e, quando o gatilho foi ligado pelo modal de Fluxos, o seletor de 1/2/3 toques mostra "Fluxo: <nome>" somente leitura (o gatilho sombreia a ação; tocar leva à aba Fluxos); a orbe da notificação mostra `Transcrevendo` enquanto o fluxo roda e **qualquer toque na orbe** o cancela (FLX-29), sem consultar a ação do toque. O lado de Fluxos (grupo "Orbe da notificação" na modal, roteamento) é a **FLX-T28**.
- **Where**: `notificacao/ResolvedorDeInteracao.kt`, `notificacao/app/NotificacaoHost.kt`, `ui/config/NotificacaoInteracoes.kt`; testes `ResolvedorDeInteracaoTest` (estendido), `OverlayServiceNotificacaoTest` (estendido).
- **Depends on**: FLX-T28 (que depende de FLX-T5, FLX-T14, FLX-T15, FLX-T20 e das NTF-T10/T13; a FLX-T28 entrega o lado do modal e do roteador, esta o lado do serviço e do painel, e o teste ponta a ponta de rodar o fluxo pelo toque mora **aqui**), T10, T12. **Requirement**: NTF-39 (efeito), NTF-11 (catálogo ganha o item).
- **Done when**: unit — 1, 2 ou 3 toques ligado a fluxo válido → `RodarFluxo` (o 1 toque também, porque é remapeável); inválido → `FluxoIncompleto` (a ação por baixo não roda); sem gatilho → a ação; "copiar" ligado usa os gatilhos da orbe flutuante; instrumentado — o fluxo roda pelo 2 toques da notificação (portas falsas), o toque durante o fluxo o cancela, tela bloqueada recusa antes de rodar; goldens existentes bit-idênticos exceto a prévia do seletor.
- **Tests**: unit + instrumentado. **Gate**: `quick` + `pool instrumentar com.keyflow.overlay.OverlayServiceNotificacaoTest com.keyflow.ui.config.NotificacaoPanelTest`. **Commit**: `feat(notificação): rodar fluxo pelos toques da notificação`.
