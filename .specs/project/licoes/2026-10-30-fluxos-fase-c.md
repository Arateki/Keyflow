# 2026-10-30 — Fluxos, Fase C (parte 1): portas, Ditar, Capturar, Agente e botão da barra (equipe/fluxos-fase-c)

FLX-T10, T11, T12, T13 e T16. T14 (`FluxoHost` no serviço) e T15 (2 e 3 toques) ficam para a parte 2: nada aqui dispara um fluxo ainda. Tudo o que o `FluxoHost` vai chamar já existe e tem teste: `OverlayService.ditarParaFluxo`, `capturarParaFluxo`/`cancelarCapturaDeFluxo`, `MontagemDasPortas.base`/`agente`, `PortasPorTipo`.

## O que entrou

- **T10** `fluxos/app/PortasAndroid.kt`, `fluxos/execucao/ErrosDeFluxo.kt` (a tabela "Erros" em código), `fluxos/app/MontagemDasPortas.kt`, `AmbienteMotores.settingsAgora/configDaTranscricao`.
- **T11** `fluxos/execucao/DestinoFluxo.kt`, gancho no `OverlayCoordinator`, `PortaDitarOverlay`, modo `TOGGLE` durante o ditado de fluxo no `OverlayService`.
- **T12** `DestinoCaptura` no `VisionFlow`, `PortaCapturarOverlay`.
- **T13** `AgentParts.novaSessao`, `PortaAgenteApp`/`SessaoDoFluxo`/`SessaoAtiva`, `FluxoConfirmacaoActivity` (+ manifest).
- **T16** `BarButton.RunFlow` em todos os `when`, `BarView`/`KeyFlowKeyboardView`/serviço do IME, `BotoesDeFluxo`, editor de barras, FLX-50 fechado no `SettingsRepository`.

## Decisões onde a spec não decidia (o mais conservador)

- **Falar com texto em branco** falha com `VAZIO` ("Não há texto para falar."), em vez de passar adiante sem falar.
- **Visão: "Unavailable" do motor** (modelo sem ser baixado no meio da chamada) vira `INDISPONIVEL` com "O modelo ainda não foi baixado." e a aba Visão.
- **Ditar: falha ao abrir o microfone** também libera o destino (`INDISPONIVEL`/`MOTOR`); a task listava só quatro fins. O aviso com destino na aba Sobrepor (permissão do microfone) continua saindo pelo coordenador, porque só ele sabe levar à aba; os demais avisos de falha da sessão ficam com o fluxo.
- **Capturar de fluxo recusa `RECORDING`/`PAUSED` dentro do `VisionFlow`** (a spec dá a recusa com aviso ao host, T14). Rede de segurança: sem ela, um chamador esquecido pausaria a gravação do usuário.
- **Agente: janela que não fica visível em 15 s nega** (`PRAZO_DA_JANELA_MS`). A spec diz "sem a janela visível nenhuma ferramenta que pede confirmação roda" mas não dá prazo; 15 s cobre o arranque de uma Activity em aparelho lento. Um `Notice` do agente (limite de passos, resposta cortada, recusa) usa as **mesmas frases** da aba KeyFlow.
- **Agente: o erro do motor não é texto.** O `AgentController` escreve "[mensagem]" no balão quando o motor falha; ler a "última resposta" devolveria isso como se fosse resposta. A porta embrulha o `AgentEngine` (e o `TextOnly`) para ver o `AgentEvent.Error` e as exceções, e falha com a causa tipada.
- **Botão de fluxo no IME**: o callback `onRunFlow` chega ao `KeyFlowKeyboardService.runFlow`, que só escreve no log. Ligar ao `ACTION_RUN_FLOW` é da T14 (instrução da rodada); nenhum botão existe antes do modal da T22 criá-lo.
- **`saveBarsConfig` limpa os gatilhos órfãos** (remover o `RunFlow` no editor desliga o gatilho: FLX-36) mas só regrava os fluxos quando algum gatilho ficou órfão, para não "adotar" a semente dos fluxos ao salvar barras.

## Riscos conhecidos

- **Estado por turno das ferramentas é compartilhado**: o catálogo é o mesmo do chat (D10), e as ferramentas `TurnScoped` (skins em lotes, `open_tab`) guardam estado pelo número da execução do controlador. Os dois controladores contam a partir de 1; chat e fluxo rodando **ao mesmo tempo** com uma dessas ferramentas podem pisar no estado um do outro. O custo de resolver agora é um parâmetro de deslocamento no `AgentController`; deixei fora (caso raro, e só duas ferramentas) e anotei aqui.
- **OCR local escreve o recorte em disco** (`cacheDir/vision`, apagado no `finally`): é o transporte do `:vision`, o mesmo do Testar da aba. A FLX-30 diz que o fluxo não grava imagem em disco; para o PaddleOCR isso só se cumpre mudando o transporte do `:vision` (fora desta fase).
- **`PortaInserir` roda no dispatcher de quem executa.** Hoje é a main do serviço; se o `FluxoHost` (T14) mudar de dispatcher, o `insertOrHold` precisa voltar para a main.

## Lições

- **Libere o destino depois de assentar a máquina**: a próxima etapa (Capturar, por exemplo) olha `coordinator.currentState()`; se o destino fosse avisado antes de `render(onTranscriptionSuccess())`, ela veria `SENDING` e o `VisionFlow` recusaria. O teste `the destination is released after the machine is back at rest...` trava isso.
- **`internal` de teste é visível entre arquivos do mesmo módulo**: `FakeTool`, `ScriptedEngine` e `Harness` de `agent/AgentTestSupport.kt` serviram direto aos testes de `fluxos/app`.
- **`ScriptedEngine` com um motor só para dois controladores troca de volta**: o contador de voltas é do motor. Teste com chat + fluxo precisa de um motor por controlador (a falha apareceu como `NullPointerException` num `pending.value!!`).
- **Teste existente que muda por causa da task**: `barsAndTriggersAreWrittenInOneTransaction` ligava um gatilho de barra sem o botão; com FLX-50 fechado esse gatilho é (corretamente) limpado. O teste passou a criar o `RunFlow` junto, e três testes novos cobrem o órfão, a remoção no editor e a semente.
