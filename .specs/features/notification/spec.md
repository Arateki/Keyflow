# Notificação (aba Notificação) — Specification

> **A notificação do KeyFlow vira uma superfície da orbe.** Em vez de uma barra com título, onda e quatro botões, ela mostra **só a
> orbe** — na skin que a pessoa escolheu (Ateliê, Sistema, as pagas Aero, Glitch, Rose Gold e Ouvi, ou um pacote `.zip`) — e a
> orbe recebe os **mesmos tipos de interação** da orbe flutuante, dentro do que o Android permite a uma notificação: **1, 2 e 3
> toques**. O que cada interação faz é definido **por interação**; a opção **"copiar a configuração da orbe principal"** traz a
> skin e as ações da orbe flutuante. A aba **Notificação** (hoje "Em breve") reúne tudo isso: estado e permissão, prévia viva,
> aparência, interações, tela bloqueada e o comportamento ao dispensar.
> **Status**: rascunho para o Yan revisar (2026-10-29). **Fora da v1** (só `main`, depois de Fluxos). Decisões do Yan de 29/10
> em "Decisões". Levantamento de origem: relatório da equipe `notificacao-levantamento.md` (fora do git; código com arquivo:linha,
> limites do Android com fontes e referências).

## Problem Statement

Hoje a notificação (`RecordingNotification.kt`, 199 linhas) é uma `RemoteViews` de **62–70 dp** com ícone, título, cronômetro, onda
e até quatro botões (gravar, pausar/retomar, parar e colar, cancelar/desfazer, ⚙). Ela é **obrigatória**: é a notificação do
serviço em primeiro plano de microfone (`OverlayService.promoteToForeground`, `:243`; manifest `microphone|mediaProjection`), que
roda enquanto a orbe flutuante está ativa e durante a gravação e a Visão. Problemas:

1. **Altura.** Com `targetSdk` 36 o conteúdo custom entra num template do sistema e a altura recolhida cai de 106 dp para **48 dp**
   (Android 12+). O nosso layout (62–70 dp) provavelmente é cortado; nunca foi medido num aparelho.
2. **Não segue a orbe.** A orbe tem seis skins (e pacotes); a notificação tem uma aparência só, e o claro/escuro vem de
   `Settings.themeDark` (o tema do app), não do sistema.
3. **Botões demais, sem dono.** Pausar, parar e cancelar existem em três lugares com três formas (orbe por gesto, mic do teclado
   por toque + seletor, notificação por botões). A decisão do Yan é **uma interface só: a orbe**.
4. **Tela bloqueada sem controle.** É `VISIBILITY_PUBLIC` e o botão ● inicia a gravação com o aparelho bloqueado
   (`.specs/uat-e2e-scenarios.md` 13.4–13.5: "não confirmado"). Não há nenhuma opção nem checagem.
5. **Dispensar.** No Android 14+ o usuário dispensa a notificação do serviço em primeiro plano; o serviço segue vivo, a
   notificação some e o código a repõe a cada estado novo (`postNotification`, `:546`).
6. **Estados sem tratamento.** `ERROR` e `PENDING_PASTE` caem em "KeyFlow ativo"; tocar no corpo não faz nada (sem `contentIntent`).
7. **A aba não existe.** `ConfigTab.NOTIFICACAO` mostra o cartão "Em breve" (`ConfigScreen.kt:512-514`); nenhum campo de `Settings`
   fala de notificação.

## O que o Android permite (e o que isso impõe)

Fontes: levantamento (páginas de fora e AOSP acessados em 2026-10-29) e o código desta árvore. Onde a fonte não foi aberta, a
linha diz **a verificar** e a task de prova (NTF-T1, NTF-T14) fecha.

| # | Limite da plataforma | Consequência nesta feature | Fonte / estado |
| - | -------------------- | -------------------------- | -------------- |
| L1 | **Uma notificação só recebe toques** (`PendingIntent` de um `RemoteViews`). Não há "segurar", arrastar nem gesto de várias pontas | A orbe da notificação tem **1, 2 e 3 toques** e nada mais. **Segurar-para-falar e os arrastos (↑ pausa, lateral cancela, ↓ abre o app) não existem** nela: a gravação é **tocar para começar / tocar para parar** | levantamento, "Visual"; modelo de toques em `OrbButtonView.kt:594-615` |
| L2 | Contar 2 e 3 toques é trabalho do app: cada toque chega como um `PendingIntent` separado | O serviço conta os toques numa janela curta (`Settings.tapBurstWindowMs`, 250 ms por padrão) e resolve quando assenta — o 1 toque espera a janela, como na orbe em Toggle | `OrbButtonView.kt:591-615` |
| L3 | Com `targetSdk` ≥ 31 a altura **recolhida** de um conteúdo custom é de **48 dp** (tetos: no máximo 48 dp recolhida, 88 dp heads-up, 252 dp expandida); toda notificação é expansível | A orbe tem **40 dp** de arte e 48×48 dp de alvo; o mesmo conteúdo serve recolhido e expandido. Se não couber no aparelho, **não há reserva** de notificação padrão do sistema (D7) — por isso a prova de altura vem primeiro (NTF-T1) | developer.android.com/about/versions/12/behavior-changes-12 e …/custom-notification; **a verificar no A32** |
| L4 | Um clique em `RemoteViews` roda um `PendingIntent` de **serviço/broadcast com o aparelho bloqueado**; Activity sempre exige desbloqueio. `Notification.Action.setAuthenticationRequired` vale só para ações padrão — **um botão de `RemoteViews` nunca consegue exigir desbloqueio** | O app **verifica o `KeyguardManager` no momento do toque** e recusa com aviso quando a opção "permitir com a tela bloqueada" está desligada (D3). Ações que abrem Activity nunca rodam bloqueado | AOSP `StatusBarRemoteInputCallback.handleRemoteViewClick`; `NotificationRemoteInputManager.java:155-157`; OEM pode divergir (Samsung) — **a verificar** |
| L5 | `VISIBILITY_PRIVATE` + `setPublicVersion` mostra uma versão neutra na tela bloqueada, mas **o usuário manda**: o sistema pode mostrar tudo (ou nada) por canal e por configuração do aparelho | Nunca confiar na visibilidade como controle de acesso: a checagem do toque (L4) é a defesa; a notificação nunca leva texto ditado | build-notification (levantamento) |
| L6 | **Bitmaps em `RemoteViews`**: o sistema avisa acima de 2 MB e **remove a view inteira a partir de 5 MB** (`NotificationManagerService.removeRemoteView`); além disso a notificação cruza o Binder, cujo limite de tamanho da transação será medido na NTF-T9/T11 antes de ser citado | Orçamento nosso, bem abaixo: **≤ 256 KB** por notificação (alvo) e **1 MB** de teto duro (número nosso, não do sistema), medidos pelo `allocationByteCount` dos bitmaps que entram (NTF-27). Uma orbe só; sem `setCustomBigContentView` (não duplica bitmap) | AOSP `NotificationManagerService.java` (levantamento); Binder: **a medir** (NTF-T9/T11) ou citar fonte |
| L7 | `RemoteViews` **não anima** a orbe (só `Chronometer` e similares) | Um **quadro estático por estado**; o tempo no centro é um `Chronometer` do sistema (não se re-posta por segundo) | levantamento |
| L8 | **Android 14+**: `setOngoing` deixa de impedir o usuário de dispensar a notificação do serviço em primeiro plano (continua não dispensável com o aparelho bloqueado e em "Limpar tudo"); o serviço segue vivo. Antes do 14, `setOngoing` impede | Dispensar **não é erro e não muda configuração** (D9); a notificação volta quando for necessária. Em Android < 14 simplesmente não dispensa | developer.android.com/about/versions/14/behavior-changes-all |
| L9 | Enquanto o serviço está em primeiro plano o Android **exige** uma notificação; `POST_NOTIFICATIONS` negada (13+) não impede o serviço — ela só some do painel | Opção "mostrar" desligada ⇒ notificação **mínima** obrigatória (NTF-21); permissão negada ⇒ a aba explica e o serviço funciona | developer.android.com/develop/ui/views/notifications/notification-permission; …/fgs/launch |
| L10 | **Início de Activity a partir de um serviço em segundo plano é restrito** (Android 10+; "trampolins" de notificação, Android 12+). O app tem `SYSTEM_ALERT_WINDOW`, que isenta (é o que a `VisionConsentActivity` usa), mas só com a permissão concedida | Ações que precisam de Activity (Ler a tela/Visão e o seletor de teclados do sistema) dependem da permissão de sobreposição; sem ela, aviso. **a verificar no A32** (NTF-T14) | behavior-changes-12; `OverlayService.showInputMethodPicker` |
| L11 | O `NotificationManagerService` **descarta atualizações** acima de uma taxa por app | Re-postar só ao mudar de estado e **coalescer** (NTF-42). O valor exato da taxa **não está citado aqui: confirmar na fonte do AOSP** na NTF-T11 | a verificar |
| L12 | Notificação de serviço em primeiro plano pode ter a **exibição adiada** em até ~10 s no Android 12+, com exceções (entre elas "tem botões de ação padrão", que a nossa não terá) e o escape `FOREGROUND_SERVICE_IMMEDIATE` | Usar `setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)` | developer.android.com (behavior-changes-12) — **a verificar** o texto exato na NTF-T9 |

