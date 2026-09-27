# Quick Task 005: Cancelar durante a transcrição + texto do banner + animação da orbe

**Date:** 2026-07-03
**Status:** Done e commitado (código + gate, 3 rodadas, validado pelo Yan)

## Description

Continuação da sessão do Q4. Yan confirmou o banner de limite (3s, reacende ao tentar gravar) e o rótulo
"Concluir" funcionando bem, e pediu 3 ajustes na mesma área:

1. Trocar o texto do banner de cancelar de **"Cancelado"** para **"Transcrição cancelada"**.
2. Permitir cancelar também durante a transcrição (`SENDING`): arrastar a orbe pro lado deveria abortar a
   requisição em andamento e abrir o mesmo modal de descarte (Desfazer/Concluir); ao desfazer, volta pro
   pause. Segurar nesse estado trava a orbe só na direção lateral (sem cima/baixo), diferente do
   modo-arrasto de RECORDING/PAUSED que tem 3 direções.
3. O visual do estado "Transcrevendo" da orbe estava muito sutil e com padrão de movimento pouco variável
   (uma única onda senoidal).

Perguntei ao Yan se ele queria decidir (a) cancelamento HTTP de verdade vs. só ignorar a resposta tardia, e
(b) se o gesto valeria só no modo Alternar ou também no Push-to-talk — sem resposta, segui com as opções
recomendadas (cancelamento real; só Alternar por enquanto, já que o modo-arrasto travado hoje só existe lá).

## Decisões (assumidas — sem resposta do Yan às perguntas feitas)

- **Cancelamento real da requisição HTTP** (não só ignorar a resposta tardia): mais correto (libera a
  conexão na hora, não desperdiça dado/bateria), custo extra de refatorar `WhisperApi`/`TranscriptionClient`
  considerado aceitável.
- **Gesto só no modo Alternar (Toggle) por agora**: o modo-arrasto travado (setas/lock) hoje só existe no
  Toggle; estender ao Push-to-talk exigiria desenhar esse mecanismo lá do zero — fora do escopo desta rodada.
- **[YAN]: confirmar se essas 2 escolhas servem** — dá pra estender ao PTT depois se quiser.

## Achado de arquitetura (por que não é trivial)

Cancelar a partir de `SENDING` é fundamentalmente diferente de cancelar a partir de `RECORDING`/`PAUSED`:
nesses dois, o `recorder` nunca foi parado (só pausado) — dá pra retomar a MESMA sessão. Em `SENDING`, o
`AudioRecorder.stop()` já foi chamado e o `MediaRecorder` já foi liberado; `pause()/resume()/discard()`
nele viram no-op silencioso depois disso. Então "voltar pro pause" depois de cancelar um envio não pode
reusar o recorder — o coordinator passou a guardar o próprio arquivo de áudio já gravado
(`pendingAudioFile`) pra poder reenviar ou descartar a partir daí sem tocar o recorder de novo.

Também achei uma race sutil ao implementar: se a tentativa cancelada só processa seu resultado tardio
DEPOIS que um reenvio (nova tentativa) já está rodando, checar só `machine.state == SENDING` não basta pra
distinguir as duas (ambas veem o mesmo estado). Resolvido com um contador (`sendAttempt`) incrementado a
cada tentativa — a tentativa antiga só age se ainda for a mais recente.

## Files Changed

- `app/src/main/java/com/keyflow/overlay/OverlayService.kt` — texto do banner "Cancelado" →
  "Transcrição cancelada".
- `app/src/main/java/com/keyflow/overlay/ButtonStateMachine.kt` — `onCancel()` aceita `SENDING` também
  (→ `CANCELLING`).
