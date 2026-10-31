# 2026-10-31 — Notificação, Fases B e C: aparência, pacote, a notificação-orbe e os toques (equipe/notificacao-b)

NTF-T6..T10 da feature `notification`, mais os 2 P3 da revisão da etapa A e a robustez da prova de altura.

## Correções da revisão da etapa A

- `desligarCopia` forçava `copiar = true` antes do retrato: uma segunda chamada, com a cópia já desligada, regravava as ações com as da orbe flutuante. Agora é
  **idempotente** (com a cópia já desligada devolve a config intacta) e tem teste (`ModeloTest`).
- `design.md` §5: o gancho de Fluxos do `resolver` é `(toques) -> ResolucaoDeFluxo?`, como no código.
- `NotificacaoAlturaSpikeTest` (T1) não quebra mais o CI com outras notificações por cima: limpa ("Clear all") e rola antes de procurar a orbe e, se ainda assim ela
  não aparece, o teste é **ignorado** (`Assume`), nunca reprovado. O `Assume` está no `achar(...)`.

## NTF-T6 — A orbe em bitmap

- `drawOrb`/`drawMascotOrb` já eram `internal`: o spike confirmou que desenham sobre `CanvasDrawScope` de um `ImageBitmap` ARGB transparente (inclusive o Glitch).
  `RenderizadorDeOrbe.renderizar(style, aparencia, ladoPx, glifo, pack)` usa **tempo fixo** (1 500 ms) e devolve o mesmo pixel nas duas chamadas.
- Os glifos saíram dos `@Composable` para funções de `DrawScope` (`drawPauseGlyph`, `drawErrorGlyph`, `drawCancelGlyph`, e o olho novo `drawEyeGlyph`); a orbe
  flutuante chama as mesmas funções com as mesmas operações (nenhuma mudança de pixel esperada). `centerInkOf(style, packInk)` substitui o `when` da tinta.
- **Achado**: no Ateliê o estado Pausada reusa o desenho de Gravando (só o glifo as distingue); o teste conferia o contrário e foi corrigido.
- **Mutação**: ignorar o `glifo` no renderizador derruba "os glifos mudam o bitmap".

## NTF-T7 — Provedor de aparência

- `OrcamentoDeBitmaps` (JVM): `ladoPx = min(192, round(40 × densidade))`, 2 bitmaps ≤ 1 MB (a 4× são 200 KB, dentro do alvo de 256 KB).
- `ProvedorDeAparencia`: skin efetiva pela mesma `orbStyleFor(id, skinsUnlocked)` da orbe flutuante (a paga sem direito vira o Ateliê **sem apagar a escolha**), skin
  **Sistema** (círculo na cor `system_accent1` no Android 12+), `LruCache` por bytes (2 MB), chave com skin efetiva, versão dos pacotes, aparência, glifo, lado e modo
  noturno; `observar(direito, pacotes)` solta o cache. Falha de render → Ateliê → `AparenciaIndisponivel` (o serviço usa a notificação mínima).
- Ateliê pinta fundo (`#2B2720` escuro, `#F1E7C9` claro); as pagas, o Sistema e o pacote sem `background` deixam o do sistema.
- **Mutação**: tirar o `min(192, …)` de `ladoPx` derruba `OrcamentoDeBitmapsTest`.

## NTF-T8 — A seção `notification` do pacote

- `PackFileKind.NotificationFace`, `NotificationManifest` (`ink`, `background` opaco), leitor (erro "falta notification_idle.svg", relatório "notificação: N estados", aviso
  da seção sem arquivos, erro "não tem orbe, teclado nem notificação"), `SkinPack.hasNotification/notificationFile`, store (instala, lista, abre, reimporta, remove) e
  `PackPreview.notification`. **`schemaVersion` continua 1**: a versão antiga ignora o campo e os arquivos com aviso (testado); só o pacote **só de notificação** é recusado nela.
- `FORMAT.md` (§1, §2, §3, §6, §7) editado junto, com o `SkinPackFormatDocTest` (tabelas e árvore) verde, a cópia embutida para o agente igual (é um asset copiado do mesmo
  arquivo) e o pacote `exemplo-neon` com as 4 peças (`notification_*.svg`, geradas pelo `gerar-exemplo-neon.py`), que importa **sem avisos**.
- O agente de skins (`SkinWorkshop`) ganhou `SkinFileKind.NOTIFICATION_FACE`, senão recusaria os nomes que o próprio FORMAT.md manda usar.
- `OrbeDePacote` (a `FonteDeOrbe` do pacote): com `notification` desenha o SVG do estado (ausente → `idle`) na `notification.ink` e no `notification.background`; um pacote
  **só de orbe** (pelo "copiar") desenha o **primeiro quadro** do estado na `orb.centerInk`, sem fundo (NTF-34).
- Textos novos/alterados em `i18n/{en,es}/Skins.kt` (o texto do erro "não tem orbe nem teclado" foi substituído, não duplicado).
- **Mutação**: tirar `"notification"` do `knownTop` do manifest faz o campo virar aviso de desconhecido e derruba os testes do pacote.

## NTF-T9 — A notificação é a orbe

