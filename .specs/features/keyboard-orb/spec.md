# Orbe do teclado — Specification

> **A orbe dentro do teclado.** O IME desenhado em Canvas passa a mostrar a orbe no lugar do microfone da barra.
> Ociosa, ela cabe no encaixe. Quando precisa mostrar tempo ou estado, cresce **só para cima**, numa faixa de
> 36 dp que a janela do teclado já sabe reservar sem contar como altura do teclado para os outros apps e **sem
> cobrir tecla**. Os tipos de interação são os da orbe flutuante (1, 2 e 3 toques, segurar, arrastar). O que cada
> toque faz é configurável, com a opção **"copiar a configuração da orbe principal"**.
> **Status**: rascunho (2026-10-29), com as decisões do Yan de 29/10 (22:45 e 22:57). **Fora da v1** (só `main`).

A notificação e os Fluxos (FLX-57..60, FLX-T28 e FLX-T29) estão na `main`. Esta spec é o lugar `TECLADO`. A FLX-T29
deixou de estar bloqueada por falta de spec: o texto bloqueado foi **substituído** (não duplicado) em `flows/tasks.md`.
Ela continua dependendo da entrega de KBO-T3 e KBO-T5; nenhuma task desta frente depende dela. O código da notificação
pode ainda não estar implementado — esta frente não o importa.

## Problem Statement

Hoje o teclado grava por um **botão de microfone** na barra, não por uma orbe:

- O encaixe é `BarButton.Mic` (`ShortcutModels.kt:52-54`). A fábrica coloca um no início da barra retraída e da expandida
  (`ShortcutModels.kt:121-145`). O peso é 1,5 (`BarGeometry.kt:37-38`) para caber o cronômetro ao lado do ícone.
- `BarView` desenha o glifo e o `m:ss` (`BarView.kt:407-428`), conta o toque (`:657-662`) e, ao segurar, abre um seletor
  de chips (`:569`, `MicSelectorController`). Isso é o KBDCTL-01..04 de `orb-gestures`.
- O toque sobe o serviço e cai em `handleKeyboardMicTap` (`OverlayService.kt:537-543`): ocioso inicia, gravando ou pausado
  envia, cancelando conclui o descarte, e a Visão ativa termina antes. O comentário de `Settings.kt:105-107` crava o
  recorte: o teclado **não tem modo**; o modo (Alternar / segurar-para-falar) é só da orbe flutuante.
- A orbe flutuante é outra janela (`OrbButtonView`): rajada em `tapBurstWindowMs` (`Settings.kt:121`, padrão 250;
  `OrbButtonView.kt:594-621`), segurar trava o arrasto (`:450-456`, offset de 150 ms), setas numa janela à parte
  (DRAG-02/06). O desenho e o centro estão em `orbStateFor` / `orbCenterFor` (`OrbButtonView.kt:674-688`) e as skins em
  `orbStyleFor` (`VoiceOrb.kt:131-135`). Nada disso está no Canvas do teclado.

O Yan quer a **mesma orbe** no teclado: pequena no repouso, maior quando há o que mostrar, sem tapar tecla, com skins da
orbe e claro/escuro do teclado, e sem um botão de microfone ao lado.

## Goals

- [ ] Uma orbe no encaixe do microfone, ociosa dentro da barra e **sobressalente para cima** ao gravar, pausar, cancelar, transcrever ou errar, sem interseção com tecla.
- [ ] Os sete tipos de interação da orbe flutuante, no mesmo relógio (`tapBurstWindowMs`) e nos mesmos limiares de segurar e arrastar.
- [ ] Toque simples **remapeável**; gravar/enviar **pode** estar em 1, 2 ou 3 toques, e também pode não estar em nenhum (não é obrigatório). Sem seletor de chips e sem segundo microfone.
- [ ] **"Copiar a configuração da orbe principal"** (padrão ligado): skin, modo e ações dos toques, ao vivo; a config própria fica guardada.
- [ ] Skins da orbe (Ateliê, pagas, pacote), claro/escuro **do teclado**.
- [ ] Gatilho de Fluxos só em `Orbe(TECLADO, 2)` e `Orbe(TECLADO, 3)`, no contrato da FLX-59/60. Segurar e arrastar não viram gatilho.
- [ ] JSON próprio no DataStore, sem mudar `bars_config_json`. Texto novo em `tr("…")`. Fora da v1.

## Out of Scope

