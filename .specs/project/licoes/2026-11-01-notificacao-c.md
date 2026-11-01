# 2026-11-01 — Notificação: sessão dispensada, primeiro plano e publicações coalescidas

NTF-T11, com a decisão D14 do Yan (31/10) registrada separadamente no spec, design e tasks.

## Presença e primeiro plano

`NotificacaoHost.visibilidade` guarda a presença só em memória. `ACTION_NOTIF_DISMISSED` aplica `Dispensou`;
pausa, retomada, envio, configuração e aparência não repõem a dispensada. `Necessaria` nas entradas de
`ensureForeground`, no início de uma gravação nova e da Visão, ou `AbaAberta`, repõe a sessão viva.
O painel dispõe de `mostrarNotificacaoSeEmPrimeiroPlano`: não inicia serviço. Intents antigas de mostrar
ou dispensar não deixam uma sessão ociosa viva. Encerrar o primeiro plano também encerra a presença.

O postador entrega o cache pronto ou a mínima imediatamente ao `startForeground`, inclusive com a
permissão negada. Enquanto a configuração ainda não foi lida, usa a mínima: não pode mostrar a orbe
por engano a quem já salvou `mostrar=false`. Os canais existentes preservam as escolhas do usuário;
novos canais usam LOW (`keyflow_overlay`) ou MIN (`keyflow_overlay_min`), sem som nem vibração.

## Publicação e aparência

`PostadorDaNotificacao<E, N>` tem portas testáveis na JVM, canal conflado e janela de 250 ms. O último
retrato visível vence; pedidos iguais não publicam de novo. Bitmaps, pacotes e RemoteViews são montados
em `Dispatchers.Default`. Cada resultado confere sua geração antes de publicar: um desenho atrasado não
pode ressuscitar uma notificação dispensada, repor um estado antigo ou publicar após o fim da sessão.
O cache aquece no `onCreate` e acompanha skin, direito e versão dos pacotes. O provedor faz skin → Ateliê;
a falha total, inclusive memória, usa a mínima. Falhas de `notify` e permissão negada não derrubam o serviço.

O modo noturno vem de `Configuration.uiMode`, com re-post em `onConfigurationChanged` antes do retorno
antecipado da Visão. `Settings.themeDark` continua apenas nos banners do overlay. A mínima não muda
com pausa, skin ou modo noturno, por isso esses eventos não geram publicações iguais. O cronômetro usa
o acumulado e o início do segmento publicados pelo coordenador, mesmo quando uma pausa não foi desenhada.

## L11 conferida no AOSP

Na [fonte do AOSP Android 14, tag android-14.0.0_r1](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/services/core/java/com/android/server/notification/NotificationManagerService.java),
`DEFAULT_MAX_NOTIFICATION_ENQUEUE_RATE = 5f` (linha 371) é o padrão por app; `Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE`
pode substituí-lo (2115–2117). Atualizações acima da taxa estimada são descartadas em `checkDisqualifyingFeatures`
(6720–6734), com exceções como progresso concluído/autogrupo. O limite de quantidade de FGS é outro teste;
não significa isenção dessa taxa. Nossa janela permite no máximo quatro `notify` por segundo desta notificação,
sem tratar o padrão do sistema como contrato imutável nem prometer quota para outras notificações do app.

## D14

O catálogo da notificação não tem mais Trocar o teclado. O id legado `switch_ime` vira Nenhuma ao ler e
`none` na próxima gravação; a cópia de `OrbShortcutAction.SWITCH_IME` também vira Nenhuma nos três toques.
A orbe flutuante e o seletor oferecido pelo sistema continuam com seu catálogo próprio. Ler a tela permanece
com sobreposição e aparelho desbloqueado, com aviso se faltar um deles; só a busca de acesso melhor a ela segue aberta.

## SPEC_DEVIATION medida: importância do canal de primeiro plano

O primeiro gate Android passou 28 de 29 casos. A única falha esperava MIN (1), mas o canal retornou LOW (2).
O mesmo `NotificationManagerService` do AOSP 14, linhas 6277–6293, promove MIN/NONE a LOW no primeiro FGS
ou quando a importância não está travada pelo usuário. Portanto o app **solicita MIN**, mantém o canal novo
silencioso e respeita a promoção do sistema; não promete preservar MIN após `startForeground`.
O teste confere o modelo de canal criado pelo app e, separadamente, aceita a importância promovida observada.

## Fechamento

- Gate final, sempre no servidor: `pool compilar --destino servidor testDebugUnitTest lintDebug assembleDebugAndroidTest`,
  3.088 de 3.088 unitários (119 da notificação), zero falhas/ignorados e lint sem erros; build em 8 min 31 s.
- `pool instrumentar --destino servidor com.keyflow.overlay.OverlayServiceNotificacaoTest com.keyflow.overlay.OverlayServiceStopTest com.keyflow.notificacao.NotificacaoDaOrbeTest`:
  29 de 29 (14 + 2 + 13), com SystemUI estável validado pelo pool. Sem intervenção no SystemUI/keyguard ou desligamento.
- Mutação de coalescência: `delay(0)` faz falhar dois dos 11 unitários do postador. Mutação de dispensar:
  escrever `mostrar=false` no DataStore faz falhar a comparação real das preferências (1 de 1, falha esperada).
  Ambas foram revertidas antes da rodada verde final; provas em `.equipe/provas/notificacao-c/` (fora do git).
- A rodada Android também fecha as pendências de prontidão do serviço e contraste com alfa, antes bloqueadas por ANR.
- Fora: painel/aba (T12/T13), gesto real de dispensar e fabricantes/Android anteriores (T14), goldens/UAT posteriores.