## Goals

- [ ] A notificação mostra **só a orbe** (skin escolhida), cabe nos **48 dp** recolhidos e no mesmo conteúdo expandido.
- [ ] A orbe da notificação recebe **1, 2 e 3 toques**, todos remapeáveis; Gravar/enviar é só uma das ações, sem mínimo obrigatório; qualquer substituição de ação atribuída pede confirmação genérica.
- [ ] **"Copiar a configuração da orbe principal"**: skin e ações da orbe flutuante, ignorando o que a notificação não suporta (e a tela diz o quê).
- [ ] **Tela bloqueada**: a notificação só controla a orbe com a opção ligada (padrão **desligada**); o toque é conferido no `KeyguardManager`; nunca há texto ditado.
- [ ] **Dispensar** (Android 14+) dispensa de fato, **não mexe em nenhuma configuração**, e a notificação volta quando for necessária de novo ou ao abrir a aba.
- [ ] Skins **Ateliê, Sistema, pagas (renderizadas em bitmap) e pacotes** vestem a notificação, respeitando o limite de bitmaps; segue o **claro/escuro do sistema**.
- [ ] Pacote de skin com seção **`notification`** aditiva (sem subir `schemaVersion`); qualquer combinação de orbe, teclado e notificação.
- [ ] Provas **no aparelho** (altura 48 dp, tela bloqueada, dispensar) por instrumentados/UiAutomator e roteiro do Yan.
- [ ] Persistência em um JSON do DataStore `keyflow_settings` com escrita atômica; texto novo em `tr("…")` (PT/EN/ES); goldens claro/escuro.

## Out of Scope

| Fora | Razão |
| ---- | ----- |
| Botões de gravação na notificação (pausar, parar, cancelar, desfazer, ⚙, ●) | Decisão do Yan (D1): a **orbe é a interface única**. Pausar e cancelar continuam existindo como **ações atribuíveis** a 2 e 3 toques (NTF-11), não como botões |
| **Segurar-para-falar** e os arrastos na notificação | Não existem na plataforma (L1). A tela lista o que "copiar" ignora |
| Notificação padrão do sistema como reserva se os 48 dp cortarem | Decisão do Yan (D7). Se a prova da NTF-T1 mostrar corte, a decisão volta ao Yan (dúvida Q9) — não há plano B silencioso |
| Tile de Configurações rápidas ("Ditar") | Decisão do Yan (D8) |
| Live Updates / `ProgressStyle` (Android 16), MediaStyle, widget (Glance) | Live Updates exige notificação sem `RemoteViews` e não lista "gravação" entre os usos; MediaStyle não se aplica (o KeyFlow não toca mídia); widget vale pouco ao lado da orbe |
| Animação da orbe na notificação | `RemoteViews` não anima (L7) |
| Ícone pequeno (barra de status) por skin | Segue o ícone atual; só alfa, o sistema o tinge. Troca do `ic_btn_speak_now` por um vetor próprio pode vir depois, sem formato novo |
| Orbe do **teclado** | Outra feature (spec própria, ainda não escrita). Esta spec só define o modelo de interações que as duas orbes compartilham (design §3) |
| "Rodar fluxo" como ação | Entra **depois** que a aba Fluxos existir (D10, NTF-39 / NTF-T20); até lá o catálogo só tem as ações nativas |
| Resposta inline (`RemoteInput`), importância/som configuráveis | Sem pedido; o canal `keyflow_overlay` continua `IMPORTANCE_LOW` e o ajuste fino é do sistema (NTF-23) |
| Variantes claro/escuro da arte do pacote | Reservado no FORMAT.md §7 (`*_dark`); o pacote tem uma aparência só (SKP D5) |

---

## Decisões do Yan (2026-10-29)