| Fora | Razão |
| ---- | ----- |
| Reescrever a orbe flutuante (`OrbButtonView`) | Ela continua dona do lugar `FLUTUANTE`. O teclado lê os `Settings` dela quando "copiar" está ligado |
| Segurar e arrastar **na notificação** | A notificação só tem toques (NTF-08, L1). O teclado, por ser View, tem os sete |
| 1 toque, segurar ou arrastar como **gatilho de fluxo** | FLX-57: na V1 de Fluxos o gatilho de orbe é 2 e 3 toques. Resposta à Q8 de Fluxos: o teclado suporta os sete, mas só 2 e 3 dispararam fluxo |
| Mover a orbe pela tela, como a flutuante se reposiciona | Dentro do teclado ela é fixa no encaixe. Arrastar é só o gesto direcional |
| Seletor de chips do mic (KBDCTL-01..03) | Sai. Pausar, cancelar e abrir o app passam a ser os arrastos da orbe |
| Editar o `FORMAT.md` ou criar seção de pacote só para esta orbe | O documento está amarrado ao parser (`SkinPackFormatDocTest`, `SkinFormatAssetTest`). A orbe do teclado reusa os quadros `orb_*` que já existem. Igual à decisão A10 da notificação |
| Tela bloqueada | O IME não é a superfície de bloqueio. Quem recusa fluxo na tela bloqueada é a notificação (FLX-58, NTF-16) |
| `connectedAndroidTest` no A32 (`RX8T10DERKW`) | Desinstala o app ao terminar. Instrumentado só com `pool instrumentar` no emulador |
| Altura da orbe flutuante (`orbSizePx`, 110..280, padrão 195) aplicada aqui | O tamanho da orbe do teclado é o do encaixe mais os 36 dp. Copiar não traz posição nem tamanho |

---

## Decisões do Yan (2026-10-29)

| # | Decisão | Onde vale |
| - | ------- | --------- |
| D1 | A orbe é a interface única nas três superfícies. O que cada interação faz é **por interação**. Não há botão de microfone separado. O toque simples é remapeável; gravar **pode** ficar em 1, 2 ou 3 toques (possibilidade, não exigência) | KBO-05, KBO-14, KBO-15 |
| D2 | No teclado a orbe é pequena; ao mudar de estado e precisar mostrar tempo ou estado, **sobressai um pouco acima do teclado**, maior, **sem cobrir nenhuma tecla** | KBO-07, KBO-08, KBO-09 |
| D3 | As skins da orbe valem aqui. Claro/escuro como o **teclado** | KBO-21, KBO-22 |
| D4 | Fluxos dispara com `Orbe(lugar=TECLADO, toques)`. A notificação usa o mesmo modelo de lugares | KBO-12, KBO-30, KBO-31 |
| D5 | A faixa sobressalente é **36 dp**, provisória até ver no aparelho (29/10, 22:45). Mais alto que a largura do encaixe cobriria o vizinho, a menos que a faixa também alargue o encaixe — isso não entra | KBO-08, KBO-09 |
| D6 | Mic extra já salvo fica no JSON, **não é desenhado** e **não é tocável** (29/10, 22:45). Confirma A6 | KBO-06 |
| D7 | Arrastar fica **fixo** (↑ pausa, lateral cancela, ↓ abre o app), fora do catálogo dos toques (29/10, 22:45). Confirma A4 | KBO-18 |
| D8 | Com "copiar" ligado, o modo da orbe flutuante vale no teclado, **inclusive segurar-para-falar** (29/10, 22:45). A alternativa de manter o toque-de-mic quando a flutuante está em segurar-para-falar foi recusada | KBO-19 |
| D9 | Gravar não é o foco do app: é só uma das ações. Não há exigência de que algum toque grave, nem fallback que recoloque gravar. Sempre que uma configuração **substituir** uma ação já atribuída (a um toque, gesto ou botão), a tela avisa o que foi substituído. Nada mais (29/10, 22:57). A resposta das 22:45 à antiga Q5 ("só aviso") fica coberta por esta | KBO-15, KBO-28 |
| D10 | A pele animada (Ouvi, Glitch) **corre no teclado real** e fica **parada só na prévia e nos goldens** (`motionTimeMs`) (29/10, 22:45) | KBO-23 |

---

## User Stories & Acceptance Criteria

### P1-A: A seção na aba Teclado (`TAB`)

**Como** usuário, **quero** configurar a orbe do teclado onde já configuro o teclado.

1. **KBO-01**: a aba **Teclado** SHALL ganhar a seção **Orbe**, abaixo das barras, **só na task que liga o painel** (KBO-T6). Antes disso a aba fica como está. Não nasce aba nova nem cartão "Em breve". A seção tem, nesta ordem: **Prévia**, **Copiar a configuração da orbe principal**, e — só com copiar desligado — **Aparência** e **Toques**. Tema da aba, `verticalScrollbar`, sem rolagem horizontal em 360 dp.
2. **KBO-02**: a **prévia** SHALL ser a orbe de verdade (o mesmo desenho do IME) nos estados Ociosa, Gravando, Pausada, Cancelando, Transcrevendo e Erro, com o tempo **congelado**. Claro e escuro são os do teclado (KBO-21).
3. **KBO-03**: o interruptor **"Copiar a configuração da orbe principal"** SHALL existir e vir **ligado** por padrão.
4. **KBO-04**: com copiar **ligado**, skin, modo e ações aparecem **somente leitura**, com o texto "Copiado da orbe principal". Desligar SHALL tornar editável o que estava na tela, como ponto de partida, **sem apagar** o que já estava guardado: se a pessoa tinha uma config própria, desligar **restaura essa**, e não a cópia. Ligar de novo volta a copiar. A tela não oferece segurar nem arrastar como linhas editáveis (KBO-18): um texto fixo diz o que eles fazem.

