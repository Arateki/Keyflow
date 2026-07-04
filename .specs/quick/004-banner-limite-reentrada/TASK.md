# Quick Task 004: Banner de limite de gravação — resume reentra em vez de burlar o tempo

**Date:** 2026-07-03
**Status:** Done (2 rodadas); validação final [YAN] pendente

## Description

Yan reportou, testando o APK do Q1/Q2/Q3 no device físico: (1) segurar a gravação e arrastar pro "play"
consegue burlar o limite máximo configurado (`Settings.maxRecordingSec`), e (2) depois disso não dá pra
descartar o banner "Enviar/Descartar" que fica na frente — só sumindo ao cancelar por gestos.

Causa raiz (mesma para os dois): o comentário em `OverlayService.showLimitBanner()` já dizia "RESUME
esconde via coordinator", mas `OverlayCoordinator.onIntent(GestureIntent.RESUME)` nunca chamava
`hideLimitBanner()`. Resultado: ao retomar depois do limite, a gravação voltava a rodar sem teto (o Q1
decidiu deliberadamente não rearmar o timer nesse caso) E o banner ficava órfão na tela — `onLimitDiscard()`
virava no-op porque exige `machine.state == PAUSED`, e o estado já tinha voltado a `RECORDING`.

Também implementado nesta mesma tarefa (pedido do Yan, mesma área de código): o banner "Enviar/Descartar"
ganhou timer de 3s + barrinha de progresso (mesmo padrão do banner "cancelado · desfazer"). Ao expirar,
só a view some — a decisão continua pendente. Se o usuário tentar retomar a gravação enquanto a decisão
está pendente, o banner reacende do zero (novos 3s) em vez de deixar a gravação continuar.

## Decisões (assumidas — sem resposta do Yan à pergunta feita; usei as opções recomendadas)

- Resume com decisão pendente → **bloqueia o resume e reacende o banner** (não deixa gravar sem teto).
  Reverte a decisão original do Q1 ("sem re-arme"), que o próprio teste automatizado travava
  (`resuming after the limit was reached does not trigger it again` — renomeado/reescrito).
- Timeout do banner (3s sem toque) → **só some da tela; nada é decidido automaticamente** (nem envia, nem
  descarta). O áudio continua retido/pausado até o usuário agir ou tentar retomar (o que reacende o banner).

**[YAN]: confirmar se essas duas escolhas são o comportamento desejado** — dá pra ajustar (ex.: enviar ou
descartar automaticamente ao expirar) se preferir outra coisa.

## Files Changed

- `app/src/main/java/com/keyflow/overlay/OverlayCoordinator.kt` — novo `limitBannerDismissMs` (ctor,
  default 3000ms), `bannerDismissJob`, `scheduleBannerDismiss()`, `retriggerLimitBanner()`;
  `showLimitBanner()`/`hideLimitBanner()` agora gerenciam o job do timer; `GestureIntent.RESUME` verifica
  `limitBannerVisible` antes de retomar.
- `app/src/main/java/com/keyflow/overlay/OverlayService.kt` — `limitBannerDismissMs` (const, passado ao
  coordinator); `limitBannerView()` ganhou parâmetro `durationMs` + a barrinha (extraída de `bannerView()`
  para o novo helper `dismissBar()`, reusado nos dois banners).
- Testes: `OverlayCoordinatorTest.kt` — reescrito o teste de "resume não rearma" pro novo comportamento
  (reaciona o banner, `recorder.resumed == 0`), + 2 novos (`auto-dismiss sem decisão`, `retrigger reseta o
  timer`); `FakeRecorder` ganhou contador `resumed`.

## Verification (1ª rodada)

- [x] `testDebugUnitTest` verde (suíte completa, incl. os 3 testes novos/reescritos).
- [x] Gate `full` (instrumentado) — rodado na 2ª rodada (ver abaixo), 14/14 no pacote `overlay`.
- [ ] **[YAN]**: validar no Galaxy A32 — segurar+arrastar pro play com o banner de limite na tela deve
      reacender o banner (não voltar a gravar); banner deve sumir sozinho em ~3s se ninguém tocar;
      "Descartar"/"Enviar" devem continuar funcionando mesmo depois do banner ter sumido sozinho.
      **Yan confirmou** (2026-07-03): reacende corretamente durante o PAUSE.

## 2ª rodada — unificar "Descartar" com o cancelar comum + corrigir bypass via desfazer (2026-07-03)

Yan testou a 1ª rodada e reportou um bypass ainda pior: segurar → **descartar** o áudio do banner de
limite → **desfazer o descarte** pelo gesto de cancelar da orbe (modo Toggle; reproduzido também no PTT)
→ gravar de novo. Isso voltava a gravar **sem teto nenhum**, igual ao bug original da 1ª rodada.

