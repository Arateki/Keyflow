# Fluxos (aba Fluxos) — Specification

> **Encadear as capacidades do KeyFlow em fluxos que a pessoa dispara com uma interação da orbe — a flutuante, a do teclado ou a da
> notificação — ou com um botão do teclado.** A aba
> Fluxos vira uma **lista de cards**; tocar num card abre um **modal** com os modelos de cada etapa e os atalhos ligados.
> A pessoa cria fluxos novos encaixando **etapas** (ditar, capturar a tela, ler a imagem, perguntar, falar, inserir, agente)
> numa **lista vertical** reordenável, edita e exclui qualquer um. Três fluxos padrão vêm prontos, **sem atalho**, e o botão
> "Restaurar fluxos padrão" os recria sem mexer no que a pessoa fez. Cada etapa reusa o motor que a aba da capacidade já
> tem (Transcrição, Visão, Raciocínio, Voz, Agente): o fluxo só escolhe o modelo e liga a saída de uma na entrada da outra.
> **Status**: rascunho para o Yan revisar (2026-10-29). **Fora da v1** (só `main`). Decisões do Yan de 29/10 em "Decisões" (a **D9**, do fim do dia,
> troca o modelo de gatilhos: as superfícies passam a ser as interações da orbe em **três lugares**; a D3 foi substituída).
> Levantamento de origem: relatório da equipe `fluxos-levantamento.md` (fora do git; código com arquivo:linha, referências de produto e proposta).

## Problem Statement

A aba Fluxos (`ConfigTab.FLUXOS`, grupo Capacidades) mostra o cartão **Em breve** (`ConfigScreen.kt:512-514`). O painel
desenhado, `FluxosPanel.kt` (207 linhas), não é chamado por ninguém: três cards fixos sem toque, o "+ Novo fluxo" com
`onClick` vazio, três gatilhos que são só texto e a nota de que o editor "virá depois". `settings` e `onSettingsChange`
nem são lidos. O terceiro card promete SSH, que não tem motor (`computer-terminal/ideias.md`). Não existe editar, excluir
nem restaurar.

As capacidades existem, mas cada uma termina na própria tela: o ditado entrega sempre no campo
(`OverlayCoordinator.deliver`, `:600`, via `KeyFlowController.insertOrHold`, `:124`); a Visão devolve só um `Bitmap`
(`VisionFlow<Bitmap>.onResult`); o `SpeechEngine` só toca no Testar; o agente só conversa na aba KeyFlow. Os atalhos da
orbe são um enum fechado (`OrbShortcutAction`, `Models.kt:48`) e a barra do teclado só tem botões de mic, config, colar, tecla,
alavanca e grade (`ShortcutModels.kt`). Nada liga a saída de uma capacidade na entrada de outra, e nenhum gatilho aponta para
"uma sequência que a pessoa montou".

## Goals

- [ ] Aba com **lista de cards** de fluxos; tocar abre o **modal** (modelos por etapa, etapas, atalhos); "+" no fim cria; excluir qualquer um.
- [ ] **Editor em lista vertical**: encaixar etapas por tipo (entrada/saída), reordenar arrastando (com alternativa por botão) e remover.
- [ ] **Executor** de fluxo: etapas em sequência, saída → entrada, uma execução por vez, cancelável, que **para e avisa** ao falhar.
- [ ] **Três fluxos padrão** (Ouvir e responder · Ler a tela e colar · Pedir ao agente e colar) e **Restaurar fluxos padrão** sem atalhos.
- [ ] **Gatilhos**: 2 e 3 toques da orbe — **flutuante**, **do teclado** e **da notificação** (D9) — e botões da barra do teclado disparam um fluxo; um atalho dispara no máximo um fluxo; ligar um gesto **substitui** a ação dele, com aviso do que era antes. O modelo já traz os três lugares; o editor entrega a orbe flutuante e o botão da barra, e a orbe da notificação e a do teclado entram nas tasks dependentes (FLX-T28, FLX-T29).
- [ ] Cada etapa usa o **modelo da aba** quando a pessoa não escolheu outro; o fluxo **não guarda chave**, endpoint nem token.
- [ ] Persistência em dois JSON no DataStore `keyflow_settings` (padrão `bars_config_json`), tolerante a dado ruim.
- [ ] Texto novo em `tr("…")` (PT/EN/ES), goldens claro/escuro das telas novas, sem dependência nova de licença não permissiva.

## Out of Scope (V1)

| Fora | Razão |
| ---- | ----- |
| Editor de grafo / nós e conexões | Decisão do Yan (29/10): a V1 é **lista vertical** de etapas. O modelo (lista de etapas tipadas) não impede um grafo depois |
| Variáveis nomeadas, mais de uma entrada por etapa, ramos (se/senão), repetição | Lista linear: a saída de uma etapa é a entrada da seguinte. Atalhos/Tasker/Zapier fazem o mesmo na forma simples |
| Orbe da notificação e orbe do teclado como gatilho **na entrega do editor** (FLX-T1..T27) | D9 (29/10) os inclui no modelo, mas a notificação é a feature `notification` (spec própria) e a orbe do teclado tem spec própria em outra frente (em andamento). O modelo, o codec e o roteador já tratam os três lugares (FLX-57); o grupo de atalhos de cada lugar só aparece quando o lugar existe, nas tasks FLX-T28 (notificação) e FLX-T29 (teclado) |
| Etapa Computador / SSH | Sem motor; a aba Computador segue "Em breve". O catálogo de etapas é `sealed`: o caso entra depois sem mexer no executor |
| Outros gatilhos (segurar, arrastar, 1 toque, comando do teclado, evento do sistema, agendamento) | O arrastar para cima **pausa** a gravação (`orb-gestures`, DRAG); 1 toque é gesto nativo do modo na orbe flutuante (na **notificação** ele é remapeável, NTF-10, e por isso também liga fluxo). A orbe da notificação **só tem toques** (segurar e arrastar não existem numa notificação; os arrastos viram botões). Só 2 e 3 toques em cada lugar da orbe (mais o 1 toque da notificação) e botão da barra |
| Botão "Rodar agora" / "Testar fluxo" no modal | Capturar e Inserir dependem do app que está na frente; fica para depois (dúvida Q4) |
| Importar, exportar ou compartilhar fluxos; galeria de modelos | Pós-validação de uso real |
| Chave de API, endpoint, header ou token dentro do fluxo | Decisão do Yan: o fluxo guarda só **qual** modelo; a credencial é a do cofre/aba |
| Baixar modelo pelo fluxo | O download é só pelas abas (mesma regra do MDL-02 da Visão). Fluxo com modelo ausente para e avisa (D6) |
| Retomar um fluxo depois de o processo morrer; histórico de execuções; guardar entradas/saídas intermediárias | O fluxo vive em memória; áudio, imagem e texto intermediários não vão a disco |
| "Melhor medido neste aparelho" nos cards (BFL-05 da bancada) | Outra feature; esta deixa o card com espaço para uma linha somente leitura por etapa |
| Restaurar **um** fluxo padrão isolado | Só o "Restaurar fluxos padrão" global (Yan); o modal não tem "restaurar este" |
| Biblioteca nova de grafo/arrastar | O reordenar usa o padrão já existente no `BarsEditor` (`detectDragGesturesAfterLongPress`); nenhuma dependência nova |

---

## Decisões do Yan (2026-10-29)