**Independent Test**: abrir Teclado → copiar ligado e a skin da Sobrepor refletida; desligar → edita; religar → somente leitura de novo e a edição anterior continua no JSON.

---

### P1-B: Onde a orbe senta e como sobressai (`GEO`) ⭐ MVP

**Como** usuário, **quero** ver a orbe no teclado, maior enquanto gravo, sem ela tapar as teclas nem empurrar o app de trás.

1. **KBO-05**: o teclado SHALL ter **uma** orbe e SHALL NOT ter seletor de chips nem outro controle de microfone. O toque, o segurar e o arrastar nascem na orbe. KBDCTL-01..03 deixam de valer quando esta frente entra; o tempo do KBDCTL-04 passa para o centro da orbe (KBO-24).
2. **KBO-06**: o **encaixe** SHALL ser o primeiro `BarButton.Mic` da barra visível mais alta que tenha um. O nome no editor de barras passa a **"Orbe"**; o símbolo no JSON das barras **continua `mic`** (o formato de `bars_config_json` não muda). A fábrica segue com um encaixe no início de cada modo (`ShortcutModels.kt:121-145`). Um segundo `Mic` já gravado SHALL ser preservado no JSON, **não desenhado** e **não tocável**. O editor não oferece adicionar outro.
3. **KBO-07**: **ociosa** (`IDLE_READY`, `IDLE_NO_FIELD`, `PENDING_PASTE`) a orbe SHALL caber inteira no encaixe: diâmetro = o menor lado do miolo do botão. Nada entra na faixa acima do teclado. O peso 1,5 do encaixe não muda entre ociosa e gravando (os vizinhos não pulam).
4. **KBO-08**: **sobressalente** (`RECORDING`, `PAUSED`, `CANCELLING`, `SENDING`, `ERROR`, e Visão ativa) a orbe SHALL crescer **só para cima**. O fundo do círculo é o fundo do encaixe. O diâmetro é o mínimo entre a **largura** do encaixe e a altura do encaixe **mais 36 dp**. A interseção do círculo com o retângulo de **qualquer tecla** SHALL ser vazia. Botões da **mesma** barra, fora do encaixe, também ficam fora do círculo (o limite é a largura do encaixe). Uma barra que esteja **acima** do encaixe pode ser coberta pelo círculo; o toque nessa interseção é da orbe.
5. **KBO-09**: a janela do IME SHALL reservar **sempre** 36 dp transparentes entre o overscan da grade e o conteúdo do teclado (`KeyFlowKeyboardView.kt:124-136` e `:182-187` já fazem isso para a grade, com a raiz transparente em `:226-228`). As três semânticas do `InputMethodService.Insets` ficam distintas: `contentTopInsets` (resize/pan) e `visibleTopInsets` (área visível/pan) SHALL ser `gridOverscanPx + 36 dp`, nos dois, para o app de trás **não** tratar a faixa como altura de teclado (a regressão do Termux em `KeyFlowKeyboardService.kt:288-307`); `touchableInsets` continua `TOUCHABLE_INSETS_REGION`, e a `touchableRegion` é o que a janela recebe — devolver falso num hit-test interno **não** entrega o toque ao app. Sem arrasto de grade e com a orbe ociosa, a região tocável começa **abaixo** dos 36 dp (o toque na faixa vai para o app). Com a orbe sobressalente, a região é o conteúdo **unido ao disco do círculo** (centro e raio; **não** o retângulo que o circunscreve), recortada à janela. Pontos do retângulo delimitador fora do disco e fora do conteúdo não entram: o app de trás os recebe. Contraexemplo em densidade 1: faixa 36, encaixe 64×28, centro (32, 32), raio 32; (1, 1) está no retângulo (distância² 1922 > 1024) e acima do conteúdo (y mínimo 36), então fica de fora, assim como os cantos desse retângulo que caem fora do disco. Com grade em arrasto (`gridDragActive`), a região volta a ser a janela inteira, como hoje (`:306-307`). O early-return `if (overscan <= 0) return` (`:302`) SHALL NOT pular a faixa de 36 dp.
6. **KBO-10**: os 36 dp SHALL entrar na reserva de altura da grade (`GridSizing.keyboardContentReservePx`, `:61-89`), junto com barras e fileiras, para uma grade grande não estourar a janela em paisagem (a mesma classe de bug de 2026-07-06).
7. **KBO-11**: a orbe SHALL acompanhar a rolagem horizontal da barra. Com o encaixe fora da área visível, a orbe sai junto e SHALL NOT ficar parada sobre as teclas. O hit-test é uma função pura: ponto no círculo → orbe; senão → a barra ou a tecla. A view da orbe não é uma camada do tamanho do teclado que consuma o toque das teclas.

**Independent Test**: ociosa, os 36 dp não recebem toque (o app de trás recebe) e nenhuma tecla muda de retângulo; gravando, o círculo cruza a borda de cima do teclado, o toque no disco é da orbe, e o toque ao lado na faixa — inclusive (1, 1) e os cantos do retângulo delimitador fora do disco — é **recebido pelo app de trás** porque a `touchableRegion` os exclui, não porque um hit-test posterior devolveu falso. A interseção com cada tecla é vazia. `contentTopInsets == visibleTopInsets == overscan + 36 dp` é necessário e não basta: o app hospedeiro, em adjustResize e adjustPan, retrato e paisagem, com e sem overscan, não perde 36 dp e o campo focado continua visível.