| # | Decisão | Onde vale |
| - | ------- | --------- |
| D1 | **A orbe é a interface única.** Teclado e notificação também exibem uma orbe que recebe os mesmos **tipos** de interação da orbe do overlay; o que cada interação faz é definido **por interação** (não há botões separados de microfone etc.). Teclado e notificação têm a opção **"copiar a configuração da orbe principal"** | NTF-05, NTF-08, NTF-10..12 |
| D2 | Notificação só recebe toques (`PendingIntent`). **1, 2 e 3 toques** são contados pelo app numa janela curta; **segurar-para-falar e arrastar não existem** — a gravação é tocar-para-começar/tocar-para-parar. "Copiar da orbe principal" **ignora** as interações não suportadas e a tela mostra quais | NTF-08..12 |
| D3 | **Tela bloqueada**: a notificação controla a orbe **apenas** com a opção **"permitir com a tela bloqueada"** ligada (padrão **desligada**). Como botões de `RemoteViews` não exigem desbloqueio, o app confere o `KeyguardManager` **no toque** e recusa, com aviso, se a opção está desligada. **Texto ditado nunca aparece** na tela bloqueada (`VISIBILITY_PRIVATE` + `publicVersion`) | NTF-15..17 |
| D4 | **Durante a gravação a notificação mostra só a orbe** | NTF-05, NTF-07 |
| D5 | **Skins**: Ateliê, Sistema, pacotes **e** as pagas (Aero, Glitch, Rose Gold, Ouvi — pintores de Canvas, renderizados em bitmap) vestem a notificação; respeitar o limite de bitmaps de `RemoteViews`. O pacote ganha a seção `notification`, **aditiva** (sem subir `schemaVersion`). Um pacote pode trazer **qualquer combinação**: só orbe, só teclado, só notificação ou várias — estende a regra do FORMAT.md §1 | NTF-24..27, NTF-31..35 |
| D6 | Segue o **claro/escuro do sistema** | NTF-28 |
| D7 | **Sem notificação padrão do sistema como reserva**: a notificação só com a orbe cabe nos 48 dp recolhidos do Android 12+ | NTF-06 |
| D8 | **Sem tile** de Configurações rápidas | Out of Scope |
| D9 | **Dispensar no Android 14+ dispensa de fato e NÃO altera nenhuma configuração** *(revisada no mesmo dia: a primeira versão desligava "mostrar notificação" pelo `deleteIntent`; o Yan trocou).* A notificação dispensada **volta a aparecer quando for necessária de novo** (a próxima vez que o serviço precisar dela: orbe flutuante, gravação, Visão) **ou quando a pessoa abrir a aba Notificação**. Onde o Android **exige** a notificação (serviço em primeiro plano) com a opção "mostrar" desligada, vale a **notificação mínima/silenciosa**, dispensável no 14+, sem quebrar o serviço. A prova "abrir o app ao dispensar" saiu | NTF-18..21 |
| D10 | Até a aba Fluxos ficar pronta, a notificação usa **só as ações nativas de hoje**; "rodar fluxo" entra quando Fluxos existir | NTF-11, NTF-39 |
| D11 | **29/10, 22:57**: gravar é uma ação/fluxo entre outros, não o foco obrigatório. Os três toques são remapeáveis, sem exigir nenhum Gravar/enviar. O aviso existe para **qualquer substituição de ação atribuída**, dizendo qual era e onde; não há aviso exclusivo de gravação. Esta decisão substitui A1 e responde Q2 | NTF-10..12, NTF-36/37; lição `../../project/licoes/2026-10-29-fluxos-decisoes.md` |

---

## User Stories & Acceptance Criteria

### P1-A: A aba Notificação (`TAB`) ⭐ MVP

**Como** usuário, **quero** uma aba que mostre e controle a notificação da orbe.

1. **NTF-01**: a aba **Notificação** SHALL sair do "Em breve" (`ConfigScreen.kt:512-514`) **só na task que liga o painel** (NTF-T13); antes disso o cartão continua. O `NotificacaoPanel` SHALL ter, nesta ordem: **Estado** (NTF-02), **Prévia** (NTF-03), **Aparência** (skin, NTF-24), **Interações** (1, 2, 3 toques + copiar, NTF-10..12), **Tela bloqueada** (NTF-15) e **Sistema** (NTF-04). Tema/paleta da aba (claro e escuro), `verticalScrollbar`, sem rolagem horizontal em 360 dp.
2. **NTF-02**: **Estado**: o painel SHALL mostrar (a) a permissão de notificações (o chip que o `ModeCards` já usa, `:113-116`) com o texto "Sem ela a notificação não aparece no painel; o KeyFlow continua funcionando" e o atalho para pedi-la; (b) o interruptor **"Mostrar a notificação da orbe"** (padrão **ligado**) com o texto do que acontece desligado (NTF-21); (c) uma linha sobre **quando** ela existe — "enquanto a orbe flutuante estiver ligada" (Sobrepor) ou "só durante a gravação" (só teclado, `maybeStopWhenIdle`, `OverlayService.kt:500`).
3. **NTF-03**: **Prévia viva**: o painel SHALL mostrar a **`RemoteViews` real** (a mesma que vai ao `notify`, inflada por `AndroidView`, como a galeria faz) na skin efetiva e no tema do sistema, com um seletor de estado (Ociosa · Gravando · Pausada · Transcrevendo · Cancelando · Erro · Visão). O tempo da prévia é congelado.
4. **NTF-04**: **Sistema**: um botão "Ajustes de notificação do sistema" SHALL abrir os ajustes do canal (`ACTION_CHANNEL_NOTIFICATION_SETTINGS`), onde ficam som, importância e visibilidade na tela bloqueada — decisões que são do usuário (L5).

**Independent Test**: abrir a aba → permissão, interruptor, prévia e seções aparecem; trocar o estado da prévia muda a orbe e o glifo; "Ajustes do sistema" abre o canal.

---

### P1-B: A orbe da notificação e as interações (`ORB`) ⭐ MVP

**Como** usuário, **quero** escolher as ações dos toques da orbe da notificação, incluindo gravar quando eu quiser.