| # | Decisão | Onde vale |
| - | ------- | --------- |
| D1 | A V1 do editor é **lista vertical** de etapas (reordenar arrastando), não grafo | FLX-11..FLX-16 |
| D2 | **2 toques, 3 toques** da orbe e **botões da barra** podem disparar fluxos; a escolha é feita **no modal do fluxo**; ligar um fluxo a um gesto **substitui** a ação atual dele, **com aviso do que era antes** | FLX-33..FLX-38 |
| D3 | ~~**Notificação** como gatilho: depois (levantamento próprio); a superfície fica prevista no modelo, fora da V1~~ — **substituída pela D9** (29/10, fim do dia) | — |
| D4 | Etapa **sem modelo escolhido** usa o modelo da aba correspondente (Raciocínio, Visão, Voz, Transcrição); o fluxo **não guarda chave de API** | FLX-08, FLX-26, FLX-46 |
| D5 | **Agente** dentro do fluxo **pede confirmação das ferramentas como hoje** | FLX-25 |
| D6 | **Falha numa etapa** (rede, modelo ausente): o fluxo **para e avisa**; nunca segue com resultado parcial | FLX-27, FLX-28 |
| D7 | **Um atalho dispara no máximo um fluxo** (um fluxo pode ter vários atalhos) | FLX-33, FLX-37 |
| D8 | Lista de cards; tocar abre modal (modelos de cada etapa + atalhos); criar de forma intuitiva encaixando etapas e modelos; editar e excluir **qualquer** fluxo; **3 padrão** de exemplo; "+" no fim; "Restaurar fluxos padrão" recria os apagados e reescreve os editados, **sem atalhos** (nunca religa nem rouba gatilho; não mexe nos gatilhos ligados; fluxos criados pela pessoa não são tocados) | FLX-01..FLX-10, FLX-40..FLX-44 |
| D9 | *(29/10, depois da D1..D8; substitui a D3)* **A orbe é a interface única** e as **superfícies de gatilho passam a ser as interações da orbe em três lugares**: **orbe flutuante**, **orbe do teclado** e **orbe da notificação** — a da notificação só tem 1, 2 e 3 toques (segurar e arrastar não existem numa notificação; spec `notification`). Os **botões da barra continuam** como gatilho. Teclado e notificação têm a opção "copiar a configuração da orbe principal" (os gatilhos também são copiados) | FLX-33, FLX-34, FLX-57..FLX-60 |

---

## User Stories & Acceptance Criteria

### P1-A: Lista de fluxos na aba (`LST`) ⭐ MVP

**Como** usuário, **quero** ver meus fluxos como cards e abrir qualquer um para configurar.

1. **FLX-01**: a aba Fluxos SHALL mostrar o `FluxosPanel` novo no lugar do cartão "Em breve", com **um card por fluxo**, na ordem da lista gravada. Cada card SHALL mostrar: o **nome**; o **resumo das etapas** em texto ("ditar → perguntar → falar"); o **selo de privacidade** (FLX-30); os **atalhos ligados** como chips ("2 toques", "Botão do teclado") ou o texto "Sem atalho"; e o estado **Incompleto** quando o fluxo não é válido (FLX-15). O card do fluxo em execução mostra "Em execução · <etapa>". Os textos fixos antigos ("Ativo", "Configurado", "Instrução de Voz → Computador (SSH)", "Gatilhos e atalhos", "Orquestração") SHALL sair.
2. **FLX-02**: tocar num card SHALL abrir o modal do fluxo (FLX-06). O card inteiro é o alvo do toque (≥ 48 dp de altura).
3. **FLX-03**: ao fim da lista, o botão **"+"** ("Novo fluxo") SHALL criar um fluxo vazio chamado "Novo fluxo" (com sufixo " 2", " 3"… se o nome existir) e abrir o modal dele. Com **20 fluxos** o botão fica desligado, com o motivo visível ("Limite de 20 fluxos"). Abaixo do "+", o botão **"Restaurar fluxos padrão"** (FLX-41) fica sempre visível.
4. **FLX-04**: com a lista vazia (a pessoa excluiu tudo), a aba SHALL mostrar um estado vazio com texto curto, o "+" e o "Restaurar fluxos padrão".
5. **FLX-05**: a lista SHALL ser uma coluna rolável com `verticalScrollbar` e o tema/paleta da aba (claro e escuro), sem rolagem horizontal em 360 dp de largura. Texto novo passa por `tr("…")`.

**Independent Test**: primeira abertura → 3 cards padrão, todos "Sem atalho"; tocar abre o modal; "+" cria "Novo fluxo" e abre; com 20 fluxos o "+" não cria e diz por quê.

---

### P1-B: Modal do fluxo (`MOD`) ⭐ MVP

**Como** usuário, **quero** abrir um fluxo e ajustar o que ele faz, com quais modelos e por qual atalho.

1. **FLX-06**: o modal (padrão `ConfigModal`) SHALL ter: **nome** editável (1 a 40 caracteres; vazio ao sair volta ao nome anterior), a lista de **etapas** (P1-C), a seção **Atalhos** (P1-E) e **Excluir fluxo**. As alterações são **gravadas na hora** (sem "Salvar"); o card atrás reflete. Fechar (✕ ou fora) nunca descarta o que já foi feito.
2. **FLX-07**: cada etapa no modal SHALL mostrar: ícone e nome ("Perguntar"), o tipo "texto → texto", o seletor **Modelo** quando a etapa usa modelo (padrão **"Da aba · <modelo atual da aba>"**) e o campo próprio da etapa, quando houver (**instrução** da Visão; **instrução de sistema** do Perguntar; máx. 500 caracteres cada). A etapa Agente mostra a nota "Com ferramentas só com chave de API da Anthropic, OpenAI ou Google; senão responde só com texto" quando a origem de Raciocínio não permitir ferramentas.
3. **FLX-08**: o seletor de modelo de uma etapa SHALL listar: **"Da aba"** (padrão) e os modelos **utilizáveis agora** da capacidade da etapa — locais instalados e, por provedor conectado, os modelos que a aba correspondente oferece (Visão: só modelos com visão; Voz: as vozes/modelos da aba). Cada opção traz o selo **No aparelho** ou **Nuvem · <provedor>**. Escolher grava um `ModeloRef` (FLX-45); "Da aba" grava `null`. O seletor **não baixa** nada e **não pede** chave: sem provedor conectado, ele só diz "Conecte na aba <X>" com atalho para ela.
4. **FLX-09**: um `ModeloRef` gravado que **deixou de ser utilizável** (modelo apagado, provedor desconectado) SHALL aparecer no modal como **"Indisponível"** (chip de aviso) e o fluxo marca a etapa; o app **não troca sozinho** por "Da aba" nem por outro modelo. O fluxo roda e falha na etapa (FLX-27) até a pessoa corrigir.
5. **FLX-10**: **Excluir fluxo** SHALL pedir confirmação ("Excluir “<nome>”? Os atalhos e os botões do teclado ligados a ele também saem."), funcionar para **qualquer** fluxo (padrão ou criado) e, ao confirmar, remover o fluxo, **todos os seus gatilhos** e os **botões de barra** criados para ele (FLX-36). Excluir um fluxo em execução o cancela primeiro.

**Independent Test**: abrir "Ouvir e responder" → trocar o modelo do Perguntar → fechar → card e DataStore refletem; apagar o modelo local escolhido → etapa "Indisponível"; excluir → some da lista e dos atalhos.

---

### P1-C: Editor de etapas em lista vertical (`EDT`) ⭐ MVP

**Como** usuário, **quero** montar um fluxo encaixando ações, sem escrever nada.

1. **FLX-11**: o botão **"+ Etapa"** SHALL abrir um seletor agrupado em **Entrada** (Ditar, Capturar), **Processar** (Visão, Perguntar, Agente) e **Saída** (Falar, Inserir), com uma linha de descrição por etapa. Só as etapas **compatíveis** com a saída da última etapa ficam habilitadas; as demais aparecem esmaecidas com o motivo ("Precisa de um texto antes"). Fluxo vazio: só as etapas de entrada habilitadas e o texto-guia "Comece escolhendo como o fluxo recebe a informação".
2. **FLX-12**: as etapas SHALL poder ser **reordenadas arrastando** (segurar e arrastar a alça ≡, mesmo gesto do `BarsEditor`) **e por botões** "Mover para cima/baixo" por etapa (alternativa para acessibilidade), e **removidas** (✕). Toda mudança roda a validação (FLX-15) na hora.
3. **FLX-13**: o catálogo de etapas SHALL ser exatamente o da tabela "Etapas" (Modelo de dados): Ditar, Capturar, Visão, Perguntar, Falar, Inserir, Agente. Etapas de **entrada** (Ditar, Capturar) só valem na **primeira** posição.
4. **FLX-14**: limites: **1 a 8 etapas** por fluxo. Com 8, "+ Etapa" fica desligado com o motivo. A mesma etapa pode repetir (ex.: dois Perguntar).
5. **FLX-15**: um fluxo é **válido** quando: tem ≥ 1 etapa; a 1ª etapa tem entrada `NADA`; cada etapa seguinte aceita o tipo que a anterior produz; nenhuma etapa é `Desconhecida`. Fluxo **inválido** SHALL ser gravado mesmo assim, marcado **Incompleto** no card e no modal (a etapa do problema destacada com o motivo), e **não pode rodar nem ligar atalho** (FLX-38). Fluxo que **termina sem entregar** o resultado (última etapa Ditar, Capturar, Visão ou Perguntar) é válido, com o aviso "O resultado será descartado: termine com Falar, Inserir ou Agente".
6. **FLX-16**: um fluxo novo ("+") nasce vazio e **inválido**; o modal abre já com o seletor de etapa de entrada em destaque.