---

### P1-C: Interações (`INT`) ⭐ MVP

**Como** usuário, **quero** os mesmos gestos da orbe flutuante, e poder pôr gravar no toque que eu escolher.

1. **KBO-12**: `SuporteDeInteracoes` SHALL marcar no lugar `TECLADO` as sete interações: `TOQUE_1`, `TOQUE_2`, `TOQUE_3`, `SEGURAR`, `ARRASTAR_CIMA`, `ARRASTAR_LADO`, `ARRASTAR_BAIXO`. `FLUTUANTE` continua com as sete. `NOTIFICACAO` continua só com os três toques (NTF-08). O tipo mora em `com.keyflow.orbe`; quem chegar primeiro cria o arquivo, e esta frente alarga o `TECLADO` se ele tiver nascido só com toques.
2. **KBO-13**: a rajada de toques SHALL usar a janela `Settings.tapBurstWindowMs` (padrão 250, `Settings.kt:121`), reiniciada a cada toque, resolvida **uma vez** quando assenta. Mais de 3 toques seguem `Settings.shortcutOverflow` (`NEAREST_DOWN` ou `CANCEL`). O relógio é injetável. Os limiares de segurar e arrastar são os da orbe flutuante: segurar = janela + 150 ms (`OrbButtonView.kt:717`), ativar o arrasto = 0,7 × o diâmetro **atual** da orbe (`:719`), começar a arrastar = 10 px (`GestureInterpreter.kt:83`).
3. **KBO-14**: **1 toque é remapeável** (diferente da notificação, onde o 1 toque é fixo). A fábrica da config **própria** SHALL ser gravar/enviar no 1 toque, com a semântica de `handleKeyboardMicTap` (`OverlayService.kt:537-543`): ocioso inicia; gravando ou pausado envia; cancelando conclui o descarte; texto pendente cola; erro dispensa e, se houver destino, abre a aba já usada hoje (`KeyFlowKeyboardService.kt:396-404`).
4. **KBO-15**: sempre que uma configuração **substituir** uma ação já atribuída a um toque, gesto ou botão, a tela SHALL avisar **o que foi substituído**. Nada mais é exigido. Gravar/enviar é uma ação como as outras: a config **pode** ter zero, uma ou várias, no Alternar e no segurar-para-falar. A tela SHALL NOT recusar salvar por falta ou excesso de gravar/enviar, e SHALL NOT recolocar gravar/enviar num toque que a pessoa, um fluxo ou uma ação desconhecida tenha deixado sem essa ação. A fábrica ainda nasce com gravar/enviar no 1 toque (KBO-14); isso é o ponto de partida, não uma invariante. Segurar continua sendo o modo (KBO-18): no segurar-para-falar ele grava, e isso não cria obrigação de um toque de gravar.
5. **KBO-16**: o catálogo de um toque SHALL ser: **Gravar/enviar** · **Nenhuma** · **Retrair/expandir o teclado** · **Trocar o teclado** · **Ler a tela (Visão)** · **Pausar/retomar** · **Cancelar/desfazer**. "Pausar/retomar" só age gravando ou pausado. "Cancelar/desfazer" cancela gravando ou pausado (janela de desfazer) e desfaz durante o cancelamento. "Retrair/expandir" só age com o teclado KeyFlow visível. "Trocar o teclado" reusa `OverlayService.showInputMethodPicker()`.
6. **KBO-17**: a fábrica SHALL ser 2 toques = Retrair/expandir e 3 toques = Trocar o teclado — os mesmos padrões de `shortcut2Tap` / `shortcut3Tap` (`Settings.kt:125-126`).
7. **KBO-18**: **segurar e arrastar não são remapeáveis** nesta frente (na orbe flutuante também não são). No modo **Alternar**, segurar entra no modo-arrasto e **não move** a orbe: ↑ pausa (ou retoma, se já pausada), para o centro da tela cancela, ↓ abre o app. No modo **segurar-para-falar**, segurar grava e soltar fora de uma zona abre a janela de envio, como na orbe flutuante; ↑ pausa, lateral cancela, ↓ abre o app sem enviar na hora. As setas são desenhadas **na própria faixa** (não numa janela extra do tamanho da tela: DRAG-06 existe para a orbe flutuante não pular de lugar; aqui o lugar já é fixo). Durante `SENDING`, só o lateral (cancelar) age.

**Independent Test**: ociosa, 1 toque na fábrica inicia depois da janela e o segundo envia; mudar gravar para 2 toques faz o 1 toque não gravar; uma config com zero gravar/enviar salva no Alternar e no segurar-para-falar; substituir o toque que tinha gravar avisa o que saiu e não recoloca gravar em outro toque; 4 toques com `CANCEL` não fazem nada; segurar no Alternar não desloca o encaixe; o círculo gravando não intersecta tecla.