1. **NTF-05**: a notificação SHALL conter **só a orbe**: um `ImageView` da orbe e, durante gravação, pausa e cancelamento, o **tempo `m:ss` no centro dela** (um `Chronometer`, parte da orbe como na orbe flutuante). SHALL NOT ter botão, título, onda, ícone grande, texto nem a legenda de estado. Não existe `RemoteViews` alternativa "com botões".
2. **NTF-06**: **altura**: a arte da orbe SHALL ter no máximo **40 dp** de lado, o alvo de toque **48×48 dp**, e o conteúdo SHALL caber nos **48 dp** recolhidos do Android 12+. O mesmo `RemoteViews` serve recolhido e expandido (**sem** `setCustomBigContentView`). SHALL NOT existir notificação padrão do sistema como reserva (D7). A conformidade é provada em aparelho (NTF-43).
3. **NTF-07**: **estados**: a orbe SHALL refletir o estado único (`ButtonState`) com a aparência e o centro da tabela do design §6 — Ociosa (`IDLE_*`) em repouso; Gravando com tempo correndo; Pausada com glifo de pausa e tempo parado; Cancelando com lixeira e tempo parado; Transcrevendo; **Erro** com o X; **Texto pendente** (`PENDING_PASTE`) como ociosa com a descrição "Texto pendente" (sem prometer ação de um toque remapeado); **Visão** ativa com o glifo de olho. Os estados sem tratamento hoje (`ERROR`, `PENDING_PASTE`) passam a ter.
4. **NTF-08**: **interações suportadas**: o modelo compartilhado de interações (design §3) SHALL declarar, por lugar (`FLUTUANTE`, `TECLADO`, `NOTIFICACAO`), quais tipos de interação existem. A notificação SHALL suportar **`TOQUE_1`, `TOQUE_2` e `TOQUE_3`** e SHALL declarar **não suportados**: segurar, arrastar para cima, para o lado e para baixo. Nenhum código da notificação trata uma interação fora dessa lista.
5. **NTF-09**: **contagem**: cada toque na orbe chega ao `OverlayService` como uma ação própria; o serviço SHALL contar os toques numa janela igual a `Settings.tapBurstWindowMs` (a mesma "Tempo de reação" da orbe), reiniciada a cada toque, e resolver **uma vez** quando a rajada assenta. Mais de 3 toques seguem a política `Settings.shortcutOverflow` (`CANCEL` ou `NEAREST_DOWN`, como em `Settings.resolveShortcut`); com `NEAREST_DOWN`, "ativo" vale para 2 e 3 toques com ação diferente de "Nenhuma" (ou com fluxo, NTF-39). A contagem é lógica pura, testada com relógio virtual.
6. **NTF-10**: **1 toque SHALL ser remapeável**, como 2 e 3. **Gravar/enviar** é o padrão da config própria, não uma obrigação: nenhum toque precisa ter essa ação, e zero ou mais de um toque com ela são configurações válidas (D11, 29/10 às 22:57). Quando a ação efetiva for Gravar/enviar, qualquer que seja o número de toques: ocioso **inicia**; gravando/pausado **envia**; cancelando **conclui o descarte já**; texto pendente **cola**; erro **dispensa**; Visão ativa **encerra a Visão**. Reusa `handleKeyboardMicTap` (`OverlayService.kt:537`), sem PTT na notificação.
7. **NTF-11**: **1, 2 e 3 toques** SHALL executar a ação atribuída (somente leitura quando copia, NTF-12). Catálogo nativo: **Gravar/enviar** · **Nenhuma** · **Retrair/expandir o teclado** · **Trocar o teclado** · **Ler a tela (Visão)** · **Pausar/retomar** · **Cancelar/desfazer**. Pausar/retomar só em RECORDING/PAUSED; Cancelar/desfazer cancela nesses estados e desfaz em CANCELLING; Retrair/expandir só com teclado visível. Com Visão ativa, somente a ação Gravar/enviar encerra a Visão; outras não fazem nada. Ações que abrem Activity seguem NTF-16/L10. **Qualquer mudança que substitua ação atribuída SHALL pedir confirmação antes de gravar**, nomeando ação efetiva anterior, nova ação e toque/lugar; cancelar preserva config e efeito. Vale para qualquer ação, sem aviso específico de gravação. Superfície livre (Nenhuma) ou ação idêntica não tem perda a avisar; não se exige reservar gravação.
8. **NTF-12**: **copiar a configuração da orbe principal** (padrão ligado) SHALL copiar skin e ações efetivas de 1/2/3 toques ao vivo, somente leitura ("Copiado da orbe principal"). No Alternar da flutuante, o 1 toque efetivo é Gravar/enviar; no PTT é `shortcut1Tap` mapeado (inclusive Nenhuma); 2/3 usam `shortcut2Tap/3Tap`. Não inventa toque de gravação ao copiar PTT: segurar e arrastar são ignorados, listados com "Uma notificação só recebe toques". Desligar copiar deixa os valores copiados como ponto de partida editável; ligar volta a copiar e guarda os próprios. Se a mudança do interruptor substituir ações efetivas atribuídas, aplica a confirmação genérica de NTF-11 com a lista de toques/ações afetados; sincronização ao vivo de escolhas já confirmadas na origem não abre diálogo em segundo plano. Pacote segue NTF-34.
9. **NTF-13**: **corpo da notificação**: tocar na notificação **fora da orbe** SHALL abrir o KeyFlow (`contentIntent` por `getActivity`; bloqueado, o sistema pede o desbloqueio). Não conta como toque de orbe.
10. **NTF-14**: **retorno tátil**: quando uma rajada de toques é resolvida em uma ação (inclusive a recusa da tela bloqueada), o serviço SHALL vibrar curto na intensidade `Settings.vibrationIntensity` (0 = sem vibração) — o clique de `RemoteViews` não vibra sozinho.

**Independent Test**: na fábrica própria/Alternar copiado, ociosa → 1 toque inicia (após a janela), a orbe muda para gravando e o tempo corre; 1 toque envia; 2 toques com "Pausar/retomar" pausa e retoma; 3 toques com "Nenhuma" não faz nada; 5 toques com `NEAREST_DOWN` cai no 3; ligar "copiar" mostra as ações da orbe principal e a lista do que é ignorado.

---

### P1-C: Tela bloqueada (`LCK`) ⭐ MVP

**Como** usuário, **quero** que ninguém grave nem envie áudio pela tela bloqueada sem eu querer.

1. **NTF-15**: a opção **"Permitir controlar com a tela bloqueada"** SHALL existir na aba e ser **desligada por padrão**.
2. **NTF-16**: a cada toque na orbe, **antes de contar**, o serviço SHALL conferir `KeyguardManager.isKeyguardLocked()`. Com a tela bloqueada e a opção **desligada**, SHALL recusar: nada é contado nem executado, e a pessoa recebe o aviso "Desbloqueie o aparelho para usar a orbe" (`Toast` + vibração curta; o plano B, se o `Toast` não aparecer sobre o bloqueio, é trocar a orbe por um cadeado por 2 s — decidido na NTF-T14). Com a opção **ligada** os toques funcionam, **exceto** as ações que precisam de Activity (Ler a tela, Trocar o teclado pelo seletor-Activity, abrir o app), que SHALL ser recusadas com "Esta ação precisa do aparelho desbloqueado".
3. **NTF-17**: **privacidade**: a notificação SHALL ser `VISIBILITY_PRIVATE` com `setPublicVersion`. A versão pública é a orbe **em repouso**, sem tempo nem estado, quando a opção está desligada; com a opção ligada ela é a orbe do estado real (para controlar). **Nenhum texto ditado, transcrição nem prévia** entra em qualquer `RemoteViews`, extra ou descrição da notificação: o construtor **não tem parâmetro de texto** e um teste varre o que ele produz.

**Independent Test**: opção desligada + aparelho bloqueado → tocar a orbe não inicia gravação e mostra o aviso; ligar a opção → inicia; "Ler a tela" bloqueado → recusa mesmo com a opção ligada; a versão pública não traz estado.

---

### P1-D: Dispensar e notificação obrigatória (`DSM`) ⭐ MVP

**Como** usuário, **quero** poder dispensar a notificação sem quebrar o KeyFlow nem mudar minhas configurações.