**Independent Test**: fluxo vazio → só Ditar e Capturar habilitadas; depois de Ditar, Perguntar/Falar/Inserir/Agente habilitadas (aceitam texto) e Visão esmaecida ("Precisa de uma imagem antes"); depois de Capturar, só Visão habilitada; remover a etapa do meio de "Capturar → Visão → Inserir" deixa "Capturar → Inserir" Incompleto, com o motivo na etapa Inserir.

---

### P1-D: Execução (`RUN`) ⭐ MVP

**Como** usuário, **quero** que o fluxo rode sozinho, etapa por etapa, e pare se algo der errado.

1. **FLX-17**: o **executor** SHALL rodar as etapas **em ordem**, passando a saída de cada uma como entrada da próxima (tipos da tabela "Etapas"), **uma execução por vez** no app. Disparar enquanto outro fluxo roda SHALL ser recusado com o aviso "Já há um fluxo em andamento" (nada é cancelado). O estado da execução (`Ocioso · Rodando(etapa) · Falhou · Cancelado`) SHALL ser publicado em `KeyFlowController` para a orbe, o teclado e o card.
2. **FLX-18**: **Ditar** SHALL usar o gravador, a orbe e a máquina de estados **existentes**, sem alterá-los: mesma permissão de microfone, mesmo limite de gravação, pausa, cancelar com desfazer e transcrição parcial. A diferença: o texto transcrito **não é inserido** — vai à próxima etapa. A gravação começa como o mic do teclado (toque inicia, toque na orbe/mic envia), **qualquer que seja o modo da orbe** (Toggle ou Push-to-talk). O fluxo só inicia Ditar com a gravação **ociosa**; senão o aviso "Termine a gravação atual antes de rodar o fluxo". Cancelar a gravação encerra o fluxo como **Cancelado** quando o cancelamento se conclui (desfazer dentro da janela mantém o fluxo esperando). Transcrição vazia ou erro → FLX-27.
3. **FLX-19**: **Capturar** SHALL usar o `VisionFlow<Bitmap>` existente (consentimento da tela **a cada acionamento**, seletor de área, captura de 1 frame). O consentimento negado/cancelado ou o ✕ do seletor encerra o fluxo como **Cancelado** (não é erro). O `Bitmap` pertence ao fluxo, é **reciclado** ao fim (qualquer fim) e nunca vai a disco. Capturar respeita as guardas do `VisionFlow` (SENDING/CANCELLING ignoram; RECORDING pausa e restaura).
4. **FLX-20**: **Visão** SHALL ler a imagem com o motor da Visão (PaddleOCR local, Gemma 4 local, provedor ou Custom): instrução vazia = "ler o texto" (`VisionModelEngine.READ_INSTRUCTION`); preenchida = a instrução da etapa. Com um modelo que só lê texto (PaddleOCR) a instrução é ignorada e o modal diz isso. Resultado vazio SHALL parar o fluxo ("Nenhum texto encontrado").
5. **FLX-21**: **Perguntar** SHALL chamar o `ReasoningEngine` com o texto de entrada (e o sistema da etapa, se houver) e entregar a resposta **inteira** só depois do `Done`; `Error` ou resposta em branco para o fluxo; um trecho parcial **nunca** segue adiante.
6. **FLX-22**: **Falar** SHALL falar o texto de entrada com o `SpeechEngine` da Voz e **devolver o mesmo texto** (passagem), para a etapa seguinte poder usá-lo. Texto longo SHALL ser dividido em trechos por frase (≤ 800 caracteres cada) e falado em sequência; cancelar corta o som na hora. Reprodução em `AudioTrack` (o mesmo player do Testar da aba Voz).
7. **FLX-23**: **Inserir** SHALL entregar o texto com `KeyFlowController.insertOrHold` (campo focado → cola; senão guarda para colar ao focar ou fica na área de transferência — as regras do auto-colar de hoje) e **devolver o mesmo texto**. Quando `insertOrHold` devolve `Held` o texto vai também para a área de transferência, como o ditado faz.
8. **FLX-24**: **Agente** SHALL enviar o texto de entrada ao agente do app (`AgentController.send`, a mesma conversa e o mesmo registro de ações da aba KeyFlow) e entregar a **última resposta do assistente** quando a execução terminar. Se o agente já está ocupado (`isBusy`), a etapa falha com "O agente está ocupado". Cancelar o fluxo cancela o agente.
9. **FLX-25**: as **ferramentas do agente** SHALL pedir confirmação **como hoje** (mesma política, mesmo painel de confirmação, mesmo "Permitir sempre" só para `WRITE`). Como o fluxo roda sobre qualquer app, o pedido SHALL aparecer numa **janela translúcida do KeyFlow** por cima do app em uso, com o `AgentConfirmationPanel` existente; sair dela sem responder **nega** (falha fechada) e cancela a etapa. Sem a janela visível nenhuma ferramenta que peça confirmação roda.
10. **FLX-26**: o **modelo de cada etapa** SHALL ser resolvido **no início da etapa**, de um instantâneo das `Settings` do momento: `ModeloRef` nulo → o que a aba tem agora; `ModeloRef` preenchido → o instantâneo com **só** a origem/modelo daquela etapa trocados, **em memória** (as `Settings` gravadas não mudam). A credencial vem do cofre na hora da chamada. O fluxo **nunca** troca de origem por conta própria (sem fallback silencioso entre local e nuvem).
11. **FLX-27**: **falha numa etapa** (modelo ausente ou não selecionado, provedor desconectado, rede, tempo esgotado, autenticação, limite de uso, memória, motor, resultado vazio) SHALL **parar o fluxo**: nenhuma etapa seguinte roda e nada mais é inserido ou falado. O aviso SHALL dizer **qual etapa** parou e **por quê** em linguagem simples ("O fluxo parou em “Perguntar”: sem conexão de rede.") e, quando a causa se resolve numa aba, levar a ela (tocar no aviso abre a aba da etapa). As causas e mensagens estão na tabela "Erros". O que etapas **anteriores** já fizeram (ex.: Inserir no meio) não é desfeito e o aviso não promete o contrário.
12. **FLX-28**: nenhum erro de etapa SHALL derrubar o app: `OutOfMemoryError`, exceção do motor e processo `:reasoning`/`:vision`/`:speech` morto viram `Falhou` com a causa `MEMORIA`/`MOTOR`. O executor libera imagem, player e gravação em **todo** fim (sucesso, falha, cancelamento).
13. **FLX-29**: **cancelar**. Fora da gravação do Ditar, **tocar na orbe** (ou no botão do fluxo no teclado, ou no card em execução) cancela o fluxo: interrompe a geração/chamada em curso, corta o áudio, descarta a captura e devolve a orbe ao estado em que estava. **Durante a gravação do Ditar** o toque na orbe é o de sempre (envia o áudio, FLX-18); ali, cancelar é pelo gesto de cancelar da gravação (arrastar para o lado, com a janela de desfazer), pelo mic do teclado ou pela notificação — e, concluído o cancelamento, o fluxo termina `Cancelado`. Em qualquer caminho o estado final é `Cancelado`, sem aviso de falha.
14. **FLX-30**: **privacidade e transparência**. O card e o modal SHALL mostrar, por etapa e para o fluxo todo, onde roda: **No aparelho** (origem Local/Sistema, Capturar, Inserir) ou **Nuvem · <provedor>** (o que sai: áudio na Transcrição por nuvem, texto no Raciocínio/Agente/Voz por nuvem, imagem na Visão por nuvem). O selo é calculado com o modelo **efetivo** (o da etapa ou o da aba agora). Fluxo 100% local: "100% no aparelho". O fluxo **não grava** áudio, imagem nem texto intermediário em disco; log e diagnóstico não contêm o conteúdo das etapas.
15. **FLX-31**: tempos máximos por etapa (o fluxo falha com `TEMPO`, não trava): Perguntar e Visão 180 s; Falar 10 min; Agente 10 min (inclui esperar confirmação); Ditar vale o limite de gravação e o tempo de transcrição já existentes. Os valores são constantes nomeadas e testadas com relógio virtual.
16. **FLX-32**: enquanto um fluxo roda, o `OverlayService` SHALL permanecer em primeiro plano (a regra de encerrar ocioso no modo só-teclado, `maybeStopWhenIdle`, não vale durante o fluxo) e a orbe SHALL mostrar o **glifo da etapa em curso** (microfone, olho, balão, alto-falante, robô…). Sem orbe (modo só-teclado), o botão de fluxo da barra mostra "em execução" e erros saem por `Toast`.