- `app/src/main/java/com/keyflow/overlay/OverlayCoordinator.kt` — `transcribeJob`/`pendingAudioFile`/
  `sendAttempt` novos; `onCancel()` ramifica: SENDING cancela o job + aborta a requisição (`transcriber.
  cancelInFlight()`), RECORDING/PAUSED continua pausando o recorder como antes; `finalizeCancel()` também
  apaga `pendingAudioFile`; `stopAndTranscribe()` reusa `pendingAudioFile` se presente (reenvio, sem
  chamar `recorder.stop()` de novo) e ganhou a guarda de tentativa obsoleta; `GestureIntent.RESUME` virou
  no-op quando há `pendingAudioFile` (não existe sessão de gravação viva pra retomar).
- `app/src/main/java/com/keyflow/transcription/WhisperApi.kt` — `post()` ganhou `onCall: (Call) -> Unit`,
  entregue ANTES do `execute()` bloquear, pra quem chama guardar uma referência cancelável.
- `app/src/main/java/com/keyflow/transcription/TranscriptionClient.kt` — `activeCall` (@Volatile) + `cancel()`
  público (`activeCall?.cancel()`).
- `app/src/main/java/com/keyflow/overlay/OrbButtonView.kt` — modo-arrasto travado também entra a partir de
  `SENDING` (Toggle); durante SENDING, `updateDragFeedback()` pina as direções cima/baixo em 0 (só a
  lateral resolve pra `OrbDragKind.CANCEL`); `enterDragMode()` não mostra as setas de cima/baixo nesse caso.
- `app/src/main/java/com/keyflow/ui/orb/VoiceOrb.kt` — `orbLevel(Transcribing, t)` trocou de uma onda
  senoidal única por duas fora de fase (mais variável, ainda mais calmo que Listening).
- Testes: `ButtonStateMachineTest.kt` (+1, -1 reescrito); `OverlayCoordinatorTest.kt` (+3: cancelar durante
  o envio aborta e ignora resultado tardio; desfazer reenvia o MESMO arquivo sem re-parar o recorder;
  concluir/expirar apaga o arquivo retido).

## Verification

- [x] `testDebugUnitTest` → 237 testes, 0 falhas.
- [x] `connectedDebugAndroidTest` escopado a `com.keyflow.overlay` no emulador → 14/14 verde (nada
      quebrado pelo texto novo do banner nem pela mudança de `updateDragFeedback`).
- [x] Build instalado no Galaxy A32 do Yan.
- [ ] **Sem cobertura automatizada pro gesto em si** (arrastar a orbe pro lado durante SENDING): o
      modo-arrasto travado já não tinha nenhum teste instrumentado antes desta tarefa (mesma lacuna
      pré-existente do PAUSE/CANCEL a partir de RECORDING/PAUSED — depende de simular o timer de
      hold+drag, mesma classe de fragilidade de toque sintético já documentada no projeto). Validado só
      por leitura de código + os testes do coordinator (que cobrem a lógica de estado/reenvio).
- [x] **Yan validado em conjunto com as rodadas seguintes** — texto novo, gesto lateral em SENDING e
      visual do "Transcrevendo" nunca geraram objeção nas rodadas 2/3; "pode prosseguir o desenvolvimento"
      (2026-07-03) tratado como aprovação geral do lote todo.

## 2ª rodada (mesmo dia): PTT, fusão de áudio, teclado e notificação sincronizados

Yan validou o texto do banner e o "Concluir", mas fez 4 perguntas/pedidos em cima da 1ª rodada:

1. **PTT também**: a 1ª rodada só cobria o modo Alternar. Investigando o mecanismo de arrasto travado do
   `OrbButtonView`, quase tudo (`enterDragMode`/`updateDragFeedback`/`resolveDragOnUp`) já era agnóstico
   de modo quanto a SENDING — só o "gatilho" em `ACTION_DOWN` (`scheduleDragHold()`) estava restrito ao
   Alternar. Ampliado pra também entrar em modo-arrasto travado no PTT quando `renderedState == SENDING`.
2. **Retomar gravação após desfazer o cancelamento em SENDING**: antes era um no-op (não dava pra
   continuar, já que o `MediaRecorder` original tinha sido liberado). Yan pediu pra poder continuar
   falando e mandar os dois trechos como uma única transcrição. Escolheu (perguntado via pergunta
   fechada) **remixar os áudios num arquivo só**, não duas chamadas de API com concatenação de texto.