1. **NTF-18**: **dispensar** (Android 14+): quando a pessoa dispensa a notificação, o serviço SHALL **seguir vivo** e **nenhuma configuração muda** (nenhuma escrita no DataStore). Dispensada, a notificação **não volta** nas mudanças de estado seguintes (nenhum `notify`) até ser **necessária de novo**: (a) uma nova entrada que hoje chama `ensureForeground()` (orbe flutuante, tecla de mic do teclado, gravar, Visão); (b) o início de uma gravação ou da Visão; (c) NTF-19. O estado "dispensada" vive **na memória do serviço**; o serviço parado o esquece.
2. **NTF-19**: abrir a aba **Notificação** (painel visível) com o serviço em primeiro plano SHALL repor a notificação dispensada. Sem serviço em primeiro plano não há o que repor (o painel não inicia o serviço).
3. **NTF-20**: antes do Android 14, com o aparelho bloqueado e no "Limpar tudo", a notificação SHALL continuar **não dispensável** (`setOngoing(true)`); a prova de dispensar roda em aparelhos dos dois lados da linha (NTF-43).
4. **NTF-21**: com **"Mostrar a notificação da orbe" desligado**, o serviço em primeiro plano ainda exige uma notificação: o app SHALL postar uma **notificação mínima e silenciosa** (canal de importância mínima, ícone pequeno e o texto "KeyFlow ativo", **sem orbe, sem ação**, `VISIBILITY_SECRET`) enquanto o serviço durar; ela é dispensável no 14+ pela mesma regra de NTF-18 e **nunca** impede o `startForeground`. A orbe da notificação não aparece.
5. **NTF-22**: com `POST_NOTIFICATIONS` negada (Android 13+) o serviço SHALL funcionar normalmente; a aba explica a consequência (NTF-02) e o app não falha ao postar.
6. **NTF-23**: **canais**: a notificação da orbe continua no canal `keyflow_overlay` (`IMPORTANCE_LOW`, nome "KeyFlow"); a mínima usa um canal **novo** de importância mínima (a importância de um canal existente não se muda por código). Nenhum som nem heads-up.

**Independent Test**: (Android 14+) dispensar a notificação → o serviço continua, a notificação não volta quando o estado muda, o DataStore não muda; iniciar uma gravação → ela volta; abrir a aba → volta; (Android 13) a notificação não sai do painel; com "mostrar" desligado e a orbe ligada → só a mínima aparece.

---

### P1-E: Aparência — skins (`SKN`) ⭐ MVP

**Como** usuário, **quero** que a notificação use a mesma skin da orbe (ou outra).

1. **NTF-24**: o painel SHALL oferecer as skins **Sistema**, **Ateliê**, **Aero**, **Glitch**, **Rose Gold**, **Ouvi** e os **pacotes** que têm notificação (NTF-32). Com "copiar" ligado a skin é a da orbe principal (somente leitura). Padrão com "copiar" desligado: Ateliê. **Sistema** é um círculo liso na cor de destaque do sistema (Material You a partir do Android 12; uma cor fixa antes), sem textura nem fundo próprio.
2. **NTF-25**: as skins pintadas em `Canvas` (Ateliê, Aero, Glitch, Rose Gold, Ouvi) SHALL ser renderizadas em **bitmap fora da tela** (o mesmo desenho da orbe, sobre o `Canvas` de um `Bitmap` transparente, tempo fixo e determinístico), **um por estado**, **fora da thread principal**, em cache por skin/estado/lado/versão; nunca no caminho do `notify`.
3. **NTF-26**: as skins **pagas** vestem a notificação **somente com `skinsUnlocked`** (a mesma regra de `orbStyleFor(id, unlocked)`); sem o direito, a notificação mostra o Ateliê **sem apagar a escolha** (volta sozinha se o direito voltar). Pacotes são grátis e nunca bloqueados.
4. **NTF-27**: **limite de bitmaps**: o total de bytes dos bitmaps de uma notificação (conteúdo + versão pública) SHALL ficar **≤ 1 MB** — alvo **≤ 256 KB** —, medido por `allocationByteCount`. O lado da orbe é `40 dp × densidade` limitado a **192 px**; um bitmap por `RemoteViews`; sem `setCustomBigContentView`. Um teste falha se a soma passar do teto.
5. **NTF-28**: **claro/escuro**: a notificação SHALL seguir o modo noturno **do sistema** (`Configuration.uiMode`), **não** `Settings.themeDark`, e SHALL ser re-postada ao mudar (o serviço recebe `onConfigurationChanged`). Ateliê tem fundo escuro (`#2B2720`) ou claro (`#F1E7C9`); Sistema e as pagas **não pintam fundo** (aparece o do sistema, que já segue o modo); pacote pinta `notification.background` quando declarado, igual nos dois modos.
6. **NTF-29**: a orbe é **estática** por estado (L7); de um pacote vale o `notification_<estado>.svg`; do que é animado, uma pose fixa. O tempo do centro é o `Chronometer` do sistema na cor de tinta da skin (a `centerInk` da orbe; `notification.ink` do pacote); **não se re-posta por segundo**.
7. **NTF-30**: **falhas**: skin ausente, pacote quebrado, sem direito ou **falha ao renderizar** SHALL cair no **Ateliê**; se nem o Ateliê puder ser renderizado (memória), cai na notificação mínima (NTF-21). Uma falha de skin **nunca** impede o `startForeground` nem derruba o serviço.

**Independent Test**: escolher cada skin → a prévia e a notificação real mudam; sem o direito, a paga mostra o Ateliê e a escolha se mantém; trocar o modo escuro do sistema recolore o Ateliê; a soma de bytes dos bitmaps ≤ 1 MB em todas as skins e densidades de teste.

---

### P1-F: Pacote de skin com seção `notification` (`PKG`) ⭐ MVP

**Como** criador de skin, **quero** vestir a notificação no mesmo pacote.

1. **NTF-31**: o pacote SHALL poder trazer a seção `notification` do manifest — **`notification.ink`** (cor do tempo e dos glifos no centro; padrão `#FFFFFF`) e **`notification.background`** (cor **opaca** do fundo do conteúdo; sem ela, vale o fundo do sistema) — e os arquivos **`notification_idle.svg`** (obrigatório quando há notificação), `notification_listening.svg`, `notification_paused.svg` e `notification_transcribing.svg`: a **orbe inteira**, quadrada (`viewBox` quadrado), fundo transparente, **estática** (um arquivo por estado, sem frames). Estado ausente usa o `idle`. A mudança é **aditiva**: **`schemaVersion` não sobe**; uma versão antiga do app importa o pacote com os avisos de "campo desconhecido" e "arquivos fora do formato", e ignora a notificação.
2. **NTF-32**: um pacote SHALL poder ter **qualquer combinação** de orbe, teclado e notificação — só orbe, só teclado, **só notificação** ou várias. Ele aparece na aba **Sobrepor** se tiver orbe, na aba **Teclado** se tiver teclado e na aba **Notificação** se tiver notificação. O erro "o pacote não tem orbe nem teclado" passa a "não tem orbe, teclado nem notificação".
3. **NTF-33**: o import SHALL validar as peças da notificação como as demais (FORMAT.md §4–§6: regras de SVG, limites, render de teste a 64 px, aviso de `viewBox` não quadrado e de quadro transparente) e relatar "notificação: N estados". Tudo-ou-nada (SKP-IMP-03). A seção `notification` sem os arquivos é ignorada com aviso; `notification.ink`/`background` inválidos são **erro** com o caminho do campo.
4. **NTF-34**: um pacote **com orbe e sem notificação**, usado por **"copiar"** (NTF-12), SHALL ser desenhado a partir do **primeiro quadro** de cada estado da orbe (menor número de `orb_<estado>_<frame>.svg`) e da `orb.centerInk`, sem fundo. Ele **não** é listado como skin própria da notificação. Pacote que só tem teclado cai no Ateliê.
5. **NTF-35**: o `FORMAT.md` SHALL ser estendido **junto com o código** que aceita a seção (a regra do §1, a tabela de arquivos, a tabela do manifest, o relatório do §6 e a remoção de "notificação" da lista reservada do §7), com o teste que amarra o documento ao parser (SKP-DOC-01, `SkinPackFormatDocTest`) e a cópia embutida para a ferramenta do agente (`SkinFormatAssetTest`); o pacote de exemplo ganha as peças de notificação e importa **sem avisos**. O texto exato está no design §8.