**Independent Test**: com fakes — "Ditar → Perguntar → Falar": cada porta recebe o que a anterior produziu; uma falha no Perguntar impede o Falar; cancelar no Falar corta o áudio; dois disparos seguidos: o segundo é recusado; modelo ausente na etapa 2 não baixa nada e o aviso leva à aba Raciocínio.

---

### P1-E: Gatilhos — gestos da orbe e botões do teclado (`TRG`) ⭐ MVP

**Como** usuário, **quero** disparar um fluxo com 2 toques, 3 toques da orbe (flutuante, do teclado ou da notificação) ou um botão do teclado, escolhendo isso no próprio fluxo.

1. **FLX-33**: o **gatilho** SHALL ligar uma **superfície** a **um fluxo**: `ORBE(lugar, 2)`, `ORBE(lugar, 3)` — com `lugar ∈ {FLUTUANTE, TECLADO, NOTIFICACAO}` (D9) —, `ORBE(NOTIFICACAO, 1)` (na notificação o toque simples é remapeável, NTF-10) e `BARRA(botaoId)`. Cada superfície aponta para **no máximo um** fluxo (D7); um fluxo pode ter **vários** gatilhos. O mesmo gesto em lugares diferentes são superfícies **diferentes** (`ORBE(FLUTUANTE, 2)` e `ORBE(NOTIFICACAO, 2)` podem apontar para fluxos distintos). As superfícies de `TECLADO` e `NOTIFICACAO` SHALL ser lidas e regravadas sem perda desde a V1; exibi-las e dispará-las é do FLX-57..FLX-59.
2. **FLX-34**: `OverlayService.handleOrbShortcut(count)` (orbe **flutuante**) SHALL consultar os gatilhos **antes** da ação do `Settings`: se `ORBE(FLUTUANTE, count efetivo)` aponta para um fluxo válido, roda o fluxo e **não** executa a ação; senão segue como hoje. O **overflow** (mais de 3 toques, política `NEAREST_DOWN`) SHALL contar um gesto ligado a fluxo como "ativo", como conta uma ação diferente de `NONE`; com `CANCEL` nada acontece, como hoje. 1 toque não é gatilho na orbe flutuante (é o gesto nativo do modo); só na notificação, onde é remapeável. O **mesmo roteador puro** serve os outros lugares (FLX-58, FLX-59), com a política de overflow (`Settings.shortcutOverflow`) da orbe principal.
3. **FLX-35**: ligar um fluxo a um gesto **substitui a ação atual dele**. A tela SHALL mostrar, **antes de confirmar**, o aviso com o que era antes ("Isto substitui “Retrair / expandir teclado” neste gesto.") ou, se o gesto está livre ("Nenhuma"), "Este gesto não tinha ação." Com mais de um lugar de orbe disponível o aviso nomeia o lugar ("… neste gesto da orbe da notificação."). A ação em `Settings.shortcut2Tap/3Tap` (orbe flutuante) ou a ação própria do lugar (a da notificação mora na config da aba Notificação) **não é alterada**: o gatilho a **sombreia**. Desligar o gatilho (ou excluir o fluxo) SHALL devolver a ação anterior sem a pessoa configurar nada. A aba Sobrepor → Atalhos SHALL mostrar o gesto ligado como "Fluxo: <nome>", tocar nele explica e leva à aba Fluxos, e o tour de gestos descreve o fluxo, não a ação sombreada (a orbe flutuante; a aba Notificação faz o mesmo para o lugar dela, NTF-T20).
4. **FLX-36**: **botão da barra**: um novo caso `BarButton.RunFlow(botaoId)` (id estável gerado na criação) SHALL ser desenhado pela `BarView` com o ícone de fluxo, disparar o fluxo pelo mesmo serviço do mic (`startForegroundService` com `ACTION_RUN_FLOW`) e aparecer **desabilitado** quando não há gatilho para o id ou o fluxo é inválido (mesma regra do `GridTrigger` órfão). Quem **cria** o botão é o modal do fluxo ("Adicionar à barra do teclado": acrescenta o botão ao fim da barra principal, nos dois modos se as barras não são espelhadas, e liga o gatilho); o editor de barras SHALL listar, mover e remover o botão, mas **não** o oferece no catálogo de adicionar. Remover o botão no editor de barras desliga o gatilho; excluir o fluxo remove o botão.
5. **FLX-37**: ligar uma superfície **já ocupada por outro fluxo** SHALL pedir confirmação ("Isto tira este atalho de “<outro fluxo>”.") e, ao confirmar, mover o gatilho; nunca fica um atalho em dois fluxos (D7). O **Restaurar** nunca faz isso (FLX-43).
6. **FLX-38**: **fluxo inválido** (Incompleto) não liga atalho (as linhas de atalho ficam desligadas, com o motivo). Um fluxo ligado que **fica** inválido deixa o atalho sem efeito: disparar mostra "Fluxo incompleto: abra a aba Fluxos" e **não** executa a ação sombreada.
7. **FLX-39**: **disparo**: antes de rodar, o serviço SHALL conferir (a) nenhum outro fluxo rodando (FLX-17); (b) para fluxos que começam com Ditar, a gravação ociosa (FLX-18); (c) para fluxos que começam com Capturar, as guardas do `VisionFlow` (FLX-19); (d) permissão de microfone para Ditar e de sobreposição/consentimento para Capturar, com os mesmos avisos que o app já usa. Falhou a conferência: aviso curto e nada acontece (a ação sombreada **não** roda no lugar).

**Independent Test**: 2 toques da orbe flutuante ligado a "Ouvir e responder" → o fluxo roda, o teclado **não** retrai; desligar → 2 toques volta a retrair; ligar a mesma superfície em outro fluxo pede confirmação; o botão do teclado criado pelo modal aparece desabilitado se o fluxo ficar incompleto; 4 toques com `NEAREST_DOWN` cai no 3 toques ligado a fluxo; o 2 toques da **notificação** ligado a um fluxo não muda o 2 toques da flutuante (superfícies diferentes).

---

### P1-F: Fluxos padrão e Restaurar (`PAD`) ⭐ MVP

**Como** usuário, **quero** exemplos prontos e poder voltar a eles sem perder o que montei.

1. **FLX-40**: na **primeira abertura** (nenhum `flows_json` gravado; um `flow_triggers_json` solto é ignorado) a lista SHALL ter os 3 fluxos padrão, **sem nenhum gatilho** — os 2 e 3 toques já têm dono de fábrica e a V1 não rouba nenhum:
   | Fluxo padrão | Etapas | Detalhe |
   | ------------ | ------ | ------- |
   | **Ouvir e responder** (`ouvir-responder`) | Ditar → Perguntar → Falar | modelos da aba; sistema do Perguntar: resposta curta e falada, sem listas nem formatação |
   | **Ler a tela e colar** (`ler-tela-colar`) | Capturar → Visão → Inserir | modelo da aba Visão; instrução vazia (ler o texto) |
   | **Pedir ao agente e colar** (`agente-colar`) | Ditar → Agente → Inserir | modelos da aba; ferramentas como na aba KeyFlow |
   Os três são fluxos como os outros: editáveis e excluíveis. O nome dos padrão **não renomeados** SHALL ser exibido traduzido (`tr`).
2. **FLX-41**: o botão **"Restaurar fluxos padrão"** SHALL abrir uma confirmação com **prévia** do que vai acontecer — "Será recriado: …", "Será reescrito: …", "Não muda: seus fluxos e todos os atalhos" — e, ao confirmar, aplicar o algoritmo do FLX-42. Sem nada a mudar, a confirmação diz "Os fluxos padrão já estão como no original" e não grava.
3. **FLX-42**: o **algoritmo de restaurar** é uma função pura `restaurar(config) → config`, aplicada **por fluxo padrão do catálogo** (identificado pelo `padrao` = `builtinId`, não pelo nome):
   - **não existe** na lista → é **recriado** com o conteúdo do catálogo (nome e etapas, modelos "da aba"), **sem gatilho**, com **id novo**, inserido logo depois do último padrão presente que o antecede no catálogo (ou no início, se nenhum o antecede);
   - **existe e difere** do catálogo (nome, etapas, modelos, instruções) → **nome e etapas são reescritos** com o catálogo; **id, posição e `padrao` não mudam** (portanto os gatilhos que apontam para ele continuam apontando);
   - **existe e é igual** → nada muda;
   - fluxo **sem** `padrao` (criado pela pessoa) → **nunca é lido nem alterado**, nem a posição relativa dele.