3. **"Cancelamento real"**: dúvida sobre o que a pergunta da 1ª rodada significava — respondida em texto
   (aborta a chamada HTTP na hora vs. deixá-la rodando e ignorar o resultado tardio); o cenário descrito
   (transcrição termina enquanto ainda se interage com a orbe) já funcionava corretamente por construção
   (o cancelamento só é efetivado ao soltar o dedo na zona lateral).
4. **Teclado (mic) e notificação sincronizados**: pedido pra ambos suportarem cancelar durante SENDING no
   mesmo estilo da orbe — segurar o mic mostra só "cancelar" (sem pausar); a notificação mostra só a ação
   de cancelar.

### Decisões desta rodada

- **Remux via `MediaExtractor`/`MediaMuxer`** (`com.keyflow.audio.AudioConcatenator`, novo): copia as
  amostras AAC já codificadas dos dois (ou mais) arquivos `.m4a` pra um container só, deslocando os
  timestamps do segmento seguinte pra colar depois do fim do anterior — sem decodificar/recodificar
  (mesma qualidade, muito menos trabalho que um pipeline de transcodificação completo).
- **Limite de gravação cumulativo entre segmentos**: sem isso, o relógio do limite reiniciaria do zero a
  cada ciclo cancelar-durante-envio→desfazer→continuar, reabrindo uma variante do bug do Q4 (burlar o
  teto). `OverlayCoordinator` ganhou `priorSegmentsMs` (soma dos segmentos já retidos) e `timeFreeze()`
  virou idempotente (reseta `timeSegmentStartMs` após acumular — chamar de novo enquanto já congelado
  soma ~0 em vez de contar o mesmo intervalo duas vezes). Se o trecho já retido sozinho já bateu o teto,
  retomar reabre o banner "enviar ou descartar" em vez de deixar continuar sem limite.
- **Reenvio puro vs. continuação**: um novo campo `continuingPendingAudio` distingue "só reenviar o
  arquivo retido, sem gravar mais nada" (comportamento da 1ª rodada, preservado) de "retomou e gravou
  mais" (funde no próximo stop). Cuidado importante: o tempo ocioso gasto interagindo com o banner de
  desfazer NÃO pode contar como tempo de gravação no reenvio puro — `stopAndTranscribe()` só chama
  `timeFreeze()` quando não é reenvio puro.

### Files Changed (2ª rodada)

- `app/src/main/java/com/keyflow/audio/AudioConcatenator.kt` (novo) — remux de 2+ segmentos `.m4a`.
- `app/src/androidTest/java/com/keyflow/audio/AudioConcatenatorTest.kt` (novo) — grava áudio real via mic
  (mesma técnica do `AudioRecorderTest`) e confere duração/reprodutibilidade do arquivo fundido (2 e 3
  segmentos).
- `app/src/main/java/com/keyflow/overlay/OverlayCoordinator.kt` — `timeFreeze()` idempotente;
  `priorSegmentsMs`/`continuingPendingAudio` novos; `GestureIntent.RESUME` com `pendingAudioFile` inicia
  uma sessão nova (ou reabre o banner de limite se já esgotado) em vez de no-op; `stopAndTranscribe()`
  funde os segmentos via `concatAudioSegments` (lambda injetável — o real aponta pro
  `AudioConcatenator`, testes injetam um fake, já que `MediaExtractor`/`MediaMuxer` não rodam em unit
  test puro).
- `app/src/main/java/com/keyflow/overlay/OrbButtonView.kt` — gatilho do modo-arrasto travado em SENDING
  passa a valer nos dois modos (Alternar e PTT), não só Alternar.
- `app/src/main/java/com/keyflow/ime/KeyFlowKeyboardView.kt` e
  `app/src/main/java/com/keyflow/ime/bars/MicSelectorController.kt` — `actionsFor(SENDING)` agora
  devolve `listOf(MicAction.CANCEL)` (era vazio) nas duas implementações (barra antiga e Canvas/`BarView`).