**Independent Test**: importar um pacote só de notificação → instalado, aparece só na aba Notificação; um pacote de orbe + teclado + notificação aparece nas três; "copiar" com um pacote só de orbe mostra o primeiro quadro; um `notification.background` translúcido é erro.

---

### P1-G: Dados e persistência (`DAT`) ⭐ MVP

1. **NTF-36**: a configuração SHALL ser guardada em **um JSON**, `notification_config_json` (`"v": 1`), no DataStore `keyflow_settings`, ao lado de `bars_config_json`: `show`, `lockscreen`, `copy`, `skin` e as ações de 1, 2 e 3 toques. As escritas SHALL ser **atômicas e sobre o valor atual** (`dataStore.edit`: lê, aplica uma função pura e grava), de modo que o auto-save das `Settings` (que regrava as `Settings` inteiras) **nunca** desfaça uma escolha desta aba — e a escrita desta aba não regrava as `Settings`.
2. **NTF-37**: o codec SHALL ser **tolerante** (como o das barras): chave ausente → padrões (mostrar ligado, tela bloqueada desligada, copiar ligado, skin Ateliê, 1 toque = Gravar/enviar, 2 toques = Retrair/expandir, 3 toques = Trocar o teclado — o mesmo que as `Settings` trazem de fábrica); JSON malformado → padrões; campo desconhecido ignorado; **ação desconhecida** (`flow:<id>` de uma versão futura, ou nome novo) **preservada** ao regravar e tratada como "Nenhuma" no disparo; skin inexistente → Ateliê. A ausência de `taps.1` em JSON anterior usa o padrão Gravar/enviar; uma ação explícita Nenhuma/desconhecida nunca é trocada por gravar. Não valida presença/quantidade de toques de gravação. Nunca lança.
3. **NTF-38**: o `OverlayService` SHALL ler a configuração por **leitura leve** (só DataStore, sem o cofre cifrado) e observá-la ao vivo: trocar skin, ação, "mostrar" ou "tela bloqueada" vale no toque/estado seguinte, sem reiniciar o serviço. O JSON **não contém** segredo nem qualquer texto ditado.

**Independent Test**: round-trip com ações, skin de pacote e uma ação desconhecida; JSON malformado → padrões; duas escritas concorrentes (aba e serviço) não se sobrescrevem; salvar as `Settings` não toca a chave.

---

### P1-H: Gancho para Fluxos (`FLW`)

1. **NTF-39**: o modelo SHALL **reservar** a ação `flow:<fluxoId>` para 2 e 3 toques: lida, guardada e regravada sem perda, **invisível e sem efeito** enquanto a aba Fluxos não existir (D10). Quando existir (FLX-T28, NTF-T20), o catálogo ganha "Rodar fluxo …" e a resolução consulta os gatilhos `Orbe(NOTIFICACAO, toques)` da spec de Fluxos; com "copiar" ligado, consulta os da orbe flutuante (FLX-60). Um fluxo apagado ou inválido deixa a interação **sem efeito com aviso**, como o gatilho órfão de Fluxos.

---

### P2-I: Qualidade (`QLD`)

1. **NTF-40**: todo texto novo SHALL passar por `tr("…")` (português como chave) com `i18n/en/Notificacao.kt` e `i18n/es/Notificacao.kt` (ordem crescente do português); `TranslationsTest` verde. A descrição de acessibilidade da orbe também é traduzida.
2. **NTF-41**: o pacote `com.keyflow.notificacao` (modelo, codec, contagem de toques, resolução de interação, máquina de visibilidade, guarda de tela bloqueada, escolha de aparência) e o modelo compartilhado `com.keyflow.orbe` SHALL ser **JVM puro** (sem `android.*`, sem `Context`, sem Compose); o código Android fica em `notificacao/app`, que a regra exclui; `ArchitectureRulesTest` confere. Telas usam `collectAsStateOnMain`; nada de `runBlocking` novo; nenhum teste que grave sem parar o serviço.
3. **NTF-42**: **desempenho**: a notificação SHALL ser re-postada só quando algo visível muda (estado, skin, modo noturno, idioma, "tela bloqueada", direito de skins) e **coalescida** — no máximo um `notify` por intervalo curto, com o último estado sempre vencendo; nada de renderizar bitmap, abrir pacote ou ler disco na thread principal; nenhum re-post por segundo (o tempo é do `Chronometer`).
4. **NTF-43**: **provas em aparelho**: as três provas — **altura (48 dp), tela bloqueada e dispensar** — SHALL existir como testes instrumentados/UiAutomator (no emulador do pool e, onde a segurança permitir, nos aparelhos de teste) e um roteiro do Yan para o que exige o PIN dele; os critérios estão no design §12. Resultado e decisões vão para o `STATE`/lições.
5. **NTF-44**: goldens claro/escuro das telas novas (design §13); os goldens `notification-*` antigos são **substituídos de propósito** (o layout muda); **todos os outros** ficam bit-idênticos.
6. **NTF-45**: **acessibilidade**: a orbe (sem texto) SHALL ter `contentDescription` traduzida com estado e efeito do toque ("KeyFlow gravando. Toque para enviar"); alvo ≥ 48×48 dp; a aba tem `contentDescription` em cada ação e não depende só de cor.
7. **NTF-46**: a aba **Docs** SHALL ganhar a seção "aba-notificacao" e `docsSectionFor(NOTIFICACAO)` aponta para ela; o texto antigo da notificação (botões, `DocsPanel.kt:318,331,363-370`) é reescrito; `website/_fonte/doc.json` é regenerado (`DocsSiteExportTest`).
8. **NTF-47**: ao entrar, esta feature **substitui** NOTIFCTL-01/02/03 da `orb-gestures` (botões de pausar/parar/cancelar/desfazer e recolorir pelo tema do app) e os itens 13.1–13.5 do `uat-e2e-scenarios.md`: a traceability daquela spec e o UAT ganham a nota de que valem as regras desta.