4. **FLX-43**: o restaurar **nunca** cria, move, apaga nem altera um gatilho, nem toca as `Settings`, a barra do teclado ou o cofre; **não rouba** atalho de ninguém (não liga nada). É **idempotente** (rodar duas vezes = rodar uma) e um fluxo restaurado que ficou válido volta a poder receber atalho como qualquer outro.
5. **FLX-44**: o `padrao` serve **só** ao restaurar; apagar o padrão remove o fluxo e os gatilhos dele e o restaurar o recria **sem** os gatilhos antigos (o id é novo, então nenhum gatilho órfão o reata). Renomear ou editar um padrão não tira dele o `padrao`.

**Independent Test**: apagar "Ler a tela e colar" (que tinha o botão do teclado) e editar "Ouvir e responder" (que tinha 2 toques) → Restaurar → o primeiro volta **sem** botão, o segundo volta ao original **com** o 2 toques; um fluxo da pessoa fica idêntico; rodar de novo não muda nada.

---

### P1-G: Dados e persistência (`DAT`) ⭐ MVP

1. **FLX-45**: os fluxos SHALL ser guardados em **dois JSON** no DataStore `keyflow_settings`, como `bars_config_json`: `flows_json` (lista de fluxos) e `flow_triggers_json` (lista de gatilhos). Esquema na seção "Modelo de dados". Cada JSON tem `"v": 1`. Um `ModeloRef` é `{ "source": <nome do enum da aba>, "model": <id ou null> }`.
2. **FLX-46**: o fluxo **nunca** contém segredo: nem chave de API, token, endpoint, header ou valor de header; origem **Custom** significa "o Custom que a aba tem" (endpoint e header ficam nas `Settings`/cofre). Um teste varre o JSON de um fluxo com todas as etapas e falha se achar campo desse tipo.
3. **FLX-47**: o codec SHALL ser **tolerante** como o das barras: chave ausente → fluxos padrão (FLX-40); lista presente e **vazia** → vazia; JSON malformado → fluxos padrão; fluxo individual malformado é descartado e os outros ficam; campo desconhecido é ignorado; **tipo de etapa desconhecido** vira `Desconhecida` (o fluxo fica Incompleto, a etapa pode ser removida); `nome` > 40, instrução > 500 caracteres e listas acima dos limites são cortados; ids repetidos viram um só. Nunca lança.
4. **FLX-48**: as escritas SHALL ser **atômicas e sobre o valor atual**: cada alteração (editar, excluir, restaurar, ligar atalho, criar/remover botão de barra) lê o valor gravado dentro de `dataStore.edit`, aplica uma função pura e grava os dois JSON juntos. Uma tela com estado velho (o modal aberto enquanto a barra mudou) **não** sobrescreve o que mudou por fora, e a escrita **não** regrava as `Settings` inteiras.
5. **FLX-49**: o serviço e o IME leem os fluxos por **leitura leve** do DataStore (sem inicializar o cofre cifrado) e observam as mudanças ao vivo (trocar o gatilho vale no gesto seguinte, sem reiniciar o serviço).
6. **FLX-50**: um gatilho que aponta para um fluxo inexistente ou para um botão de barra que não existe mais SHALL ser ignorado no disparo e **removido** na próxima gravação.

**Independent Test**: round-trip de uma config com todas as etapas, as superfícies (a orbe nos **três lugares** e a barra) e instruções com acentos e aspas; JSON malformado → padrão; duas escritas concorrentes (modal e barra) não se sobrescrevem.

---

### P2-H: Qualidade (`QLD`)

1. **FLX-51**: todo texto novo da interface SHALL passar por `tr("…")` (português como chave), com as traduções em `i18n/en/Fluxos.kt` e `i18n/es/Fluxos.kt` (ordem crescente do português); as chaves do `FluxosPanel` antigo que ficarem sem uso saem. `TranslationsTest` verde.
2. **FLX-52**: o pacote `com.keyflow.fluxos` (modelo, codec, validação, restaurar, roteamento) e o subpacote `fluxos/execucao` SHALL ser **JVM puro** (sem `android.*`, sem `Context`, sem Compose); o código Android fica só em `fluxos/app`, que a regra exclui e o executor SHALL **não importar** o downloader de modelos nem o cofre; `ArchitectureRulesTest` confere. A tela usa `collectAsStateOnMain`; nada de `runBlocking` novo.
3. **FLX-53**: goldens claro/escuro das telas novas (lista com os 3 padrão; lista com atalhos e um card Incompleto; vazia; modal; seletor de etapa; seletor de modelo; aviso de substituir; confirmação de restaurar; confirmação de excluir; barra do teclado com botão de fluxo; Sobrepor com gesto ligado). Goldens existentes ficam bit-idênticos, salvo a barra/Sobrepor onde a mudança é intencional.
4. **FLX-54**: acessibilidade: alvos de toque ≥ 48 dp; cada ação tem `contentDescription`; toda reordenação por arrastar tem a alternativa por botão (FLX-12); o estado "Em execução" e "Incompleto" não depende só de cor.
5. **FLX-55**: a aba **Docs** SHALL ganhar a seção "aba-fluxos" (o que é um fluxo, as etapas, os padrão, atalhos, restaurar, o que sai do aparelho) e o `docsSectionFor(FLUXOS)` passa a apontar para ela; `website/_fonte/doc.json` é regenerado (`DocsSiteExportTest`).
6. **FLX-56**: a aba sai do "Em breve" só na task que liga o painel (FLX-T22); antes disso o `ConfigScreen` continua com o cartão (`deferredTabsShowComingSoonCardWithoutControls` muda nessa task). Sem flag de build: a `release/v1` está congelada e a `main` é a release seguinte.

---

### P2-I: A orbe em três lugares (`LUG`) — entrega dependente de outras features

**Como** usuário, **quero** que a orbe da notificação e a orbe do teclado também disparem fluxos, do mesmo jeito que a flutuante.

1. **FLX-57**: o **modelo** SHALL tratar `ORBE(lugar, toques)` com `lugar ∈ {FLUTUANTE, TECLADO, NOTIFICACAO}` (`LugarDaOrbe`, do pacote compartilhado `com.keyflow.orbe`) e `toques ∈ {2, 3}` (na `NOTIFICACAO` também 1, porque lá o toque simples é remapeável) desde a FLX-T1 (codec, roteador e política de ligação já os conhecem). A orbe da notificação só tem toques (spec `notification`, L1); a da flutuante e a do teclado ficam em 2 e 3 toques na V1 (segurar e arrastar seguem fora, ver Fora de escopo). O modal, na seção **Atalhos**, SHALL agrupar as linhas por lugar — "Orbe flutuante", "Orbe da notificação", "Orbe do teclado" e "Botão do teclado" — e **o grupo de um lugar só aparece quando o lugar existe** (a aba Notificação ligada; a orbe do teclado entregue); nenhum grupo mostra um lugar que ainda não faz nada.
2. **FLX-58**: **orbe da notificação como gatilho**: depois que a notificação resolve uma rajada de 2 ou 3 toques (NTF-09), o serviço SHALL consultar `RoteadorGatilhos.resolverToques(NOTIFICACAO, n, …)` **antes** da ação própria da notificação: fluxo válido → roda e **não** executa a ação; fluxo inválido → "Fluxo incompleto: abra a aba Fluxos" (a ação por baixo não roda); sem gatilho → a ação da notificação. A tela bloqueada recusa antes de qualquer fluxo (NTF-16); o disparo segue as precondições do FLX-39. Vale com "copiar" **desligado** (FLX-60). Entregue pela FLX-T28 (lado de Fluxos) e pela NTF-T20 (lado da notificação).
3. **FLX-59**: **orbe do teclado como gatilho**: o mesmo roteamento para `TECLADO`, quando a orbe do teclado existir (feature com **spec própria, em andamento em outra frente** — Yan, 29/10). A FLX-T29 fica **bloqueada** até lá.
4. **FLX-60**: **copiar a configuração da orbe principal**: quando o lugar (`TECLADO` ou `NOTIFICACAO`) está com "copiar" ligado, os gatilhos dele SHALL ser os da orbe **flutuante** (`ORBE(FLUTUANTE, n)`), e o modal SHALL **não** oferecer o grupo desse lugar (mostra "Copiando da orbe principal"). Os gatilhos **próprios** `ORBE(lugar, n)` já gravados são **preservados** (nunca apagados por copiar) e ignorados enquanto se copia; voltam a valer quando "copiar" é desligado. Interações que o lugar não suporta ficam de fora (`SuporteDeInteracoes`). A cópia vale ao vivo.