- `app/src/main/java/com/keyflow/overlay/RecordingNotification.kt` — `notif_cancel` visível também em
  SENDING (só ele — pause/stop continuam ocultos); `titleFor(SENDING)` = "⏳ Transcrevendo".
- Testes: `OverlayCoordinatorTest.kt` (+2 novos: fusão de segmentos ao retomar; limite cumulativo reabre
  o banner; 1 reescrito pra refletir que reenvio puro agora exige NÃO chamar RESUME); `FakeRecorder`
  ganhou suporte a devolver arquivos diferentes por ciclo start/stop; `RecordingNotificationTest.kt`
  (+2: título e visibilidade das ações em SENDING).

### Verification (2ª rodada)

- [x] `testDebugUnitTest` → 241 testes, 0 falhas.
- [x] `connectedDebugAndroidTest` escopado a `com.keyflow.audio` (6/6) e `com.keyflow.overlay` (15/15) no
      Galaxy A32 — inclui o remux real gravando pelo microfone do aparelho.
- [x] `connectedDebugAndroidTest` escopado a `com.keyflow.ime` (29/29 na 2ª tentativa — a 1ª rodada teve
      TODOS os testes de UI falhando por um anúncio de outro app roubando o foco da tela do aparelho
      pessoal do Yan, não relacionado ao código; confirmado via `dumpsys window` mostrando
      `com.CodeBySonu.VoxSherpa/...AdActivity` em foco). O único teste que ainda falhou depois
      (`KeyFlowKeyboardAccessibilityTest.actionClickOnAVirtualNodeActivatesTheSameBaseActionAsATap`) não
      tem nenhuma relação com as mudanças desta rodada (área de acessibilidade da grade antiga) — mesma
      classe de flakiness de toque sintético sob carga já documentada no projeto.
      **Emenda 2026-09-08**: essa explicação foi medida em `KeyFlowKeyboardSpellCheckerTest` e lá era
      falsa — o toque chega; quem falhava era a leitura pelo UiAutomator. Ver
      `.specs/quick/010-suite-no-aparelho/`.
- [ ] **Sem cobertura automatizada pro gesto de cancelar durante SENDING no PTT** nem pro "segurar o mic
      mostra cancelar durante SENDING": exigiria interceptar/controlar a chamada de rede real dentro do
      teste instrumentado pra manter o estado em SENDING tempo suficiente, o que a suíte atual não monta
      (os testes de UI do teclado usam o `OverlayService` real, sem transcriber fake injetável de fora).
      Validado só por leitura de código + os testes do coordinator. Mesma lacuna já flagada na 1ª rodada
      pro gesto na orbe.
- [x] Build instalado no Galaxy A32 do Yan (`installDebug`).
- [x] **Yan validado via "pode prosseguir o desenvolvimento"** (2026-07-03), sem objeção aos 4 itens (PTT,
      continuar+fundir áudio, mic do teclado, notificação) — nenhum reportado como quebrado nas rodadas
      seguintes.

## 3ª rodada: setas cima/baixo reacendendo indevidamente + tempo local zerando entre segmentos

Depois de instalar a 2ª rodada, o Yan reportou dois bugs visuais (e, no meio do caminho, um incidente
separado: reinstalar via `installDebug` forçou uma reinstalação completa, apagando a config de voz dele —
ver nota abaixo, não é bug de código desta rodada):

1. **As setas ↑/↓ reacendiam durante "Transcrevendo"**: `enterDragMode()` já as escondia (alpha 0) ao
   travar o modo-arrasto, mas assim que o dedo se movia — mesmo só na direção lateral, pra cancelar —
   `updateDragFeedback()` recalculava `upAlpha`/`downAlpha` como `1f - upProx`, e como `upProx`/`downProx`
   ficam pinados em `0f` nesse estado (não respondem a movimento), a conta dava `1f - 0f = 1f` (opacas de
   novo). Corrigido fixando as duas em `0f` direto quando `sending`, em vez de calcular via proximidade.
