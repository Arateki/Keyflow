# 2026-10-30 — Notificação, Fase 0 e Fase A: prova de altura, modelo, codec e portões (equipe/notificacao-a)

NTF-T1..T5 da feature `notification`. A orbe é a interface única nos três lugares; a notificação é o terceiro (`com.keyflow.orbe` e
`com.keyflow.notificacao`, JVM puro, conferido pelo `ArchitectureRulesTest`).

## NTF-T1 — Prova de altura e de toque (emulador medido; aparelhos do Yan pendentes)

**Resultado no emulador `keyflow_api34` (Android 14, 1080×2209, 420 dpi = 2,625; `NotificacaoAlturaSpikeTest`, 4 testes OK):
a orbe e os dois botões CABEM, sem corte. A feature não para.** Capturas recortadas em
`.specs/features/notification/provas/ntf-t1-emulador-api34/`.

| Medida (emulador) | Resultado |
| ----------------- | --------- |
| Orbe, `visibleBounds` | 126×126 px = **48×48 dp** inteiros, nas 4 variações, na gaveta recém-aberta e depois de puxar a linha |
| Círculo teal (40 dp = 105 px), quatro extremos na captura | 104×104 px (±1 px de antialias), largura = altura: **cabe** |
| Botões esquerdo e direito (`ImageButton` 48×48 dp, glifo vetorial 24 dp) | 126×126 px = **48×48 dp** cada, dentro da linha, **cabe** |
| Sobreposição orbe × botões | nenhuma, nas 4 variações |
| Linha da notificação (com o cabeçalho do sistema: ícone, "KeyFlow • agora") | **87,6 dp** inicial; **117,7 dp** depois de puxar (o sistema oferece o botão de expandir mesmo sem `BigContentView`) — o **conteúdo continua 48 dp e a orbe/botões idênticos** (recolhido = expandido no tamanho) |
| Orbe com o botão ausente `INVISIBLE` | x do centro = 535 px em sem-botão, só-direito e dois botões: **a orbe não anda**. Com `GONE` ela anda 63 px (24 dp) para a esquerda: **`INVISIBLE` é obrigatório** |
| Posição horizontal | a orbe fica no centro da **área de conteúdo**, não da linha: −5 px do centro da linha na gaveta inicial e **+47 px (18 dp)** depois de puxar (o cabeçalho expandido empurra o conteúdo). Não corta nem sobrepõe; só não esperar pixel-centro da linha inteira |
| Tela estreita (`wm size` para 320 dp) | linha de conteúdo 144 dp dentro de 320 dp; orbe e botões dentro da tela; 48×48 dp mantidos |
| Clique até o serviço (`PendingIntent.getService`) | orbe, esquerdo e direito **chegam**; 160 / 56 / 72 ms **incluindo** a injeção do UiAutomator (teto, não o custo real) |

**O que o emulador não mede (o Yan mede com `NotificacaoAlturaSpikeTest` nos aparelhos, sem `connectedAndroidTest`):** Vaio TL10
(Android 13), Tab S10 Lite (Android 16) e A32 (Android dele, `getprop ro.build.version.release`; com `--confirmo-a32`) — renderização do
fabricante (One UI/Positivo) da `DecoratedCustomViewStyle`, densidades 1,5×–3×, o recorte do cabeçalho em cada versão e a latência real
do clique. Nenhum dos três estava conectado ao servidor. O teste imprime `INSTRUMENTATION_STATUS: spike_NNN=…` (o relatório inteiro) e grava
`relatorio.txt` e as capturas em `externalFilesDir/spike-notificacao/`.

**Limites da prova:** (1) a notificação foi postada por `NotificationManager` do teste, não por serviço em primeiro plano (o layout é o
mesmo; o tempo de exibição do primeiro plano é a L12, outra task); (2) "recolhido" e "expandido" foram medidos como "gaveta recém-aberta" e
"depois de puxar a linha": com outra notificação por cima a orbe **não apareceu** na árvore de acessibilidade (a nossa ficou fora da
tela ou agrupada) — o Yan confere nos aparelhos como a notificação convive com outras; (3) glifo de pausa genérico, orbe teal desenhada à mão.

**Para a T9:** o `notif_orb.xml` do design §6 serve como está; manter os botões `INVISIBLE`; não prometer centro de pixel da linha.
**Para quem repetir a medição:** `UiDevice.openNotification()` + `By.desc(...)`, `takeScreenshot` e varredura de cor; o filtro de cor
(soma das diferenças RGB < 60) acha o círculo com ±1 px.