**Independent Test**: com a notificação ligada e "copiar" desligado, ligar o 2 toques da notificação a "Ouvir e responder" → 2 toques na notificação roda o fluxo e 2 toques na flutuante segue retraindo o teclado; ligar "copiar" → o 2 toques da notificação passa a rodar o que a flutuante faz (a ação ou o fluxo dela) e o grupo some do modal; desligar "copiar" → o gatilho próprio volta a valer; round-trip do JSON com `orb:teclado:2` e `orb:notificacao:3` sem perda.

---

## Modelo de dados

### Etapas

| Etapa (rótulo) | `type` no JSON | Entrada | Saída | Modelo (aba de onde vem o padrão) | Campos próprios |
| -------------- | -------------- | ------- | ----- | --------------------------------- | --------------- |
| **Ditar** | `ditar` | Nada | Texto | `TranscriptionSource` (aba Transcrição) | — |
| **Capturar** | `capturar` | Nada | Imagem | — | — |
| **Visão** | `visao` | Imagem | Texto | `VisionSource` (aba Visão) | `instrucao` (vazio = ler o texto) |
| **Perguntar** | `perguntar` | Texto | Texto | `ReasoningSource` (aba Raciocínio) | `sistema` (opcional) |
| **Falar** | `falar` | Texto | Texto (o mesmo) | `FalaSource` (aba Voz) | — |
| **Inserir** | `inserir` | Texto | Texto (o mesmo) | — | — |
| **Agente** | `agente` | Texto | Texto | `ReasoningSource` (aba Raciocínio) | — |

`Tipo` = `NADA | TEXTO | IMAGEM`. A cadeia vale quando `saida(etapa[i]) == entrada(etapa[i+1])`. As duas etapas de **saída**
(Falar, Inserir) devolvem o texto que receberam: assim "Perguntar → Falar → Inserir" compõe sem regra especial.

### Tipos

```kotlin
enum class Tipo { NADA, TEXTO, IMAGEM }

/** Qual modelo; `origem` é o NOME do enum da aba da etapa (nunca CREDITS). `modelo` nulo = o que a aba escolheu daquela origem. */
data class ModeloRef(val origem: String, val modelo: String? = null)

sealed interface Etapa {
    data class Ditar(val modelo: ModeloRef? = null) : Etapa
    data object Capturar : Etapa
    data class Visao(val instrucao: String? = null, val modelo: ModeloRef? = null) : Etapa
    data class Perguntar(val sistema: String? = null, val modelo: ModeloRef? = null) : Etapa
    data class Falar(val modelo: ModeloRef? = null) : Etapa
    data object Inserir : Etapa
    data class Agente(val modelo: ModeloRef? = null) : Etapa
    data class Desconhecida(val tipo: String) : Etapa          // JSON de versão mais nova; nunca executa
}

data class Fluxo(
    val id: String,              // 8 hex aleatórios; o semeado na 1ª abertura usa `s-<builtinId>` (estável até a 1ª gravação); o recriado pelo restaurar ganha id novo (FLX-44)
    val nome: String,            // 1..40
    val padrao: String? = null,  // `builtinId` do catálogo; nulo = criado pela pessoa
    val etapas: List<Etapa>,     // 0..8
)

/** Onde há uma orbe (D9). Vive em `com.keyflow.orbe`, compartilhado com a feature `notification`. */
enum class LugarDaOrbe { FLUTUANTE, TECLADO, NOTIFICACAO }

sealed interface Superficie {
    data class Orbe(val lugar: LugarDaOrbe, val toques: Int) : Superficie   // toques: 2 ou 3 (1 também na NOTIFICACAO)
    data class Barra(val botaoId: String) : Superficie
}
data class Gatilho(val superficie: Superficie, val fluxoId: String)
data class ConfigFluxos(val fluxos: List<Fluxo>, val gatilhos: List<Gatilho>)
```

`ModeloRef.modelo` por etapa: **Ditar** — Local: id do catálogo; OpenAI/Groq: nome do modelo; Custom: ignorado. **Visão** — Local: id do catálogo;
nuvem: id do modelo com visão (`VisionModelCatalog`); Custom: ignorado. **Perguntar/Agente** — Local: id do catálogo; nuvem: id do modelo;
Custom: ignorado. **Falar** — Local: id do catálogo; OpenAI/ElevenLabs/Google/Custom: a voz (`falaCloudVoice`, `falaElevenLabsVoice`, …); Sistema: ignorado.

### JSON (`flows_json` e `flow_triggers_json`)

```json
{ "v": 1, "flows": [
  { "id": "a1b2c3d4", "name": "Ouvir e responder", "builtin": "ouvir-responder",
    "steps": [
      { "type": "ditar" },
      { "type": "perguntar", "system": "Responda em poucas frases, como numa conversa falada.", "model": { "source": "OPENAI", "model": "gpt-4o" } },
      { "type": "falar" } ] } ] }

{ "v": 1, "triggers": [
  { "surface": "orb:flutuante:2",   "flow": "a1b2c3d4" },
  { "surface": "orb:notificacao:3", "flow": "a1b2c3d4" },
  { "surface": "orb:teclado:2",     "flow": "a1b2c3d4" },
  { "surface": "bar:b81f02",        "flow": "a1b2c3d4" } ] }
```

Superfícies: `orb:<lugar>:<toques>` (lugar em minúsculas: `flutuante`, `teclado`, `notificacao`; toques 2 ou 3; 1 só em `notificacao`) e `bar:<botaoId>`. O rascunho anterior (`orb:2`, `notif:<acaoId>`) nunca foi implementado, então não há migração.

Limites: 20 fluxos; 8 etapas por fluxo; nome 40; instrução e sistema 500; gatilhos 64. Tudo acima é cortado ao ler (FLX-47).

### Fluxos padrão (catálogo no código)

O catálogo mora no código (`PadroesFluxo`), com `builtinId`, nome (`trKey`), etapas e a versão do catálogo (`PADROES_VERSAO = 1`). Mudar o
catálogo numa release nova só aparece para quem usar "Restaurar fluxos padrão"; os padrão já gravados **não** mudam sozinhos.

---

## Restaurar fluxos padrão — resumo do contrato

| Situação do padrão na lista | O que o restaurar faz | Gatilhos |
| --------------------------- | --------------------- | -------- |
| Apagado | Recria (nome e etapas do catálogo, id novo, posição pelo catálogo) | Nenhum — **nunca** liga |
| Editado (nome, etapas, modelo, instrução) | Reescreve nome e etapas pelo catálogo; mantém id e posição | **Intactos** (seguem apontando para o mesmo id) |
| Igual ao catálogo | Nada | Intactos |
| Fluxo da pessoa (`padrao` nulo) | Nada | Intactos |
| — | Não mexe nas `Settings`, na barra, no cofre | — |

## Erros — causas, mensagens e destino (FLX-27)

| Causa (`CausaErro`) | Quando | Mensagem (após “O fluxo parou em “<etapa>”: ”) | Tocar no aviso abre |
| ------------------- | ------ | ---------------------------------------------- | ------------------- |
| `INDISPONIVEL` | sem modelo local selecionado/instalado, provedor desconectado, sem modelo escolhido na origem, `ModeloRef` inválido | o motivo já usado pelas abas ("Nenhum modelo local selecionado", "O modelo ainda não foi baixado", "Conecte o provedor") | a aba da etapa (Transcrição, Visão, Raciocínio, Voz) |
| `REDE` | sem conexão, DNS, corte | "Sem conexão de rede." | — |
| `AUTENTICACAO` | 401/403, token expirado | "Falha de autenticação — verifique a conexão do provedor." | a aba da etapa |
| `LIMITE` | cota ou taxa do provedor | "Limite de uso do provedor atingido." | — |
| `MEMORIA` | `OutOfMemoryError`, guarda de RAM do modelo local | "Memória insuficiente para este modelo. Tente um menor." | a aba da etapa |
| `VAZIO` | nada captado pelo microfone, nenhum texto na imagem, resposta em branco | "Nada foi captado." / "Nenhum texto encontrado." / "O modelo não respondeu." | — |
| `TEMPO` | passou do tempo máximo (FLX-31) | "Tempo esgotado." | — |
| `MOTOR` | exceção do motor, processo morto, agente ocupado, voz do sistema sem dados | a frase do motor, já curta e traduzida, sem corpo cru de resposta | a aba da etapa, quando a frase pede |
| (cancelado) | tocou para cancelar, consentimento negado, janela de confirmação fechada | não é erro: sem aviso de falha | — |