---

### P1-D: Copiar a orbe principal (`CPY`)

**Como** usuário, **quero** que o teclado acompanhe a orbe que eu já configurei, até eu pedir o contrário.

1. **KBO-19**: com copiar ligado, a orbe do teclado SHALL usar, ao vivo, a **skin** (`Settings.orbSkin`), o **modo** (`Settings.overlayRecordingMode`, inclusive segurar-para-falar — D8) e as **ações de 1, 2 e 3 toques** (`shortcut1Tap` no 1 toque só quando o modo da orbe principal já o usa; no Alternar o 1 toque copiado continua sendo gravar/enviar, porque na orbe flutuante o 1 toque do Alternar é o gesto nativo, não `shortcut1Tap` — é copiar a orbe principal, não um fallback que reponha gravar quando a pessoa o tirou da config própria). Não copia `overlaySide`, `orbSizePx` nem a posição. O teclado suporta segurar e arrastar, então **não há lista de interações ignoradas** (a notificação tem; o teclado não).
2. **KBO-20**: ligar ou desligar copiar SHALL NOT criar, apagar ou reescrever gatilho `Orbe(TECLADO, n)` nem a config própria. É o contrato da FLX-60: copiar só muda **qual lista é lida**. Os gatilhos próprios voltam quando copiar é desligado.
3. **KBO-21**: claro/escuro SHALL ser o `dark` que o teclado já usa (`themeDark` via `themeDarkFlow`, `KeyFlowKeyboardService.kt:187-195`), **não** o `uiMode` do sistema (esse é o da notificação, NTF-28) e **não** um interruptor novo. Trocar o tema do teclado redesenha a orbe junto com as teclas.

**Independent Test**: mudar a skin na Sobrepor com copiar ligado muda a orbe do teclado sem escrever `keyboard_orb_config_json`; desligar não apaga um gatilho `Orbe(TECLADO, 2)` plantado; o tema escuro do app escurece a orbe mesmo com o sistema no claro.

---

### P1-E: Aparência (`SKN`)

**Como** usuário, **quero** a mesma pele da orbe, no claro/escuro do teclado.

1. **KBO-22**: as skins SHALL ser as da orbe: Ateliê, Aero, Glitch, Rose Gold, Ouvi e pacote com quadros de orbe, pela mesma `orbStyleFor(id, skinsUnlocked)` (`VoiceOrb.kt:135`). Sem o direito, a paga mostra o **Ateliê** e a escolha **fica gravada**. Pacote não é bloqueado. A skin **do teclado** (`keyboardSkin`) não troca a orbe — o comentário de `Settings.kt:173-174` continua valendo.
2. **KBO-23**: pacote **sem** quadros de orbe SHALL cair no Ateliê. Não há seção nova de pacote. O `FORMAT.md` **não é editado** por esta frente (nomes reservados em §7, inclusive "notificação", seguem ignorados com aviso). O desenho no IME é o pintor da orbe num bitmap em cache (pele, estado, lado, `dark`, pose), fora do caminho de cada tecla; o tempo do centro é desenhado por cima, uma vez por segundo, sem refazer o bitmap. Prévia, galeria e golden usam o tempo de movimento **congelado** (o mesmo gancho `motionTimeMs` da `KeyFlowKeyboardView`). No teclado de verdade o movimento da pele pode correr, no relógio que o teclado já usa para o Glitch e o Rose Gold.
3. **KBO-24**: o estado visual SHALL seguir `orbStateFor` / `orbCenterFor` (`OrbButtonView.kt:674-688`): gravando mostra o tempo correndo; pausada, o glifo de pausa e o tempo parado; cancelando, a lixeira e o tempo parado; transcrevendo, o estado de envio; erro, o X; texto pendente, a orbe ociosa com a descrição "texto pendente: toque para colar"; Visão ativa, o glifo de olho. O tempo é o do `KeyFlowController` (`recordingAccumulatedMs` + o trecho), o mesmo que o mic usa hoje (`BarView.kt:549-554`).

**Independent Test**: cada skin da orbe aparece na prévia e no teclado; sem direito, Aero mostra Ateliê e o id continua `aero`; pacote só de teclado cai no Ateliê; um golden com `motionTimeMs` não muda de um run para o outro.

---

### P1-F: Estado, Visão, háptica (`STA`)

1. **KBO-25**: com a Visão ativa, a ação **gravar/enviar** SHALL encerrá-la antes de qualquer gravação (o `if` de `handleKeyboardMicTap`, `:538`). As outras interações SHALL NOT fazer nada enquanto a Visão estiver ativa.
2. **KBO-26**: ao resolver uma interação que produz efeito (inclusive a recusa de uma ação fora de estado), o teclado SHALL vibrar na intensidade `Settings.vibrationIntensity` (0 = mudo), pelo `Haptics` que a barra já usa. Não vibra em toque que ainda está dentro da janela da rajada.

**Independent Test**: Visão ativa, 1 toque na fábrica encerra a Visão e não começa a gravar; 2 toques não fazem nada; intensidade 0 não vibra.

