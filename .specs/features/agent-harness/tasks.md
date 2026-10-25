# Agente KeyFlow com ferramentas — Tasks

Spec: `spec.md` · Design: `design.md`. Uma task por vez, na ordem; **commit atômico por task** (português, sem citar Claude, sem
trailer). Atualizar o Status aqui e a traceability do `spec.md` ao concluir.

## Fase 1 — Núcleo, Anthropic, chat, skins e Ajustes

### AGT-T1 — Núcleo puro: ferramentas, política, filtro, registro, laço
- **What**: `AgentTool`/`ToolRegistry` (esquema + validação), `ToolPolicy`, `Confirmation` (interface), `SecretFilter`, `ActionLog`, `AgentMessage`, `AgentEngine` (contrato), `AgentLoop` (limite de passos, cancelamento, negação, erro de ferramenta).
- **Done when**: tudo na JVM com motor falso; matriz da política testada; filtro com sentinelas; laço com ordem/limite/cancelamento; nenhum `android.*` no núcleo.
- **Tests**: unit. **Gate**: `testDebugUnitTest`.
- **Status**: Done (2026-10-03, fase 1A) — tudo em `app/src/main/java/com/keyflow/agent/` (Kotlin puro; o `AgentIsolationTest` confere que não há `android.*` e que só o `ToolExecutor` chama `AgentTool.run`).
  - **O que existe**: `AgentTool`/`ToolRisk`/`ToolOutput`/`ToolFailure`/`UndoCall`/`@PolicyGated` · `ToolSchema` (subconjunto de JSON Schema + validador) · `ToolRegistry` · `ToolPolicy`/`ToolPolicyConfig`/`ConfirmationMode` · `Confirmation`/`ConfirmationRequest`/`ConfirmationDecision` · `SecretFilter` · `ActionLog`/`InMemoryActionLog` · `AgentMessage` (+ `ProviderState`) · `AgentHistory` (conserto da invariante chamada↔resultado e poda) · `AgentEngine`/`AgentEvent` · `AgentPrompt` · `ToolExecutor` (o único caminho até `run`) · `AgentLoop`/`AgentLimits`/`AgentLoopEvent`.
  - **Testes novos (JVM)**: 153 nesta task (política 8, esquema 18, registro e argumentos 13, filtro 14, executor 35, laço 27, histórico 10, prompt 7, abuso 15, isolamento do núcleo 6).
  - **Decisões onde a spec era omissa**: ver `.specs/project/STATE.md`, seção "agent-harness — AGT-T1".

### AGT-T2 — Adaptador Anthropic (`AnthropicAgentEngine`)
- **What**: `messages` em streaming com `tools`, parse de `tool_use`/`text` no SSE, conversão do histórico neutro (`tool_result`), mesmas credenciais/URL/retentativa do motor de texto; `ReasoningEngine` intocado.
- **Done when**: testes com MockWebServer cobrem texto, uma e várias `tool_use`, erro HTTP/limite/credencial; **fixtures conferidas na doc oficial da Anthropic** (não presumir formato).
- **Tests**: unit (MockWebServer). **Gate**: unit + build.
- **Status**: Done (2026-10-03, fase 1A). **SPEC_DEVIATION (local)**: o adaptador mora em `com.keyflow.reasoning.anthropic` (`AnthropicAgentEngine.kt` + `AnthropicAgentWire.kt`), não em `agent/providers/` como o design previa — o `AuthIsolationTest` só deixa o motor de cada provedor (`reasoning/<provedor>/`) importar `auth/providers/<x>` (a URL base e o formato do login), e afrouxar essa regra seria o desvio pior. O núcleo `agent/` continua sem conhecer o provedor.
  - **Formato conferido na documentação oficial** (páginas em `STATE.md`): `AnthropicAgentWire` tem a lista no KDoc; as fixtures de SSE (`AnthropicSseFixtures`) copiam os exemplos da página `streaming` e dizem o que é inventado.
  - **Testes novos (JVM)**: 60 (leitura do SSE e conversão do histórico 31; motor contra MockWebServer 29).
  - O `ReasoningEngine` e o `AnthropicReasoningEngine` ficaram **intocados** (nenhum arquivo existente foi alterado).