---

## Edge Cases

- WHEN a pessoa edita um fluxo **enquanto ele roda** THEN a execução em curso usa a versão de quando começou; a edição vale na próxima.
- WHEN um fluxo ligado a um gesto é **excluído** THEN o gesto volta à ação do `Settings`, sem a pessoa fazer nada (FLX-35).
- WHEN a pessoa apaga o botão do fluxo no editor de barras THEN o gatilho dele sai; o fluxo e os outros atalhos ficam.
- WHEN as barras são **espelhadas** (`mirrored`) THEN o botão fica na lista única; WHEN não são, o modal acrescenta o mesmo `botaoId` às duas listas (um gatilho só).
- WHEN `NEAREST_DOWN` e só o 3 toques está ligado a fluxo THEN 4 toques roda o fluxo (FLX-34).
- WHEN o fluxo começa com **Capturar** e a gravação está **RECORDING** THEN o `VisionFlow` pausa e, ao fim, devolve a orbe a PAUSED; o texto é inserido com a gravação pausada (como o Visão faz).
- WHEN o fluxo tem **Ditar** e a permissão de microfone falta THEN o mesmo aviso do app ("falta permissão de microfone", leva à aba Sobrepor).
- WHEN o campo some entre Ditar e Inserir (a pessoa trocou de app) THEN `insertOrHold` guarda para colar ao focar o próximo campo e/ou deixa na área de transferência (regras do auto-colar).
- WHEN a Voz está em **Local** sem modelo e o fluxo tem Falar THEN falha `INDISPONIVEL` na etapa; o fluxo não troca para a voz do Sistema sozinho (FENG-05 da síntese de fala).
- WHEN o texto de entrada de **Falar** passa do limite do provedor (OpenAI 4096) THEN é dividido em trechos (FLX-22), não falha.
- WHEN o texto lido da tela entra no **Agente** THEN ele é dado, não ordem: as ferramentas que escrevem continuam pedindo confirmação (FLX-25) e a política de ferramentas não muda.
- WHEN o JSON traz uma etapa de uma versão mais nova THEN o fluxo aparece Incompleto ("Etapa desconhecida"), não roda e permite remover a etapa.
- WHEN o processo do app morre no meio THEN o fluxo some (sem retomar); a gravação em curso segue as regras já existentes.
- WHEN o idioma da interface muda THEN nomes de padrão não renomeados e rótulos de etapa trocam ao vivo; nomes que a pessoa digitou ficam como estão.
- WHEN a notificação (ou a orbe do teclado) está com "copiar" ligado THEN o modal não mostra o grupo do lugar e o gatilho próprio gravado fica preservado e inativo (FLX-60); desligar "copiar" o reativa.
- WHEN a pessoa liga um fluxo ao 2 toques da notificação com a tela bloqueada e "permitir com a tela bloqueada" desligado THEN o toque é recusado pela notificação (NTF-16) e o fluxo não roda.
- WHEN a orbe da notificação toca durante a execução de um fluxo THEN o toque cancela o fluxo (FLX-29), não conta como início de outro; o aviso "Já há um fluxo em andamento" vale para disparar um segundo.
- WHEN o JSON traz um gatilho de um lugar que ainda não existe no app (`orb:teclado:2` antes da orbe do teclado) THEN ele é lido e regravado sem perda, sem aparecer nem disparar.

---

## Decisões do autor desta spec (a confirmar pelo Yan)

| # | Decisão | Por quê | Alternativa |
| - | ------- | ------- | ----------- |
| A1 | **Falar e Inserir devolvem o mesmo texto** (passagem), não `Nada` | permite "Perguntar → Falar → Inserir" sem etapa nova | Falar/Inserir como fim de linha |
| A2 | Etapas de **entrada** (Ditar, Capturar) só na **1ª posição**; sem variáveis | V1 é lista linear; "capturar + ditar" juntos exigem variáveis | variáveis nomeadas (depois) |
| A3 | O gatilho de gesto **sombreia** a ação do `Settings` em vez de sobrescrevê-la | desligar/excluir devolve a ação de graça; o auto-save da tela, que regrava as `Settings`, não desfaz o gatilho | gravar `RUN_FLOW` no enum e guardar a ação anterior no gatilho |
| A4 | Botão da barra novo (`BarButton.RunFlow(botaoId)`) criado **só pelo modal** | a escolha é no modal (D2); evita dois lugares para ligar | também no catálogo do editor de barras |
| A5 | **Ditar** no fluxo sempre com semântica de **alternar** (toque inicia/envia), mesmo com a orbe em Push-to-talk; só inicia com a gravação ociosa | no PTT o gatilho de 2 toques não tem o "segurar" para terminar; o mic do teclado já é assim | PTT respeitado; pausar a gravação em curso |
| A6 | **Agente** usa o `AgentController` real (mesma conversa, mesmo registro) e a confirmação aparece numa **janela translúcida** do KeyFlow | reuso total da política/painel; o que o fluxo pediu fica auditável na aba KeyFlow | conversa isolada por execução |
| A7 | Modal com **gravação na hora**; fluxo inválido é gravado como **Incompleto** | mesmo padrão dos editores de barra/grade; sem estado "rascunho" | botão Salvar |
| A8 | Limites: 20 fluxos, 8 etapas, 40/500 caracteres, 800 por trecho falado | JSON pequeno, lista legível | outros números |
| A9 | Recriado no restaurar ganha **id novo** e posição pelo catálogo | gatilho órfão nunca o reata (FLX-44) | id fixo = `builtinId` |
| A10 | **Sem fallback** de modelo (nem para "Da aba" quando o `ModeloRef` some) | D6; trocar origem em silêncio muda para onde o dado vai (privacidade) | cair no modelo da aba com aviso |
| A11 | Excluir um fluxo remove também os **botões de barra** que o modal criou para ele | botão sem fluxo é botão que não faz nada | deixar desabilitado |
| A12 | Tempos máximos por etapa (FLX-31) | nenhuma etapa pode travar a fila "um fluxo por vez" | sem teto |
| A13 | Aba sai do "Em breve" sem flag de build (FLX-56) | `release/v1` congelada; padrão do `AGENT_ENABLED` hoje (ligado em todos os builds) | `BuildConfig.FLOWS_ENABLED` |
| A14 | A superfície é `ORBE(lugar, toques)` num tipo só, com `LugarDaOrbe` compartilhado (`com.keyflow.orbe`) | um modelo para os três lugares, sem tipo novo por lugar; a feature `notification` usa o mesmo enum | `Superficie.OrbeFlutuante/OrbeTeclado/OrbeNotificacao` separadas |
| A15 | O modelo, o codec e o roteador já aceitam os três lugares na FLX-T1/T2/T5; a **tela** só mostra o lugar que existe | evita mudar o formato depois e evita prometer atalho que não faz nada | formato só da flutuante e migração depois |
| A16 | "Copiar a configuração da orbe principal" também copia os **gatilhos** (FLX-60), e o gatilho próprio gravado fica preservado | copiar é copiar tudo o que a orbe principal faz, e desligar "copiar" não pode apagar escolhas | copiar só as ações; ou apagar os gatilhos próprios |
| A17 | Na notificação o 1 toque é remapeável (padrão gravar/enviar; Yan, 29/10) e **também liga fluxo**; na flutuante o 1 toque segue sem ser gatilho | a notificação não tem mais um 1 toque nativo fixo (NTF-10); o catálogo dela terá "Rodar fluxo" em qualquer dos três toques | 1 toque só gravar/enviar |

### Dúvidas para o Yan