---

### P1-G: Dados (`DAT`)

1. **KBO-27**: a config própria SHALL ficar num JSON, `keyboard_orb_config_json` (`"v": 1`), no DataStore `keyflow_settings`, ao lado de `bars_config_json`. Campos: `copy`, `skin`, `mode` (`toggle` | `ptt`), `taps` (`"1"`, `"2"`, `"3"`). A escrita SHALL ser atômica sobre o valor atual (`dataStore.edit`), para o auto-save das `Settings` não desfazer a seção e a seção não regravar as `Settings`.
2. **KBO-28**: o codec SHALL ser tolerante: chave ausente → padrões (copiar ligado, skin Ateliê, modo Alternar, 1 = gravar/enviar, 2 = retrair/expandir, 3 = trocar o teclado); JSON malformado → padrões; campo desconhecido ignorado; ação desconhecida (`flow:<id>` ou nome novo) **preservada** ao regravar e tratada como "Nenhuma" no disparo. O JSON não é reescrito para pôr gravar/enviar no 1 toque. Nunca lança.
3. **KBO-29**: o IME SHALL ler por leitura leve (só DataStore, sem o cofre) e observar ao vivo. Trocar skin, modo, ação ou copiar vale no próximo gesto, sem reiniciar o processo. O JSON não contém segredo nem texto ditado.

**Independent Test**: round-trip com skin de pacote e uma ação `flow:…`; JSON ruim → padrões; gravar as `Settings` não altera a chave; duas escritas concorrentes não se perdem.

---

### P1-H: Gancho para Fluxos (`FLW`)

1. **KBO-30**: 2 e 3 toques SHALL consultar o roteador de Fluxos **antes** da ação do lugar, quando a FLX-T29 existir. Até lá, a ação `flow:<id>` é guardada e **não faz nada** (o mesmo reserva da NTF-39 / D10). 1 toque, segurar e arrastar **não** são superfície de gatilho. Fluxo válido roda e a ação por baixo não roda; fluxo inválido avisa "Fluxo incompleto: abra a aba Fluxos" e a ação por baixo não roda; sem gatilho, corre a ação do lugar. O toque que dispara também cancela um fluxo já em execução (FLX-29), na task da FLX-T29 — esta frente só deixa o gancho.
2. **KBO-31**: com copiar ligado, os gatilhos lidos SHALL ser os da orbe **flutuante** (`Orbe(FLUTUANTE, n)`), como a FLX-60. O grupo "Orbe do teclado" no modal de Fluxos, quando a FLX-T29 o mostrar, fica na linha "Copiando da orbe principal". Desligar copiar reativa o gatilho próprio sem tê-lo apagado.

**Independent Test** (na FLX-T29, não antes): 2 toques com gatilho `Orbe(TECLADO, 2)` roda o fluxo e não retrai o teclado; com copiar ligado o mesmo toque usa o gatilho da flutuante; 1 toque nunca consulta o roteador.

---

### P2-I: Qualidade (`QLD`)

1. **KBO-32**: `com.keyflow.orbe` (lugares, suporte, geometria, plano de insets, rajada, resolução, codec) SHALL ser **JVM puro** (sem `android.*`, sem `Context`, sem Compose). O desenho, o `onComputeInsets` e o `MotionEvent` ficam em `com.keyflow.ime`. `ArchitectureRulesTest` confere, no mesmo espírito de `fluxos/` e `fluxos/execucao` (com `fluxos/app` de fora). Tela de config usa `collectAsStateOnMain`. Nada de `runBlocking` novo.
2. **KBO-33**: todo texto novo SHALL passar por `tr("…")`, com `i18n/en/OrbeTeclado.kt` e `i18n/es/OrbeTeclado.kt`. `TranslationsTest` verde.
3. **KBO-34**: a orbe SHALL ter `contentDescription` por estado (as frases de `OrbButtonView.describe`, `:690-699`, mais "texto pendente: toque para colar" e a da Visão). O alvo de toque ocioso é o encaixe inteiro (a altura da barra hoje já é ~28 dp; não piora). Com a orbe sobressalente, o círculo também é alvo.
4. **KBO-35**: goldens que **desenham** o mic da fábrica mudam de propósito (o glifo vira a orbe ociosa): `keyboard-*`, `bar-default-*`, `tour-keyboard-mic`. Rotas novas: orbe gravando e orbe pausada, claro e escuro, com a faixa de 36 dp visível e nenhuma tecla coberta. Qualquer outro golden fica **bit-idêntico**.
5. **KBO-36**: teste instrumentado SHALL rodar com `pool instrumentar` no emulador (`ANDROID_SERIAL=emulator-5554`). SHALL NOT usar `connectedAndroidTest` no A32.
6. **KBO-37**: a Docs e o tour do microfone do teclado SHALL passar a descrever a orbe (toques, segurar, arrastar, sobressair). O `docsSection` correspondente e o `website/_fonte/doc.json` são regenerados na task (`DocsSiteExportTest`).

**Independent Test**: um arquivo em `com.keyflow.orbe` que importe `android.` falha a regra; `pool instrumentar` da classe de gesto fica verde no emulador; os goldens fora da lista acima não mudam um byte.

