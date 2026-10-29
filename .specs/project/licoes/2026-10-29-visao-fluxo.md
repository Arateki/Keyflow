# 2026-10-29 — Visão: consentimento, captura e seleção efêmeros (VC-T3/T4/T5)

O PR #63 entrega a etapa de captura, reutilizável por qualquer gatilho: `VisionFlowRequest` contém o estado de voz;
`VisionFlow<T>` recebe os adaptadores de consentimento, seleção, pausa, limpeza e entrega tipada. O serviço oferece
`ACTION_VISION`, além do atalho existente. O consumidor de OCR/modelo/clipboard será conectado na VC-T7/T8/T10/T16;
por enquanto o serviço entrega o bitmap ao ponto de integração e o recicla. Nenhum modelo é baixado neste fluxo.

## Consentimento pertence ao acionamento

Cada chamada abre `createScreenCaptureIntent`. Só depois do resultado concedido o serviço soma `mediaProjection` ao
FGS de microfone e chama `getMediaProjection`; a sessão registra `MediaProjection.Callback` antes de criar seu único
`VirtualDisplay`. Nenhum token fica salvo. Negação, revogação, cancelamento e erro convergem em `finish`: fecha display e
reader, encerra projeção, remove o type adicional, limpa `cacheDir/vision/` e devolve a UI ao estado de voz preservado.
`VisionConsentRequest` identifica os pedidos: uma resposta atrasada de um diálogo cancelado não pode autorizar a sessão
seguinte. Cancelar também fecha a Activity de consentimento; uma Activity criada depois do cancelamento verifica o id
antes de abrir qualquer diálogo. Os testes cobrem resposta duplicada, pedido concorrente e fechamento real da autorização
pelos controles de voz, sem usar Voltar para esconder um diálogo abandonado.

Fontes oficiais conferidas:
[MediaProjection](https://developer.android.com/media/grow/media-projection) e
[FGS mediaProjection](https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection).
Android 14+ exige autorização nova e limita cada instância a um `createVirtualDisplay`; reutilizar token não é otimização.
As chamadas novas têm guardas por SDK; o `minSdk` continua 24. A partir da API 34, a autorização usa
[`MediaProjectionConfig.createConfigForDefaultDisplay`](https://developer.android.com/reference/android/media/projection/MediaProjectionConfig):
a seleção tem coordenadas da tela viva e não pode ser aplicada à projeção de uma janela isolada. O consentimento continua
obrigatório em cada chamada; a configuração só define a superfície solicitada.

## Voz e tela têm ciclos de vida distintos

Gravando, o fluxo solicita PAUSE antes do consentimento, tanto no Toggle como no PTT. IDLE/PAUSED permanecem intactos;
SENDING/CANCELLING recusam a entrada. Os controles de voz encerram a etapa ativa antes de poder retomar/enviar o áudio.
O epílogo é idempotente, inclusive quando adaptadores ou consumidor lançam. O indicador usa tokens da paleta, encobrindo
somente o centro da orbe. Antes do frame, seletor e janelas próprias somem; a captura espera dois callbacks de composição,
sem `sleep`. O parcial é removido durante o frame e renderizado novamente no epílogo.

`ScreenRegion` e `RegionGeometry` não importam Android: quatro cantos, hit-test, limites e tamanho mínimo rodam na JVM.
A View converte para `Rect` só na confirmação. Resize invalida as coordenadas, encerra a sessão e oferece recomeçar com
novo consentimento. Preto em todas as amostras do recorte gera aviso e epílogo; a proteção `FLAG_SECURE` não é contornada.

## Por que os sete jobs falharam

O run `40836515251` falhou primeiro em `compileDebugAndroidTestKotlin`: a prova usava `By.textMatches`, que não existe
no UiAutomator do projeto. O job não publicou APKs, causando as seis falhas seguintes por artefato ausente. A correção é
[`By.text(Pattern)`](https://developer.android.com/reference/androidx/test/uiautomator/By), API documentada desde 2.2.0.
Não eram sete defeitos independentes. A compilação dos instrumentados passou a fazer parte da verificação local desta
retomada, junto dos unitários e do lint, sempre pelo pool sem escolher nó.

A prova opt-in `VisionCaptureDeviceTest#capturaRegiaoNoEmulador` usa o diálogo real, arrasta um handle e confere as
dimensões exatas do bitmap entregue. Observa `Service.foregroundServiceType`, exige o retorno ao microfone e abre uma
segunda autorização para provar revogação. Salva PNGs do seletor e do recorte no log da instrumentação (Base64), extraídos
para `.equipe/provas/`; esses arquivos locais não entram no git. Os goldens novos são `visao-selecao` e `visao-fases`, nos
dois temas. Nenhum golden anterior deve ser alterado.

A validação física no A32 é do Yan (regra de ouro 9); os resultados automatizados e a prova do emulador ficam no relatório
da equipe. As ações sobre a imagem continuam nas tasks seguintes, sem marcar seus requisitos como concluídos.

## Isolamento dos testes de serviço

A primeira rodada ampliada expôs dois erros do teste: procurar o seletor de compartilhamento antes de o diálogo estar
pronto podia aceitar a opção padrão de aplicativo único; e aguardar só o desaparecimento do diálogo não garantia o
callback de negação consumido. O teste espera o botão de autorização, escolhe tela inteira quando existe essa opção e
aguarda `VisionFlowState.NONE` para reentrar. A guarda do pedido não deve ser contornada com atraso fixo.

O run intermediário `40865745829` passou build e cinco shards, mas o shard 1 encontrou o teste de PTT falhando na segunda
autorização e o teste seguinte de parcial começando em PAUSED. O teardown novo primeiro cancela a etapa de Visão, depois
cancela/conclui o áudio de teste pelos intents públicos e só então para o serviço e restaura Settings. Isso verifica a
separação dos estados sem deixar uma gravação pausada para a próxima classe.

A segunda prova passou os seis testes de consentimento/pausa/View, mas mediu recorte com 612 px onde o gesto pedia
608 px: o último MOVE do swipe fica antes do ponto de soltura. O seletor atualiza o canto também no ACTION_UP; o teste
isolado agora usa MOVE e UP em pontos diferentes. A prova real exige o tamanho pedido, sem afrouxar a tolerância.