### AGT-T3 — `AgentController` e o chat com histórico
- **What**: mover a lógica do `KeyflowPanel` para `AgentController`; histórico; cartões de ferramenta; painel de confirmação (Permitir/Negar); cancelar; orbe reflete o estado; queda para o modo só texto (AGT-05); troca de origem.
- **Done when**: sem ferramentas registradas o chat se comporta como hoje; com elas, o ciclo completo funciona num teste de UI com motor falso; goldens existentes da aba KeyFlow inalterados (ou só os que mudarem por intenção, justificados).
- **Tests**: instrumentado (Compose) + goldens. **Gate**: unit + instrumentado escopado + goldens.
- **Status**: Done (2026-10-03, fase 1B). **SPEC_DEVIATION (local)**: o `AgentController` mora em `agent/` como o design previa, mas o que precisa de Android, da UI ou do `ReasoningEngine` mora no pacote novo `com.keyflow.agenthost` (`AgentHost`, `AgentBackends`, `AgentSecretSource`, `AgentRuntime`): o `AgentIsolationTest` proíbe `android.*`, a UI e o `reasoning/` dentro de `agent/`, e afrouxá-lo seria o desvio pior — o núcleo fala com o app por portas (`AgentBackend`, `ConfirmationGate`, `PolicyConfigStore`) e os adaptadores moram fora.
  - **O que existe**: `AgentController`/`ChatItem`/`AgentBackend` · `ConfirmationGate` · `PolicyConfigStore` (interface + em memória; a persistência é a T5) · `ui/config/AgentCards.kt` · `KeyflowPanel` refatorada (wrapper + corpo) · imagem no `tool_result` (commit `5bd47f6`) · `AgentSecretSource` ligado aos cofres · 6 rotas de galeria. Detalhes, provas e o que NÃO foi provado: `STATE.md`, seção "fase 1B, AGT-T3".
  - **Testes**: 82 unit novos (1373 no total) + `KeyflowPanelAgentTest` 12/12 e `AgentSecretsDeviceTest` 4/4 no emulador.

### AGT-T4 — Ferramentas de skin
- **What**: `skin_get_format` (FORMAT.md embutido como asset), `skin_list`, `skin_preview` (folha de contato), `skin_install` (zip em memória → `SkinPackStore`), `skin_select`, `skin_remove`; limites por chamada (SKL-06); imagem opcional ao modelo.
- **Done when**: o ciclo preview → confirmação → install → seleção → remoção funciona num teste instrumentado com um pacote real (o `exemplo-neon` como fixture); erro de validação não instala nada; nada vai a disco antes da confirmação.
- **Tests**: unit + instrumentado. **Gate**: unit + instrumentado escopado.
- **Status**: Done (2026-10-03, fase 1B). **Decisão**: o `FORMAT.md` embutido é COPIADO pelo build (`copySkinFormatAsset`), não versionado em duplicata (UMA fonte da verdade; `SkinFormatAssetTest` confere byte a byte). **SPEC_DEVIATION (local)**: as ferramentas moram em `agent/tools/` como o design previa, mas falam com o app por uma porta (`SkinWorkshop`) cujo adaptador do aparelho mora em `agenthost/` (mesmo motivo da T3: o núcleo não importa `android.*` nem `skins/`); o `AgentIsolationTest` ganhou só a exceção `import com.keyflow.i18n.tr`.
  - **O que existe**: as 6 ferramentas, rascunho em lotes com limites, `ContactSheet`, `SkinPackStore.validate`, `SettingsRepository.skinSelectionOnce/updateSkinSelection/resetSkinsUsing`, `AndroidSkinWorkshop`. Detalhes, provas e o que NÃO foi provado: `STATE.md`, seção "fase 1B, AGT-T4".
  - **Testes**: 41 unit novos (1414 no total) + `SkinToolsDeviceTest` 13/13 no emulador (o ciclo preview → confirmação → instalação → seleção → remoção → desfazer com o `exemplo-neon` real).

### AGT-T5 — Seção "Ferramentas do agente" nos Ajustes do chat + registro de ações
- **What**: chave geral, modo de confirmação, uma chave por ferramenta, **Registro de ações** (com desfazer onde houver), **Servidor MCP externo** como linha **desligada e desabilitada** ("em breve") até a fase 3; persistência no DataStore (nenhum segredo); strings `tr()` + EN/ES; rotas da galeria.
- **Done when**: ligar/desligar reflete na lista de ferramentas enviada ao modelo; o registro mostra e apaga; goldens existentes inalterados exceto os da aba KeyFlow que mudarem por intenção.
- **Tests**: instrumentado (Compose) + goldens + `TranslationsTest`. **Gate**: unit + instrumentado + goldens.
- **Status**: Done (2026-10-03, fase 1B, fechada pelo revisor). Configuração no DataStore próprio `keyflow_agent` (`DataStorePolicyConfigStore`) e registro em `files/agent/actions.jsonl` (`FileActionLog`: teto de 300 entradas/256 KB, rotação atômica, filtro de segredos, fora do backup); seção `ui/config/AgentSettingsSection.kt` nos Ajustes do chat (chave geral, modo, uma chave por ferramenta com o risco, "voltar a perguntar sempre", registro com Desfazer/Limpar, linha do MCP desligada e desabilitada); rota `agent-ajustes`. **Decisão**: se a configuração gravada não puder ser lida, as ferramentas ficam DESLIGADAS (os padrões religariam o que o usuário desligou) — `READ_FAILED`.

## Fase 2 — Ajustes do app e mais provedores