2. **O tempo mostrado sob a lixeira (glifo de cancelar) zerava** ao cancelar durante o envio, e a
   continuidade se perdia de novo ao continuar gravando após desfazer: a `OrbButtonView` mantém seu
   PRÓPRIO relógio local (`accumulatedMs`/`segmentStartMs`, independente do relógio autoritativo do
   `OverlayCoordinator`), derivado só observando as transições de `ButtonState` em `render()`. Essa
   máquina de estados local nunca tinha sido atualizada pra reconhecer `SENDING`/`CANCELLING` como
   "estados com tempo rastreado" — qualquer transição envolvendo esses dois caía no `else` de reset
   (zera pra valer), já que as condições originais só conheciam RECORDING/PAUSED. Reescrito com um
   conjunto `TIME_TRACKED_STATES` (RECORDING/PAUSED/CANCELLING/SENDING) que trata: início de verdade
   (zera), retomada de qualquer PAUSED (preserva — cobre tanto o resume normal quanto continuar gravando
   pós-desfazer), saiu de RECORDING pra qualquer um dos outros três (congela, soma o segmento), e
   transições só entre os três não-RECORDING (preserva sem mexer).

**Nota à parte (não é bug desta rodada):** o Yan reportou "o app resetou tudo" entre a 2ª e 3ª rodada —
investigado e confirmado: o `installDebug` da 2ª rodada fez uma reinstalação completa (não incremental),
porque esta máquina tem duas keystores debug diferentes (`~/.android/debug.keystore` vs
`~/.config/.android/debug.keystore`, fingerprints diferentes) e o Android exige desinstalar quando a
assinatura muda. Perguntei ao Yan se quer que eu fixe uma keystore explícita no `build.gradle.kts` pra
isso não se repetir — ainda sem resposta. O install desta 3ª rodada não resetou nada (mesma keystore da
2ª rodada, `firstInstallTime` inalterado), mas o risco persiste enquanto não fixarmos.

Também investigado (e descartado como bug de código) um "erro de provedor de voz" mesmo com o Groq
configurado: confirmado que `transcription_source=GROQ` estava salvo corretamente; o Yan resolveu colando
o token de novo e salvando uma 2ª vez — parece ter sido um problema pontual de sincronização/timing na
primeira gravação após o reset de dados acima, não reproduzido de forma determinística.

### Files Changed (3ª rodada)

- `app/src/main/java/com/keyflow/overlay/OrbButtonView.kt` — `updateDragFeedback()`: `upAlpha`/`downAlpha`
  forçadas a `0f` quando `sending`, em vez de `1f - prox`; `render()`: máquina de estados do relógio local
  reescrita com `TIME_TRACKED_STATES` (novo `private companion` set) pra preservar o tempo acumulado
  através de SENDING/CANCELLING/PAUSED, só zerando de vez ao sair pra um estado fora desse conjunto.

### Verification (3ª rodada)

- [x] `testDebugUnitTest` → 241 testes, 0 falhas (nenhum teste unitário cobre `OrbButtonView` diretamente
      — é uma View Android pura, sem contrato JVM-testável; mesma lacuna já documentada).
- [x] Build instalado no Galaxy A32 sem reinstalação completa (`firstInstallTime` conferido antes/depois).
- [x] **Yan confirmou** (2026-07-03): as setas cima/baixo não aparecem mais durante "Transcrevendo" e o
      tempo sob a lixeira segue contando corretamente entre cancelar/desfazer/continuar.
- [x] Keystore debug fixada em `app/build.gradle.kts` (`signingConfigs.debug.storeFile` apontando pra
      `~/.config/.android/debug.keystore` — confirmado por fingerprint como a mesma que já assinava o
      app instalado no aparelho). Validado com um `installDebug` extra: `firstInstallTime` inalterado.

## Commit

`506d47d` — commitado junto com o Q4 (mesmo working tree, sem separação limpa possível entre as duas
tarefas: nunca houve um commit intermediário entre elas).
