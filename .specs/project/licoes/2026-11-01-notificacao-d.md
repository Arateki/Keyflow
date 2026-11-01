# 2026-11-01 — Painel da notificação e P3 do publicador (NTF-T12)

Branch `equipe/notificacao-d`, de `origin/main` com T1..T11 e D14. A aba continua "Em breve" até a T13; não há alterações de goldens.

## P3 do PR #97

O publicador não segura `trava` ao consultar a permissão nem ao chamar `notify`. A geração e a presença são verificadas depois da consulta de permissão, imediatamente antes da publicação; o retorno só confirma o retrato publicado se a geração continuar válida. A construção da mínima de `inicial` também fica fora da trava, para o primeiro plano não esperar um Binder do worker. Um Binder já iniciado não pode ser cancelado: as operações de presença/inicial não esperam essa chamada, e a contagem reflete as publicações que de fato completaram.

Erros do render são capturados como `Throwable`, com `CancellationException` relançada. `StackOverflowError`, `NoClassDefFoundError` e `OutOfMemoryError` caem na mínima; os testes verificam que o worker aceita um retrato posterior. Os 15 unitários incluem bloqueios controlados nas portas de permissão e publicação: uma segunda thread consegue dispensar e obter `inicial` sem esperar o bloqueio; dispensar durante a consulta de permissão impede o `notify` seguinte.

O teste de dispensar do serviço agora observa a ausência de pendência **e** o relógio passar de 300 ms depois do envio, antes de conferir o contador e as preferências. Assim cruza a janela de 250 ms por condição, sem `sleep` fixo, e não passa só porque o worker ainda não acordou.

## Painel

Estado, Aparência e Interações usam a paleta da aba. O chip de permissão é o de `ModeCards`, agora reutilizável com um modificador; o novo alvo tem 48 dp sem alterar o tamanho dos chips antigos. Interruptores têm a linha inteira como alvo e descrição acessível. O adaptador real coleta `configNotificacaoFlow` na Main e grava exclusivamente por `atualizarNotificacao`, sem regravar `Settings`.

`efetiva` e `desligarCopia` continuam a fonte da verdade: Alternar copia Gravar/enviar no primeiro toque; PTT copia `shortcut1Tap`, inclusive Nenhuma. Copiar bloqueia a edição de skins e ações; desligar congela os valores copiados como ponto de partida. Atualizações já confirmadas da origem chegam ao vivo sem diálogo. A confirmação compara as ações efetivas antes/depois, inclusive ao ligar copiar, nomeia toque/lugar/anterior/nova e cancelar não grava. Ao confirmar, os avisos são recalculados para não aceitar silenciosamente uma origem que tenha mudado enquanto o diálogo estava aberto.

A folha `ShortcutActionSheet` recebe um catálogo opcional, preservando a API e o desenho dos atalhos existentes. O catálogo da notificação tem só Nenhuma, Gravar/enviar, Retrair/expandir e Ler a tela; não inclui Trocar o teclado (D14), segurar, pausar ou cancelar. O aviso da sobreposição/desbloqueio acompanha Ler a tela na folha e na escolha atual. Zero toques de gravação só avisa; não bloqueia nem inventa ação.

`SkinPacksSection` ganhou `NOTIFICACAO`, miniatura por `PackPreview.notification`, importação/remoção e filtragem por `hasNotification`. Pacote só de orbe não entra na lista própria; copiar mantém o caminho de fallback da T7. Skins pagas continuam bloqueadas sem direito, e o callback de desbloqueio não muda a seleção. Nenhuma skin paga é liberada por este painel.

## Validação

Todos os comandos usaram `/srv/dev/ferramentas/pool/bin/pool` com `--destino servidor`:

- `compilar --max-workers=1 testDebugUnitTest lintDebug assembleDebugAndroidTest`: verde em 10 min 28 s; app **3.180/3.180**, termlib **3/3**, sem falhas/erros/ignorados; lint de ambos **0 erros e 0 avisos**. Também houve `assembleDebug assembleDebugAndroidTest` verde na implementação. Publicador **15/15** e traduções **5/5** estão incluídos no gate completo.
- `instrumentar com.keyflow.ui.config.NotificacaoPanelTest com.keyflow.ui.config.SkinPacksSectionTest com.keyflow.ui.config.ShortcutActionSheetTest`: **32/32** (14 + 16 + 2), execução Android em 211,321 s. Cobertura: permissão, JSON real sem regravar Settings, copiar Alternar/PTT, desligar/congelar e religar com confirmação, cancelamento, catálogo D14/aviso, zero gravação, destinos dos gestos, skins pagas e filtro dos pacotes. Os componentes compartilhados conservaram seus testes verdes.
- `instrumentar com.keyflow.overlay.OverlayServiceNotificacaoTest`: **14/14**, inclusive dispensar cruzando a janela de coalescência sem mudar o DataStore.

Mutação: troquei **somente** o primeiro `catch (e: Throwable)` do render por `catch (e: Exception)`. `compilar :app:testDebugUnitTest --tests 'com.keyflow.notificacao.PostadorDaNotificacaoTest.*outros erros*'` produziu **1 teste/1 falha**: `outros erros de render caem na minima e o worker continua vivo` não recebeu a notificação mínima após `StackOverflowError`. Restaurei o arquivo byte a byte (SHA-256 `5e528304be6e5fc890c9cfc516b4340887248a819deadb4877c5ad478d3b7956`) e rodei `compilar :app:testDebugUnitTest --tests com.keyflow.notificacao.PostadorDaNotificacaoTest`: **15/15**, verde. A mutação nunca foi commitada.

As rodadas aguardaram a trava compartilhada: o usuário informou ter parado um `ComputadorDialogosTest` travado; depois houve outro pedido do pool em execução. Espera de fila não foi contada como falha nem contornada. Logs, XML do gate completo, mutação e regressão estão em `.equipe/provas/notificacao-d/`, fora do git.

## Fora deste recorte

T13: prévia RemoteViews, tela bloqueada, ajustes do canal, reposição ao abrir e promoção da aba. T15: galeria e goldens; T18: validação física do Yan e as restrições de Ler a tela. Não houve intervenção em SystemUI/keyguard, desligamento manual do emulador, push nem arquivos `.equipe` commitados.
