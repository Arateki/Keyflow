# 2026-11-01 — Prévia real e abertura da aba Notificação (NTF-T13)

Branch `equipe/notificacao-e`, criada de `origin/equipe/notificacao-d` (T12/PR #99). A T13 liga somente Notificação; mantém o comportamento existente de Fluxos e o cartão de Computador. `docsSectionFor(NOTIFICACAO)` continua nulo até a T16.

## Prévia

`NotificacaoPrevia` usa `NotificacaoDaOrbe.galeria`, a mesma construção da linha enviada ao `notify`, com `AndroidView`/`FrameLayout` e tempo congelado em 12 segundos. O seletor simula Ociosa, Gravando, Pausada, Transcrevendo, Cancelando, Erro e Visão; usa os botões reais de cada estado, inclusive Encerrar na Visão. Não usa o estado real da gravação nem recebe texto ditado.

A skin vem de `efetiva`, do direito de skins e de `OrbeDePacote`, com o mesmo fallback/cache do provedor. Reage ao modo noturno do sistema (`LocalConfiguration`), não ao tema escolhido para o app, e às mudanças de pacote/idioma/densidade. O desenho, leitura do pacote e montagem da RemoteViews ficam no dispatcher de fundo; só a aplicação da View e as escritas de estado ficam na Main. Cancelamento é relançado; falha total da prévia mostra aviso traduzido.

A linha real contém PendingIntents: depois de inflar, a amostra remove os listeners de todas as Views, bloqueia foco nos descendentes e oferece uma descrição acessível da prévia no Compose. Assim tocar no exemplo não grava, pausa, descarta ou abre uma sessão.

## Tela bloqueada, canal e presença

O novo interruptor grava `permitirComTelaBloqueada` por `atualizarNotificacao`, preservando os demais campos. O serviço da T11 já observa esse JSON e re-posta; não foi criado outro caminho de atualização.

O botão de sistema usa `ACTION_CHANNEL_NOTIFICATION_SETTINGS` com pacote e `keyflow_overlay` no Android 8+. Se ainda não houve sessão, cria somente o canal silencioso com a mesma fábrica usada pelo serviço, sem tocar nas escolhas de um canal existente. No Android 7/7.1 (minSdk 24), sem canais, abre os detalhes do aplicativo.

O adaptador real observa `ON_RESUME` enquanto está na composição: entrar na aba ou voltar ao app chama `mostrarNotificacaoSeEmPrimeiroPlano`. A checagem de instância/primeiro plano fica no serviço; não há `startForegroundService` nem nova sessão para exibir a prévia. Sair da aba remove o observador. Abrir os ajustes no teste é interceptado por ContextWrapper, sem mudar janelas do sistema.

## Validação

Todos os comandos de Gradle/Android usaram `timeout 2400 /srv/dev/ferramentas/pool/bin/pool … --destino servidor`; as travas e a preparação ficaram com o pool.

- Gate completo: `compilar --max-workers=1 testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`, **verde em 10 min 35 s**. App **3.180/3.180** e termlib **3/3**, nenhuma falha/erro/ignorado. Lint de ambos: **0 erros e 0 avisos**. Traduções **5/5** e abas **6/6** incluídos.
- Android: `instrumentar com.keyflow.ui.config.ConfigScreenTest com.keyflow.ui.config.NotificacaoPanelTest com.keyflow.ui.config.NotificacaoServicoPanelTest com.keyflow.overlay.OverlayServiceNotificacaoTest`, **64/64** (28+19+3+14). Verifica a aba real, os sete estados e seus botões, cliques passivos, cronômetro congelado, mudança de skin/modo, bloqueio persistido/re-post com versão pública real, intent do canal e reposição somente com serviço existente.

Falhas medidas e corrigidas: ordenar as traduções pela chave portuguesa, não pela linha incluindo aspas; ao trocar `LocalContext` por um ContextWrapper de teste, fornecer explicitamente o `LocalActivityResultRegistryOwner` original (o seletor de arquivos precisa dele); o cronômetro nativo mostra **00:12**, e não **0:12**, expectativa corrigida após execução. Essas correções de fixture/formato não alteraram a produção. O modo noturno foi simulado por `LocalConfiguration`, sem mexer no SystemUI/keyguard.

Mutação: removi **somente** a guarda `instance?.isForeground == true` de `mostrarNotificacaoSeEmPrimeiroPlano`. `instrumentar com.keyflow.ui.config.NotificacaoServicoPanelTest#abrirSemServicoNaoEnviaIntentNemCriaSessao` produziu **1 teste/1 falha**, na asserção `abrir o painel enviou startService`. O ContextWrapper registra essa chamada sem de fato iniciar um serviço; assim a prova não cria uma gravação indevida. Restaurei `OverlayService.kt` byte a byte (SHA-256 `8b31f293c1299c085c1e4dd438be6f6aa9cc2486b7b6c89d44893024218de9c6`) e rodei novamente `NotificacaoServicoPanelTest`: **3/3**, verde. A mutação nunca entrou no git.

Provas em `.equipe/provas/notificacao-e/` (fora do git): XML/log do gate completo, logs das falhas medidas, Android final, mutação e regressão. Os instrumentados rodaram no emulador API 34; o fallback de ajustes das APIs 24–25 foi conferido por código/lint, sem prova física nessa versão.

## Goldens e limites

Mudança visual intencional nas rotas já existentes `config-notificacao` e `config-notificacao-dark` (o cartão Em breve dá lugar ao painel). Nenhuma imagem foi aprovada/alterada; a galeria/goldens novos são da T15. Provas em aparelhos, bloqueio real e restrições de Ler a tela seguem T14/T18; ajuda/Docs seguem T16. Não houve intervenção em SystemUI/keyguard, desligamento manual do emulador ou push.