- **Q1** — O Agente do fluxo deve **dividir a conversa da aba KeyFlow** (A6) ou rodar numa conversa **isolada** por execução? Dividir deixa o rastro visível, mas mistura o contexto do chat com os pedidos ditados.
- **Q2** — **Ditar** disparado por 2 toques com a orbe em Push-to-talk (A5): aceitar o "alternar", ou prefere que o fluxo respeite o PTT (segurar a orbe ao falar)?
- **Q3** — Gatilho que **sombreia** (A3): ao excluir/desligar, a ação antiga volta sozinha. É o que você espera, ou prefere que ela fique em "Nenhuma"?
- **Q4** — Quer um botão **"Rodar agora"** no modal na V1? Ele ajuda a testar, mas Capturar/Inserir dependem do app em foco.
- **Q5** — Disparo com a **gravação em andamento** (RECORDING/PAUSED) num fluxo com Ditar: recusar (FLX-18) ou pausar/guardar a gravação?
- **Q6** — Os 3 padrão ficam **sem atalho** na primeira abertura (FLX-40). Quer uma *dica* escrita no modal de cada um ("Sugestão: 2 toques"), sem ligar nada?
- **Q7** — Coordenação com a **bancada**: `Capacidade{TEXTO,AUDIO,IMAGEM}` e `Etapa/Fluxo` do BFL-01 são parentes destes tipos. A bancada deve **consumir** os tipos de `com.keyflow.fluxos` (a BNC-T23 passaria a depender da FLX-T1)?
- **Q8** *(respondida pelo Yan em 29/10)* — A **orbe do teclado** terá **spec própria, em outra frente já em andamento**: o gatilho dela já está no modelo (FLX-57) e a FLX-T29 espera por essa spec. Ela decide quais interações o teclado suporta (toques, segurar, arrastar?) e se alguma além de 2 e 3 toques vira gatilho; a dependência é mantida.
- **Q9** — Os gatilhos da orbe da **notificação** e do **teclado** com "copiar" ligado seguem a orbe flutuante (FLX-60, A16). É o que você espera, ou prefere que "copiar" leve só as ações e cada lugar tenha seus gatilhos?

---

## Requirement Traceability

| Requirement | Story | Task | Status |
| ----------- | ----- | ---- | ------ |
| FLX-01 | P1-A | FLX-T17, FLX-T23 | Pending |
| FLX-02 | P1-A | FLX-T17 | Pending |
| FLX-03 | P1-A | FLX-T17 | Pending |
| FLX-04 | P1-A | FLX-T17 | Pending |
| FLX-05 | P1-A | FLX-T17 | Pending |
| FLX-06 | P1-B | FLX-T18 | Pending |
| FLX-07 | P1-B | FLX-T18, FLX-T19 | Pending |
| FLX-08 | P1-B | FLX-T19 | Pending |
| FLX-09 | P1-B | FLX-T19 | Pending |
| FLX-10 | P1-B | FLX-T20 | Pending |
| FLX-11 | P1-C | FLX-T18 | Pending |
| FLX-12 | P1-C | FLX-T18 | Pending |
| FLX-13 | P1-C | FLX-T1 | Pending |
| FLX-14 | P1-C | FLX-T1, FLX-T18 | Pending |
| FLX-15 | P1-C | FLX-T1, FLX-T18 | Pending |
| FLX-16 | P1-C | FLX-T18 | Pending |
| FLX-17 | P1-D | FLX-T6, FLX-T14, FLX-T25 | Pending |
| FLX-18 | P1-D | FLX-T11 | Pending |
| FLX-19 | P1-D | FLX-T12 | Pending |
| FLX-20 | P1-D | FLX-T10 | Pending |
| FLX-21 | P1-D | FLX-T10 | Pending |
| FLX-22 | P1-D | FLX-T8, FLX-T10 | Pending |
| FLX-23 | P1-D | FLX-T10 | Pending |
| FLX-24 | P1-D | FLX-T13 | Pending |
| FLX-25 | P1-D | FLX-T13 | Pending |
| FLX-26 | P1-D | FLX-T7, FLX-T9 | Pending |
| FLX-27 | P1-D | FLX-T6, FLX-T14, FLX-T25 | Pending |
| FLX-28 | P1-D | FLX-T6, FLX-T14 | Pending |
| FLX-29 | P1-D | FLX-T6, FLX-T14, FLX-T25 | Pending |
| FLX-30 | P1-D | FLX-T7, FLX-T17 | Pending |
| FLX-31 | P1-D | FLX-T6 | Pending |
| FLX-32 | P1-D | FLX-T14, FLX-T16 | Pending |
| FLX-33 | P1-E | FLX-T1, FLX-T2, FLX-T5 | Pending |
| FLX-34 | P1-E | FLX-T5, FLX-T15, FLX-T25 | Pending |
| FLX-35 | P1-E | FLX-T5, FLX-T20, FLX-T21, FLX-T28 | Pending |
| FLX-36 | P1-E | FLX-T16, FLX-T20 | Pending |
| FLX-37 | P1-E | FLX-T5, FLX-T20 | Pending |
| FLX-38 | P1-E | FLX-T5, FLX-T20 | Pending |
| FLX-39 | P1-E | FLX-T14, FLX-T15, FLX-T25 | Pending |
| FLX-40 | P1-F | FLX-T3 | Pending |
| FLX-41 | P1-F | FLX-T3, FLX-T17 | Pending |
| FLX-42 | P1-F | FLX-T3 | Pending |
| FLX-43 | P1-F | FLX-T3 | Pending |
| FLX-44 | P1-F | FLX-T3 | Pending |
| FLX-45 | P1-G | FLX-T2, FLX-T4 | Pending |
| FLX-46 | P1-G | FLX-T2 | Pending |
| FLX-47 | P1-G | FLX-T2 | Pending |
| FLX-48 | P1-G | FLX-T4 | Pending |
| FLX-49 | P1-G | FLX-T4, FLX-T14 | Pending |
| FLX-50 | P1-G | FLX-T4, FLX-T5 | Pending |
| FLX-51 | P2-H | FLX-T17..T21 (cada uma traz os seus textos) | Pending |
| FLX-52 | P2-H | FLX-T1, FLX-T6, FLX-T10 | Pending |
| FLX-53 | P2-H | FLX-T23 | Pending |
| FLX-54 | P2-H | FLX-T17, FLX-T18 | Pending |
| FLX-55 | P2-H | FLX-T24 | Pending |
| FLX-56 | P2-H | FLX-T22 | Pending |
| FLX-57 | P2-I | FLX-T1, FLX-T2, FLX-T5, FLX-T20 | Pending |
| FLX-58 | P2-I | FLX-T28, NTF-T20 | Pending (depende da feature `notification`) |
| FLX-59 | P2-I | FLX-T29 | Pending (bloqueada: depende da orbe do teclado, spec em outra frente) |
| FLX-60 | P2-I | FLX-T5, FLX-T28, FLX-T29 | Pending (a parte do teclado, FLX-T29, está bloqueada: depende da orbe do teclado, cuja spec está em outra frente) |

**Coverage:** 60 requisitos, 60 mapeados em tasks, 0 sem task.
**Status values:** Pending → In Design → In Tasks → Implementing → Verified.

---

## Success Criteria

- [ ] **Primeira abertura**: a aba mostra os 3 fluxos padrão, sem atalho; tocar abre o modal com etapas e modelos "Da aba"; o "+" cria; "Restaurar fluxos padrão" aparece.
- [ ] **Ouvir e responder** ligado a 2 toques no A32: falar → a resposta é falada; o teclado não retrai; desligar devolve a retração.
- [ ] **Ler a tela e colar** ligado a um botão do teclado: seleciona a área, lê o texto e cola no campo; sem modelo de Visão, o aviso leva à aba Visão e nada é baixado.
- [ ] **Pedir ao agente e colar**: o agente pede confirmação de uma ferramenta que escreve **sobre outro app**; negar encerra sem inserir; permitir cola a resposta.
- [ ] Falha no meio (modo avião no Perguntar em nuvem): o fluxo para, o aviso diz a etapa e a causa, nada é falado nem colado depois.
- [ ] Cancelar (toque na orbe depois do Ditar; gesto de cancelar da gravação durante o Ditar) deixa a orbe no estado anterior, sem áudio tocando e sem captura em cache.
- [ ] Restaurar: apaga-se um padrão que tinha atalho, edita-se outro que tinha atalho, cria-se um fluxo próprio → restaurar recria o primeiro **sem** atalho, desfaz a edição do segundo **mantendo** o atalho e não toca o terceiro.
- [ ] Barra do teclado: o botão criado pelo modal dispara o fluxo e some quando o fluxo é excluído.
- [ ] Orbe em três lugares: os JSON guardam `orb:flutuante:2`, `orb:notificacao:3` e `orb:teclado:2` sem perda; **(depende da `notification`)** o 2 toques da notificação roda um fluxo sem mexer no 2 toques da flutuante, e com "copiar" ligado ela segue a flutuante.
- [ ] Os dois JSON não contêm chave, token, endpoint nem header; goldens claro/escuro aprovados; `TranslationsTest` e `ArchitectureRulesTest` verdes.
- [ ] Gate `full` verde; requisitos → Verified.