**Causa raiz:** `onLimitDiscard()` chamava `machine.onDiscard()` — um descarte instantâneo e à parte,
sem passar pela janela de desfazer (`CANCELLING`). Só que o gesto de cancelar da orbe (`onCancel()`)
também podia ser acionado nesse ponto (PAUSED), e ele **limpava incondicionalmente** a flag de "decisão
de limite pendente" (`hideLimitBanner()`) para tirar o banner de limite da tela. Se o usuário desfizesse
esse cancelamento (`onUndoCancel()` → volta a `PAUSED`), a flag nunca era restaurada — então o próximo
`RESUME` não tinha mais nenhum sinal de que o limite já tinha sido atingido, e retomava a gravação livre
(sem reagendar o timer, já que o tempo acumulado já passava do máximo).

**Pedido do Yan (correção + redesenho), atendido nesta rodada:**
1. "Descartar" no banner de limite deve acionar **o mesmo fluxo/banner** do descarte via gesto de
   cancelar da orbe — um único padrão de descarte na UI, não dois.
2. Esse banner de descarte (antes "toque em qualquer lugar para desfazer") ganha **dois botões**:
   **Desfazer** (à esquerda) e **Concluir** (à direita, confirma o descarte na hora, sem esperar os 3s).
3. **Segurança contra clique duplo acidental**: "Desfazer" fica na MESMA posição (esquerda) onde
   "Descartar" estava no banner de limite — um duplo-toque apressado no mesmo ponto da tela cai no lado
   seguro (desfazer), não no de confirmar o descarte. Para excluir rápido a partir do PAUSED é preciso
   1 toque + mover o dedo + 2º toque — fricção deliberada, válida pros dois modos (Toggle/PTT), já que a
   lógica é do `OverlayCoordinator` e não depende do modo de gravação.

**Implementação:**
- `OverlayCoordinator.kt`: `limitBannerVisible` virou `limitDecisionPending` — persiste mesmo depois que a
  VIEW some (sozinha ou por entrar em `CANCELLING`); só é limpa quando o áudio é de fato resolvido
  (`onLimitSend`/`stopAndTranscribe`/`finalizeCancel`, nunca em `onCancel()`, que só esconde a VIEW).
  `onLimitDiscard()` agora delega para `onCancel()` (reusa a janela de desfazer comum) em vez de chamar um
  descarte instantâneo à parte.
- `ButtonStateMachine.kt`: `onDiscard()` removido (ficou órfão — nada mais chama descarte instantâneo sem
  janela de desfazer).
- `OverlayService.kt`: `limitBannerView()` generalizado para `twoActionBannerView(text, leftLabel,
  leftAction, rightLabel, rightAction, durationMs)`, reusado tanto pelo banner de limite
  (Descartar/Enviar) quanto pelo novo banner de cancelar (Desfazer/Concluir — antes um banner de clique
  único). Rótulo "Concluir" escolhido por já ser o termo interno usado pro `onCommitCancel()`
  ("conclui o cancelamento na hora"); ajustável se o Yan preferir outro.
- Testes: `ButtonStateMachineTest.kt` (-3, órfãos do `onDiscard()` removido); `OverlayCoordinatorTest.kt`
  reescreve o teste de "descartar do banner de limite" (agora abre `CANCELLING` em vez de descartar na
  hora) e adiciona a **reprodução exata do bug** (`undoing a discard started from the limit banner still
  enforces the limit on resume` — falha sem o fix, passa com ele);
  `OverlayServiceCancelTest.kt` (instrumentado) ajustado: a busca por texto `"desfazer"` (minúsculo, banner
  antigo) virou `"Desfazer"` (rótulo do botão novo).

**Gate:** `testDebugUnitTest` → **233 testes, 0 falhas** (fresco, após o fix de um teste que dependia da
ordem de `onHideLimitReached` — `onLimitSend()` não chama mais `hideLimitBannerView()` direto, porque
`handleState(machine.onStop())` já cai em `stopAndTranscribe()`, que faz isso; chamar dos dois lugares
duplicava a contagem). `connectedDebugAndroidTest` escopado ao pacote `com.keyflow.overlay` no emulador →
**14/14 verde**, incluindo `OverlayServiceCancelTest` com a nova asserção de texto.

Build (`assembleDebug`) gerado; **não instalado ainda** — o Galaxy A32 não estava conectado nesta sessão
(só o emulador). Falta `installDebug` no device físico do Yan.

**Pendente [YAN]**:
- Instalar/testar no Galaxy A32: (a) segurar → descartar (banner de limite) → desfazer (banner novo,
  botão à esquerda) → gravar de novo deve **reacender o banner de limite**, não gravar sem teto; (b) o
  banner de descarte novo (Desfazer/Concluir) deve aparecer tanto vindo do limite quanto do cancelar comum
  da orbe (Toggle e PTT); (c) confirmar se o rótulo "Concluir" está bom ou se prefere outro termo.

## Commit

_(pendente — aguardando instalação/validação do Yan antes de comitar)_