---

## Edge Cases

- WHEN não há nenhum `Mic` nas barras visíveis THEN o teclado não desenha orbe e não reserva os 36 dp (não há o que sobressair). Gravar por esse teclado não existe; a orbe flutuante e a notificação seguem as delas. O fallback de barra vazia (`BarsConfig.fallbackFor`) continua trazendo a fábrica, que tem o encaixe.
- WHEN a largura do encaixe é menor que a altura + 36 dp THEN o diâmetro é a largura e a orbe sobressai menos que 36 dp. A faixa reservada continua 36 dp (a janela não muda de tamanho no meio da gravação).
- WHEN a pessoa rola a barra no meio da gravação THEN a orbe vai com o encaixe (KBO-11) e o plano de insets é recalculado no mesmo frame.
- WHEN uma grade efêmera está em arrasto THEN a região tocável é a janela inteira (KBO-09) e o popup da grade, que já é uma janela acima, ganha o toque. A orbe não cria uma segunda janela.
- WHEN o modo é segurar-para-falar, próprio ou copiado THEN nenhum toque precisa ser gravar/enviar. O 1 toque copiado é `shortcut1Tap` mapeado (pode ser "Nenhuma"); quem grava é o segurar (KBO-18, KBO-19). A fábrica própria nesse modo nasce com 1 toque "Nenhuma". Salvar com zero gravar/enviar é aceito nos dois modos (KBO-15).
- WHEN a ação efetiva de um toque é desconhecida THEN o disparo trata como "Nenhuma" e o JSON não é reescrito (KBO-28). Não se põe gravar/enviar no 1 toque para completar a config.
- WHEN a pessoa substitui uma ação já atribuída (toque, gesto ou botão) THEN a tela avisa o que foi substituído e grava a nova ação. Não exige que outro toque fique com gravar/enviar (KBO-15, D9).
- WHEN o teclado abre no meio de uma gravação já começada pela orbe flutuante THEN a orbe do teclado nasce sobressalente, no estado atual (`updateMicState` já sincroniza isso em `KeyFlowKeyboardService.kt:429`).
- WHEN "copiar" está ligado e `orbSkin` é um pacote só de teclado THEN a orbe mostra Ateliê (KBO-23).

## Success Criteria

- [ ] No emulador, gravar pelo teclado mostra a orbe acima da borda do teclado, o `m:ss` no centro, e nenhuma tecla coberta; soltar o dedo numa tecla ao lado ainda escreve.
- [ ] O app de trás não perde 36 dp: comparação antes/depois da faixa, em adjustResize e adjustPan, retrato e paisagem, com e sem overscan; o campo focado continua visível. `contentTopInsets == visibleTopInsets` é necessário e não basta sozinho.
- [ ] Com a orbe sobressalente, o app de trás recebe o toque dos cantos do retângulo delimitador que ficam fora do disco.
- [ ] Substituir uma ação já atribuída avisa o que saiu; uma config sem gravar/enviar salva, no Alternar e no segurar-para-falar.
- [ ] Copiar ligado acompanha a Sobrepor, inclusive o segurar-para-falar; desligado, a config própria volta intacta.
- [ ] Um fluxo em `Orbe(TECLADO, 2)` só passa a disparar na FLX-T29, sem ciclo com esta frente.
- [ ] Goldens fora dos que desenham o mic permanecem bit-idênticos.

## Decisões do autor desta spec