### AGT-T6 — Ferramentas de app (`app_status`, `settings_get/set`, `open_tab`)
- **What**: a **lista de permitidos** (tabela com tipo e faixa) + teste que falha se aparecer campo de `SettingsSecrets`; `settings_set` com antes → depois e desfazer.
- **Tests**: unit + instrumentado. **Status**: Done (2026-10-24, fase 2). `agent/tools/AppTools.kt` (as 4 ferramentas) sobre a porta `AppControl`, a tabela `AppSettingsAllowlist` (12 linhas, tipo e faixa; as duas skins só para leitura) e o adaptador `agenthost/AndroidAppControl.kt`; a gravação é `SettingsRepository.updateAgentSettings` (atômica, só nas chaves de `data/AgentSettingKeys.kt`, as não secretas). **Decisões**: `value` do `settings_set` é sempre texto (o esquema do app não tem união de tipos); as skins só mudam pelo `skin_select`; `open_tab` abre a aba quando a resposta TERMINA (`TurnScoped.turnCancelled` descarta ao cancelar) para o painel de confirmação da ferramenta seguinte não ficar numa tela escondida. Unit 1574/0 falhas e lint limpo; instrumentados `AppToolsDeviceTest` 11/11, `AgentSecretsDeviceTest` 4/4, `SkinToolsDeviceTest` 13/13, `KeyflowPanelAgentTest` 12/12 e `SettingsRepositoryTest` 32/32 no emulador; nenhuma tela mudou (goldens intactos). Detalhes, provas e o que NÃO foi provado: `STATE.md`, seção "fase 2, AGT-T6".

### AGT-T7 — Adaptadores OpenAI e Google
- **What**: `OpenAiAgentEngine` (`tool_calls`/`role=tool`) e `GoogleAgentEngine` (`functionCall`/`functionResponse`), conversão do histórico neutro, fixtures conferidas nas docs oficiais.
- **Tests**: unit (MockWebServer). **Status**: Done (2026-10-24, fase 2). **SPEC_DEVIATION (local)**: o `OpenAiAgentEngine` fala a **Responses API** (`function_call`/`function_call_output`), não a Chat Completions (`tool_calls`/`role=tool`) do texto acima — a doc oficial diz que os modelos GPT-6 exigem a Responses para ferramentas, e o motor de texto do app já usa `/responses`; o Google usa a `streamGenerateContent` (não a Interactions API, Beta). Os dois moram em `reasoning/<provedor>/` (`…AgentEngine.kt` rede/retentativa, `…AgentWire.kt` corpo/histórico/SSE puro), como o da Anthropic, e o `AgentBackends` os liga **só com chave de API** (login por assinatura segue no texto). 99 testes JVM novos (motores contra MockWebServer, conversão do histórico, `AgentBackends`); recorte local 751/0 falhas e lint limpo; instrumentados 27/27; goldens: só a nota do modo só texto mudou. Detalhes, fontes oficiais, o que NÃO foi provado e **o que o Yan precisa criar para usar o Google**: `STATE.md`, seção "fase 2, AGT-T7".

## Fase 3 — Skins parametrizadas e servidor MCP

### AGT-T8 — `skin_generate_from_template` + avaliador seguro de expressões
- **What**: SVGs com `{{expressão}}`, `t` em 0..1; aritmética e funções fixas; limites de tamanho/frames; saída segue pelo `skin_preview`/`skin_install`.
- **Tests**: unit (avaliador: sem acesso a nada, sem laços, erro claro). **Status**: Pending

### AGT-T9 — Servidor MCP (desligado por padrão)
- **What**: **ler a especificação oficial do MCP vigente antes de codar**; JSON-RPC 2.0 sobre HTTP; `initialize`, `tools/list`, `tools/call`; mesmo `ToolRegistry`/`ToolPolicy`; localhost por padrão; "rede local" com token; taxa; auto-desligamento; notificação; registro com origem "externa". Habilita a linha dos Ajustes.
- **Done when**: desligado → nenhuma porta aberta (teste); ligado → um cliente MCP real lista e chama; `WRITE` pede confirmação no aparelho; sem token → recusa.
- **Tests**: unit + instrumentado + manual com cliente real. **Status**: Pending

### AGT-T10 — Abuso, validação ponta a ponta e fechamento
- **What**: suíte de abuso (injeção, segredo, argumentos, flood); validação real com a Anthropic (só com autorização do Yan e a chave dele); `STATE.md`, traceability, `DEVELOPMENT-ORDER.md`; pendências **[YAN]**.
- **Status**: Parcial — recorte V1-4 auditado em 2026-10-23; proteção por minuto adicionada ao `ToolExecutor` (24 tentativas/janela móvel de 60 s, compartilhadas entre turnos) e teste de janela/recuperação. Suíte JVM existente cobre injeção, segredos, argumentos, flood por passo/turno, resultado limitado e cancelamento; `AgentSecretsDeviceTest` e `KeyflowPanelAgentTest` rodam no emulador. O build só habilita o agente em debug; release mostra `ComingSoonPanel()` e não instancia `AgentRuntime`. Falta validação com modelo real e chave/autorização do Yan **[YAN]**; sem essa evidência, agente **Em breve** na V1.