- `notif_orb.xml` (design §6; um segundo `Chronometer` miúdo para o tempo congelado), `NotificacaoDaOrbe.construir` (sem parâmetro de texto), `construirMinima` (canal
  `keyflow_overlay_min`, `SECRET`, sem ação) e `galeria`. `RecordingNotification`, `notif_recording.xml`, `ic_notif_record/stop/config` e `RecordingNotificationTest` saíram; as
  constantes `ACTION_PAUSE_RESUME/STOP_PASTE/CANCEL` (do mic do teclado) foram para o `OverlayService`.
- **Um `PendingIntent` por botão** com o botão no extra (`EXTRA_BOTAO`) e `requestCode` próprio: Pausar e Retomar têm a mesma ação mas intents distintos, então um clique atrasado é
  reconhecido (T10). Botão ausente é `INVISIBLE` (a T1 provou que `GONE` desloca a orbe).
- A versão pública (tela bloqueada) é a orbe em repouso, sem cronômetro e **sem botões** com a opção desligada; com ela ligada, o estado real.
- O `OverlayService` monta a notificação por `notificacaoDoEstado` (aparência, orçamento, mínima como fallback). **Limite desta etapa**: o desenho ainda roda na thread de quem chama
  (um bitmap de ~105 px, do cache depois do primeiro); a coalescência e o desenho fora da principal são da T11.
- **Visual intencional**: a notificação mudou (era a barra de chips; agora é a orbe). As capturas da galeria `notification-*` mudam de propósito (T15 as reaprova).
- **Mutação**: botão ausente `GONE` em vez de `INVISIBLE` derruba "os botões de cada estado batem com a tabela".

## NTF-T10 — Os toques

- `NotificacaoHost` (JVM puro, em `notificacao/`, e não em `notificacao/app/`, para testar com relógio virtual): **consulta o `PortaoDeToque` antes de qualquer toque ou botão**; a orbe
  entra na `RajadaDeToques` e, ao assentar, `resolver` devolve o `Comando`, que o host executa (ações de Activity passam de novo pelo portão com `precisaDeActivity = true` e pela
  permissão de sobreposição, L10); o botão age na hora, não entra na rajada, e confere o estado de agora.
- `OverlayService`: `executarAcaoDeAtalho` extraído de `handleOrbShortcut` (a orbe flutuante usa a mesma função), `ACTION_NOTIF_ORB_TAP/PAUSE_RESUME/CANCEL/UNDO`, config ao vivo
  (`configNotificacaoFlow`), `KeyguardManager.isKeyguardLocked()` lido a cada toque, avisos por `Toast` + vibração.
- `ArrastosDaOrbeConformidadeTest` fecha o que a T4 deixou: arrasta a `OrbButtonView` real em cada estado e direção e compara com `ArrastosDaOrbe`.
- **Prova da tela bloqueada no emulador**: o AVD de teste vem sem bloqueio; o teste liga o de deslizar (`locksettings set-disabled false`, sem PIN), apaga a tela, confere
  `isKeyguardLocked` e devolve o valor anterior. Com a opção desligada, a orbe e os botões **não agem**; ligada, o botão age.
- **Mutação**: o botão sem passar pelo portão (`recusado` ignorado em `aoTocarNoBotao`) derruba "bloqueada com a opção desligada nenhum botão age".

## Fechamento (T6..T10)

- Gates: `testDebugUnitTest` (suíte inteira), `:app:lintDebug` e `assembleDebug` verdes; instrumentados verdes: `RenderizadorDeOrbeTest`, `ProvedorDeAparenciaTest`, `NotificacaoAlturaSpikeTest`,
  `SkinPackStoreTest`, `OrbeDePacoteTest`, `NotificacaoDaOrbeTest`, `OverlayServiceNotificacaoTest`, `ArrastosDaOrbeConformidadeTest`, `OrbButtonViewTest`, `OverlayServiceCancelTest`,
  `OverlayServiceStopTest`, `OverlayServiceVisionTest`.
- **Lição (teste de serviço)**: `KeyFlowController.buttonState` é global; o estado do teste anterior fica nele até o serviço novo subir, então `waitUntilState(RECORDING)` no `setUp` pode passar antes
  de o serviço existir e o primeiro clique cai no vazio (`coordinator` ainda não inicializado). Os botões são idempotentes: o teste repete até o estado chegar (`botaoAte`).
- **Lição (bloqueio no emulador)**: o teste do bloqueio deixava o aparelho travado e derrubou o `OverlayServiceVisionTest` ("IME não mudou o controller"). Agora cada teste começa com
  `KEYCODE_WAKEUP` + `wm dismiss-keyguard`, e o do bloqueio devolve `locksettings` ao que estava.
- **Mutações** (uma por task, todas derrubaram testes e foram revertidas): T6 ignorar o glifo; T7 sem o teto de 192 px; T8 `notification` fora do `knownTop`; T9 botão ausente `GONE`;
  T10 botão sem passar pelo portão.
- **Goldens**: só mudam de propósito os `notification-*` (a notificação deixou de ser a barra de chips). Orbe, Sobrepor, onboarding e tour devem ficar bit-idênticos (ver o relatório).
- **Pendente (fora desta etapa)**: coalescência, dispensar e re-posts (T11); desenho da aparência fora da thread principal (T11); gancho real de Fluxos (T20).