---

## Edge Cases

- WHEN o serviço está ocioso no modo só-teclado THEN **não há notificação** (o serviço sai do primeiro plano em `maybeStopWhenIdle`); WHEN a gravação começa THEN ela aparece com a orbe (ou a mínima, se "mostrar" está desligado).
- WHEN a pessoa toca duas vezes e a primeira rajada ainda não assentou THEN os dois toques contam como 2 toques (a janela reinicia a cada toque).
- WHEN o aparelho é bloqueado **durante** a gravação, com a opção desligada THEN a gravação continua (o bloqueio não a para); tocar a orbe é recusado; desbloquear devolve o controle.
- WHEN a opção "tela bloqueada" é ligada ou desligada THEN a notificação é re-postada (versão pública muda) sem reiniciar o serviço.
- WHEN a Visão está ativa THEN a orbe mostra o glifo de olho; o toque configurado como Gravar/enviar a encerra e outras ações não fazem nada; a notificação é a do serviço de captura de tela (`VisionProjectionSession`, `:30-35`), construída pelo mesmo construtor.
- WHEN o idioma muda THEN a descrição de acessibilidade é re-postada no idioma novo.
- WHEN o pacote da skin é reimportado ou removido THEN a notificação acompanha (observa `SkinPackStore.changes`); removido, cai no Ateliê.
- WHEN a densidade da tela muda THEN o cache de bitmaps é invalidado pelo lado em px.
- WHEN o `POST_NOTIFICATIONS` é concedido depois THEN a próxima postagem já aparece, sem reiniciar.
- WHEN a pessoa dispensa a notificação **durante** a gravação THEN a gravação segue; só o controle some até NTF-18 (b)/(c) ou NTF-19.
- WHEN "copiar" está ligado e a orbe principal usa um pacote só de teclado THEN a notificação cai no Ateliê (NTF-34).
- WHEN a ação de 2 toques é "Ler a tela" e o app não tem a permissão de sobreposição THEN aviso "Ler a tela precisa da permissão de sobreposição" (L10), sem tentar abrir a Activity.

---

## Decisões do autor desta spec (a confirmar pelo Yan)

| # | Decisão | Por quê | Alternativa |
| - | ------- | ------- | ----------- |
| A1 | **Substituída pela D11 (29/10, 22:57)**: 1/2/3 remapeáveis, sem mínimo de Gravar/enviar | gravar é uma ação entre outras; confirmação genérica para substituir qualquer ação atribuída | 1 toque fixo ou outro toque obrigatório de gravação (descartados) |
| A2 | O catálogo da notificação inclui **Pausar/retomar** e **Cancelar/desfazer** | Sem os botões, só assim pausar e cancelar existem na notificação (a orbe principal os tem por arrasto, que a notificação não suporta). Com "copiar" ligado (padrão) elas **não** estão ativas — o padrão repete 2 toques = Retrair/expandir, 3 toques = Trocar o teclado | padrão com copiar desligado e 2 toques = Pausar, 3 toques = Cancelar |
| A3 | "Copiar" é **uma chave só**: skin **e** ações | É a leitura de "copiar a configuração da orbe principal"; menos opções na tela | duas chaves (skin e ações) |
| A4 | "Sistema" = círculo liso na cor de destaque do sistema, sem fundo próprio | A orbe não tem skin "Sistema" (só o teclado tem); esta é a mais leve e segue o modo do sistema de graça | notificação com o template padrão (vetada pelo D7) |
| A5 | Pacote **só de orbe** não aparece na aba Notificação, mas "copiar" o desenha pelo 1º quadro | Mantém a regra "aparece onde tem a peça" (FORMAT §1); evita um desenho derivado escolhido às cegas | listar também pacotes só de orbe na aba |
| A6 | O tempo `m:ss` fica **no centro da orbe** (Chronometer), como na orbe flutuante | "Só a orbe" lido como "a orbe como ela é": o tempo faz parte do desenho do centro | sem tempo na notificação |
| A7 | `VISIBILITY_PRIVATE` **sempre**; a versão pública é neutra (desligado) ou real (ligado); a checagem usa `isKeyguardLocked()` | Obedece o D3 literalmente e cobre o caso de o sistema mostrar tudo; `isKeyguardLocked` também cobre o bloqueio por deslizar | `VISIBILITY_PUBLIC` quando a opção está ligada; `isDeviceLocked` |
| A8 | Tocar no corpo da notificação abre o KeyFlow | A notificação de hoje não faz nada ali; o levantamento sugeriu | corpo sem ação |
| A9 | "Dispensada" é um **flag em memória**; volta por: nova entrada que exige o primeiro plano, início de gravação/Visão ou abrir a aba | Segue o D9 revisado sem escrever nada; a lista é a tradução de "quando for necessária" | voltar só no próximo `startForeground` |
| A10 | O `FORMAT.md` **não é editado nesta spec** | Ele é a fonte única do contrato: o teste `SkinPackFormatDocTest` o amarra ao parser e o build o **embute** na ferramenta `skin_get_format` do agente (`SkinFormatAssetTest`, byte a byte). Documentar antes do código faria o documento mentir | editar já, com a nota "reservado" |
| A11 | Notificação **mínima**: canal de importância mínima, texto "KeyFlow ativo", sem ação, `VISIBILITY_SECRET` | É o menor que o `startForeground` aceita; some da tela bloqueada | importância baixa com a orbe pequena |
| A12 | Recusa na tela bloqueada = `Toast` + vibração; plano B = cadeado na orbe por 2 s | Um `Toast` pode não aparecer sobre o bloqueio; a notificação é a única superfície que sempre está ali | só vibração |
| A13 | A janela de contagem e o `overflow` são os da **orbe principal** | Uma "Tempo de reação" só; a notificação não ganha sliders próprios | janela própria |
| A14 | Prefixo `NTF` e a pasta `.specs/features/notification/` | Segue `flows`/`FLX` | — |

### Dúvidas para o Yan