| # | Decisão | Por quê | Alternativa |
| - | ------- | ------- | ----------- |
| A1 | A faixa é de **36 dp**, sempre reservada, **entre** o overscan da grade e as barras — não se reusa o overscan da grade como lugar da orbe. **Provisória até ver no aparelho** (D5, 29/10): não é medida fechada | O overscan é 0 sem grade e, com grade, é do tamanho da maior grade e precisa continuar deixando o toque passar. 36 dp + a altura da barra (~28 dp) dá um círculo de ~64 dp, que ainda cabe na largura do encaixe de peso 1,5 na fábrica | Crescer a janela só enquanto grava (pula de tamanho a cada estado); ou desenhar dentro do overscan da grade |
| A2 | O círculo sobressalente tem o **fundo no fundo do encaixe** e o diâmetro limitado à **largura** do encaixe | É o que impede cobrir tecla (abaixo) e vizinho (ao lado). Para cima não há tecla | Centralizar no encaixe e deixar o círculo invadir a primeira fileira |
| A3 | "Copiar" vem **ligado**, como na notificação (NTF-12), e **inclui o segurar-para-falar** (D8, 29/10) | Quem não mexe em nada continua com uma orbe só. A config própria nasce igual à fábrica da flutuante, então ligar ou desligar no primeiro dia não muda o gesto | Recusada: vir desligado, ou manter o toque-de-mic mesmo quando a orbe flutuante está em segurar-para-falar |
| A4 | Segurar e arrastar ficam **fixos**, como na orbe flutuante (D7, 29/10). O que se remapeia são os três toques | Um segundo catálogo só no teclado divergiria da orbe que o Yan chamou de interface única | Arrastar também escolhível na seção |
| A5 | A orbe **não se move**. O lateral é em direção ao **centro da tela**, a partir do centro da orbe, não o `overlaySide` | O encaixe pode estar à esquerda (fábrica) ou no meio, se a pessoa reordenar a barra. O lado da orbe flutuante não descreve isso | Usar `overlaySide` mesmo com a orbe fixa na barra |
| A6 | `BarButton.Mic` continua no JSON; o editor só troca o rótulo para "Orbe". Mic extra fica no JSON, some do desenho e não é tocável (D6, 29/10) | Mudar o formato da barra bate na frente de Fluxos, que só pode acrescentar o caso `flow` | Migrar `mic` → `orb` na leitura e apagar os extras |
| A7 | O modo da config própria, com copiar desligado, nasce **Alternar** (toque grava), não herda sozinho o modo da flutuante | É o comportamento do teclado hoje (`Settings.kt:105-107`). Herdar o modo só com "copiar" ligado deixa a escolha explícita | Teclado sem modo próprio: ou copia, ou é sempre toque |
| A8 | O `FORMAT.md` não muda | O teste do formato quebra se o documento divergir do parser. Os quadros `orb_*` já vestem esta orbe | Seção `keyboardOrb` agora, com `schemaVersion` 1 e campo desconhecido |
| A9 | Bloco **17** no `DEVELOPMENT-ORDER` | O bloco 15 é a notificação, já na `main`. O bloco 16 fica com a spec de Computador (`equipe/computador-spec`). Numerar esta frente como 16 colidiria com ela | Bloco 16 aqui e renumerar o Computador |
| A10 | A seção mora na aba **Teclado**, não numa aba nova | A orbe é parte do teclado. A notificação precisou de aba porque não tinha uma | Aba "Orbe do teclado" no grupo Capacidades |

### Dúvidas para o Yan

Respondidas em 29/10 (D5–D10). Nenhuma em aberto.

## Requirement Traceability

| Requirement | Story | Tasks | Status |
| ----------- | ----- | ----- | ------ |
| KBO-01 | P1-A | KBO-T6 | Pending |
| KBO-02 | P1-A | KBO-T6 | Pending |
| KBO-03 | P1-A | KBO-T2, KBO-T6 | Pending |
| KBO-04 | P1-A | KBO-T3, KBO-T6 | Pending |
| KBO-05 | P1-B | KBO-T5 | Pending |
| KBO-06 | P1-B | KBO-T5, KBO-T6 | Pending |
| KBO-07 | P1-B | KBO-T1, KBO-T5 | Pending |
| KBO-08 | P1-B | KBO-T1, KBO-T5 | Pending |
| KBO-09 | P1-B | KBO-T4, KBO-T5, KBO-T10 | Pending |
| KBO-10 | P1-B | KBO-T4, KBO-T5 | Pending |
| KBO-11 | P1-B | KBO-T1, KBO-T5 | Pending |
| KBO-12 | P1-C | KBO-T1 | Pending |
| KBO-13 | P1-C | KBO-T3, KBO-T10 | Pending |
| KBO-14 | P1-C | KBO-T3, KBO-T10 | Pending |
| KBO-15 | P1-C | KBO-T3, KBO-T6 | Pending |
| KBO-16 | P1-C | KBO-T3 | Pending |
| KBO-17 | P1-C | KBO-T2, KBO-T3 | Pending |
| KBO-18 | P1-C | KBO-T3, KBO-T5, KBO-T10 | Pending |
| KBO-19 | P1-D | KBO-T3, KBO-T6 | Pending |
| KBO-20 | P1-D | KBO-T2, KBO-T3 | Pending |
| KBO-21 | P1-D | KBO-T5, KBO-T7 | Pending |
| KBO-22 | P1-E | KBO-T7 | Pending |
| KBO-23 | P1-E | KBO-T7 | Pending |
| KBO-24 | P1-E | KBO-T5 | Pending |
| KBO-25 | P1-F | KBO-T3, KBO-T10 | Pending |
| KBO-26 | P1-F | KBO-T5 | Pending |
| KBO-27 | P1-G | KBO-T2 | Pending |
| KBO-28 | P1-G | KBO-T2 | Pending |
| KBO-29 | P1-G | KBO-T2, KBO-T5 | Pending |
| KBO-30 | P1-H | KBO-T3 | Pending |
| KBO-31 | P1-H | KBO-T3 | Pending |
| KBO-32 | P2-I | KBO-T1 | Pending |
| KBO-33 | P2-I | KBO-T6, KBO-T8 | Pending |
| KBO-34 | P2-I | KBO-T5 | Pending |
| KBO-35 | P2-I | KBO-T9 | Pending |
| KBO-36 | P2-I | KBO-T10 | Pending |
| KBO-37 | P2-I | KBO-T8 | Pending |

A FLX-59 e a parte de teclado da FLX-60 **não** estão nesta tabela: continuam na FLX-T29 (texto que substituiu o bloqueado em `flows/tasks.md`), que depende de KBO-T3 e KBO-T5 e não é dependência deles.