- **Q1** — Com "copiar" ligado (padrão), a notificação **não pausa nem cancela** (a orbe principal faz isso por arrasto, que a notificação não tem). Para pausar/cancelar ali, desliga-se "copiar" e atribui-se "Pausar/retomar" e "Cancelar/desfazer" a 2 e 3 toques (A2). É isso, ou o padrão já deve vir com esses dois?
- **Q3** — "Copiar" leva **skin e ações** juntas (A3), ou prefere duas chaves?
- **Q4** — O **tempo no centro** da orbe da notificação (A6): fica, ou "só a orbe" é só a orbe, sem número?
- **Q5** — Um pacote **só de orbe** deve aparecer também na aba Notificação (A5), com o 1º quadro?
- **Q6** — **Ler a tela** e **Trocar o teclado** pela notificação dependem de a permissão de sobreposição estar concedida (L10) e do aparelho desbloqueado. Aceita que fiquem indisponíveis sem ela, com aviso?
- **Q7** — A recusa na tela bloqueada (A12): `Toast` + vibração basta, ou quer que a orbe vire um cadeado?
- **Q8** — Tocar no **corpo** da notificação abrir o KeyFlow (A8): quer?
- **Q9** — Se a prova da NTF-T1 mostrar que a orbe **não cabe nos 48 dp** em algum aparelho (Samsung, por exemplo), o D7 não tem plano B: a decisão volta a você. Quer já combinar um (ex.: orbe menor, 32 dp)?
- **Q10** — A **orbe do teclado** precisa de spec própria (o D1 a cria): quem escreve, e entra antes ou depois de Fluxos? Fluxos já deixa o gatilho dela no modelo (FLX-59).

---

## Requirement Traceability

| Requirement | Story | Task | Status |
| ----------- | ----- | ---- | ------ |
| NTF-01 | P1-A | NTF-T12, NTF-T13 | Pending |
| NTF-02 | P1-A | NTF-T12 | Pending |
| NTF-03 | P1-A | NTF-T13 | Pending |
| NTF-04 | P1-A | NTF-T13 | Pending |
| NTF-05 | P1-B | NTF-T1, NTF-T9 | Pending |
| NTF-06 | P1-B | NTF-T1, NTF-T9, NTF-T14 | Pending |
| NTF-07 | P1-B | NTF-T4, NTF-T9 | Pending |
| NTF-08 | P1-B | NTF-T2 | Pending |
| NTF-09 | P1-B | NTF-T4, NTF-T10 | Pending |
| NTF-10 | P1-B | NTF-T2, NTF-T3, NTF-T4, NTF-T10, NTF-T12 | Pending |
| NTF-11 | P1-B | NTF-T4, NTF-T10, NTF-T12, NTF-T20 | Pending |
| NTF-12 | P1-B | NTF-T2, NTF-T12 | Pending |
| NTF-13 | P1-B | NTF-T9 | Pending |
| NTF-14 | P1-B | NTF-T10 | Pending |
| NTF-15 | P1-C | NTF-T3, NTF-T13 | Pending |
| NTF-16 | P1-C | NTF-T5, NTF-T10, NTF-T14 | Pending |
| NTF-17 | P1-C | NTF-T9, NTF-T14 | Pending |
| NTF-18 | P1-D | NTF-T5, NTF-T11, NTF-T14 | Pending |
| NTF-19 | P1-D | NTF-T11, NTF-T13 | Pending |
| NTF-20 | P1-D | NTF-T9, NTF-T14 | Pending |
| NTF-21 | P1-D | NTF-T9, NTF-T11 | Pending |
| NTF-22 | P1-D | NTF-T11, NTF-T12 | Pending |
| NTF-23 | P1-D | NTF-T11, NTF-T13 | Pending |
| NTF-24 | P1-E | NTF-T7, NTF-T12 | Pending |
| NTF-25 | P1-E | NTF-T6 | Pending |
| NTF-26 | P1-E | NTF-T7 | Pending |
| NTF-27 | P1-E | NTF-T7 | Pending |
| NTF-28 | P1-E | NTF-T7, NTF-T11 | Pending |
| NTF-29 | P1-E | NTF-T6, NTF-T9 | Pending |
| NTF-30 | P1-E | NTF-T7 | Pending |
| NTF-31 | P1-F | NTF-T8 | Pending |
| NTF-32 | P1-F | NTF-T8 | Pending |
| NTF-33 | P1-F | NTF-T8 | Pending |
| NTF-34 | P1-F | NTF-T7, NTF-T8 | Pending |
| NTF-35 | P1-F | NTF-T8 | Pending |
| NTF-36 | P1-G | NTF-T3 | Pending |
| NTF-37 | P1-G | NTF-T3 | Pending |
| NTF-38 | P1-G | NTF-T3, NTF-T10 | Pending |
| NTF-39 | P1-H | NTF-T2, NTF-T3, NTF-T20 | Pending |
| NTF-40 | P2-I | NTF-T12, NTF-T13 (cada uma traz os seus textos) | Pending |
| NTF-41 | P2-I | NTF-T2, NTF-T4, NTF-T5 | Pending |
| NTF-42 | P2-I | NTF-T11 | Pending |
| NTF-43 | P2-I | NTF-T1, NTF-T14, NTF-T18 | Pending |
| NTF-44 | P2-I | NTF-T15 | Pending |
| NTF-45 | P2-I | NTF-T9, NTF-T12 | Pending |
| NTF-46 | P2-I | NTF-T16 | Pending |
| NTF-47 | P2-I | NTF-T19 | Pending |

**Coverage:** 47 requisitos, 47 mapeados em tasks, 0 sem task.
**Status values:** Pending → In Design → In Tasks → Implementing → Verified.

---

## Success Criteria

- [ ] **Altura**: no A32, no Tab S10 Lite e no Vaio a orbe aparece inteira (sem corte) na notificação recolhida e expandida, e o alvo de toque é de 48×48 dp.
- [ ] **Remapear sem gravação**: salvar 1 = Nenhuma, 2 = Ler a tela, 3 = Trocar teclado é aceito e persiste; substituir qualquer ação atribuída mostra seu nome/toque, cancelar não grava; copiar PTT com 1 = Nenhuma não inventa gravação.
- [ ] **Gravar pela notificação (padrão próprio/Alternar)**: 1 toque inicia, o tempo corre no centro da orbe, 1 toque envia e o texto é entregue como hoje; 2 toques com "Pausar/retomar" pausa e retoma.
- [ ] **Copiar**: com "copiar" ligado, trocar a skin ou o 2 toques da orbe principal muda a notificação na hora; a tela diz que segurar e arrastar são ignorados.
- [ ] **Tela bloqueada**: com a opção desligada, tocar a orbe com o aparelho bloqueado não grava e avisa (emulador por UiAutomator; no A32 pelo roteiro do Yan, com o PIN dele); ligada, controla; "Ler a tela" recusa bloqueado.
- [ ] **Dispensar** (Android 14+): o serviço segue, nenhuma configuração muda, a notificação volta numa gravação nova e ao abrir a aba; no Android 13 não sai do painel.
- [ ] **Skins**: Ateliê, Sistema, Aero, Glitch, Rose Gold, Ouvi e um pacote só de notificação vestem a notificação; sem o direito, a paga mostra o Ateliê; a soma de bitmaps fica ≤ 1 MB.
- [ ] **Claro/escuro** do sistema recolore a notificação ao vivo.
- [ ] Os JSON não contêm segredo nem texto ditado; goldens claro/escuro aprovados e os demais bit-idênticos; `TranslationsTest` e `ArchitectureRulesTest` verdes.
- [ ] Gate `full` verde; requisitos → Verified.
