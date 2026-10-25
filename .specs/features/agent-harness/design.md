# Agente KeyFlow com ferramentas — Design

Requisitos em `spec.md`. Esta página decide **como** encaixar no código de hoje.

## Diagnóstico do código atual (2026-10-03)

| Peça | Hoje | Consequência |
| ---- | ---- | ------------ |
| Chat | `ui/config/KeyflowPanel.kt`: o `sendMessage()` mora dentro do Composable e faz `engine.generate(ReasoningRequest(system = "Você é o KeyFlow…", user = prompt))` — **uma chamada por mensagem, sem histórico**; a lista de mensagens é `mutableStateListOf` local | A lógica do agente sai do Composable para um controlador testável na JVM |
| Motor | `reasoning/ReasoningEngine.kt`: `fun generate(request): Flow<ReasoningEvent>` — texto → texto (`Delta`/`Done`/`Error`), "sem histórico (YAGNI)" | **Não estender**: criar `AgentEngine` ao lado; o contrato de texto fica intocado (usado por Testar, Visão, Fala) |
| Provedores | `reasoning/{anthropic,openai,google,local}`; Anthropic usa `v1/messages` em streaming SSE (OkHttp, `channelFlow`) | O adaptador novo reaproveita auth/URL/retentativa do motor de texto; só muda o corpo e o parse do SSE (`tool_use`) |
| Ajustes do chat | Área expansível "Ajustes" do `KeyflowPanel` (conta, plano, "Capacidades ativas", "Limpar conversa") | Entra a seção "Ferramentas do agente" aqui |
| Skins | `skins/SkinPackStore.import`/`install(SkinPackReadResult)`, `SkinPackReader.read(InputStream)`, `SvgRender`, `PackPreview` | O `skin_install` monta um zip **em memória** e chama o mesmo caminho do import (mesmas garantias); a prévia usa `SvgRender` |
| Ajustes | `data/Settings.kt` + `SettingsRepository` (segredos no cofre `keyflow_secure`, o resto no DataStore) | A lista de permitidos referencia só campos não secretos |

## Módulos (tudo novo em `com.keyflow.agent`, sem `android.*` onde der — JVM testável)

```
agent/
  AgentTool.kt          # interface: name, description, schema (JSONObject), risk (READ/WRITE/DESTRUCTIVE), suspend run(args, ctx)
  ToolRegistry.kt       # registra, lista as ativas (Ajustes), valida args contra o esquema, resolve por nome
  ToolPolicy.kt         # decide: roda direto | pede confirmação | nega (ferramenta desligada, modo, risco)
  Confirmation.kt       # pedido (resumo gerado pelo app, preview opcional) -> Allow/Deny; interface suspensa que a UI implementa
  SecretFilter.kt       # remove padrões de segredo de TODO resultado antes de ir ao modelo e ao registro
  ActionLog.kt          # registro de ações (DataStore/arquivo próprio, sem segredos) + undo opcional
  AgentMessage.kt       # histórico neutro: User / Assistant(text, toolCalls) / ToolResult(id, content, isError)
  AgentEngine.kt        # fun chat(messages, tools, system): Flow<AgentEvent>  (TextDelta, ToolCall, Done, Error)
  AgentLoop.kt          # envia -> consome -> executa chamadas (política) -> devolve resultados -> repete; limites; cancelamento
  AgentController.kt    # estado do chat (mensagens, cartões de ferramenta, orbe) para a UI; troca de origem
  providers/AnthropicAgentEngine.kt   # fase 1; OpenAiAgentEngine.kt e GoogleAgentEngine.kt na fase 2 (moram em reasoning/<provedor>/, como o da Anthropic)
  tools/SkinTools.kt    # skin_get_format, skin_list, skin_preview, skin_install, skin_select, skin_remove
  tools/AppTools.kt     # app_status, settings_get, settings_set (lista de permitidos), open_tab
  mcp/McpServer.kt      # fase 3: JSON-RPC 2.0 / HTTP sobre o mesmo ToolRegistry + ToolPolicy
ui/config/AgentCards.kt, AgentSettingsSection.kt   # cartões de ferramenta, painel de confirmação, seção de Ajustes
```

## Decisões

- **D1 — Contrato separado.** `AgentEngine` não herda de `ReasoningEngine`. Sem credencial/origem com suporte (Local, ou provedor ainda sem adaptador), o `AgentController` cai no `ReasoningEngine` de texto exatamente como hoje (AGT-05).
- **D2 — Política central.** Toda execução (chat ou MCP) passa por `ToolPolicy` → `Confirmation`. Não existe caminho que chame `AgentTool.run` sem a política. A UI só implementa `Confirmation`.
- **D3 — Esquema e validação.** Cada ferramenta tem um JSON Schema (subconjunto: object/properties/required/type/enum/min/max/maxLength). O registro valida os argumentos **antes** de `run`; erro vira `tool_result` de erro.
- **D4 — Lista de permitidos de ajustes** (`settings_get/set`): só campos **não secretos** e de baixo risco, cada um com tipo e faixa — `themeDark`, idioma do teclado, `keyboardScalePercent`, `orbSizePx` (110–280), `orbSkin`, `keyboardSkin`, intensidade de vibração, `tapBurstWindowMs`, número da fileira de números, e escolhas de **modelo** (não de credencial). **Fora, sempre**: qualquer campo de `SettingsSecrets`, `custom*Endpoint`/`*HeaderName`, `customModelMirrorUrl`, SSH, origens de autenticação. A lista é uma tabela no código, com teste que **falha** se um campo de `SettingsSecrets` aparecer nela.
- **D5 — Filtro de segredos.** Defesa em profundidade: mesmo sem ferramenta que leia segredos, o `SecretFilter` varre resultados e o registro por padrões (`sk-…`, `AIza…`, `Bearer …`, JWT, blocos PEM, valores iguais a qualquer segredo guardado no cofre — comparação sem imprimir). Teste com sentinelas.
- **D6 — Prompt de sistema.** Fixo, em português, define: papel, que instruções só vêm do usuário no chat, que `tool_result` e texto externo são dados, que ações que alteram algo exigem confirmação, e a lista de ferramentas ativas. Não inclui nenhum segredo.
- **D7 — Skin criada pelo modelo.** O modelo envia `skin_preview`/`skin_install` com `files` (mapa nome → texto SVG/JSON). O app monta o zip em memória com os **nomes canônicos do vocabulário** e passa pelo `SkinPackReader` (a mesma checagem de segurança do import). A folha de contato é um PNG (4 estados × alguns frames; teclado: faces) gerado por `SvgRender`; mostrada ao usuário e, se o modelo aceitar imagem, anexada ao `tool_result`.
- **D8 — Histórico neutro.** O app guarda `AgentMessage` neutro; cada adaptador converte para o formato do provedor (Anthropic: blocos `tool_use`/`tool_result`; OpenAI: itens `function_call`/`function_call_output` da **Responses API** — os modelos GPT-6 exigem a Responses para ferramentas, conferido em 2026-10-24, em vez de `tool_calls`/`role=tool`; Google: `functionCall`/`functionResponse` da `generateContent`). Trocar de origem no meio da conversa só reconverte.
- **D9 — MCP (fase 3).** Porta de entrada alternativa para o **mesmo** `ToolRegistry`/`ToolPolicy`. Servidor HTTP leve (já há OkHttp; para servir, avaliar `ServerSocket` mínimo ou uma lib — **conferir a spec oficial do MCP e a lib antes**; não presumir). Desligado = nenhuma porta aberta. Foreground service curto (notificação) enquanto ligado, token guardado no cofre `keyflow_secure`.
- **D10 — Contexto e custo.** Poda do histórico por tamanho aproximado (mantém o prompt de sistema, o início e as últimas voltas; resultados grandes de ferramenta são resumidos); mostrar ao usuário os **tokens usados** na sessão quando o provedor os informa.

## Skins parametrizadas (fase 3, opcional)

Para o modelo não escrever 45–120 SVGs à mão: `skin_generate_from_template` recebe 1–N SVGs **com expressões** (`{{sin(2*pi*t)*0.1}}`, `t` em 0..1 do loop) e parâmetros de animação; o app expande para os frames com um avaliador **seguro** (aritmética e funções matemáticas fixas, sem acesso a nada), e o resultado segue pelo `skin_preview`/`skin_install`. Casa com o suporte a 120 fps (branch `feature/skins-120fps`).

## Plano de testes

- **Unit (JVM)**: validação de esquema; política (matriz risco × modo × ferramenta ligada); `SecretFilter` (sentinelas, falsos positivos); `AgentLoop` com um motor falso (ordem das chamadas, limite de 12 passos, cancelamento, negação, erro de ferramenta); histórico/poda; lista de permitidos (teste que falha se vazar campo secreto); parse do SSE com `tool_use` (fixtures reais da documentação da Anthropic, **conferidas**); conversão de histórico por provedor.
- **Instrumented**: `AgentController` + cartões e painel de confirmação (Compose); fluxo `skin_preview`→`skin_install` real no `SkinPackStore`; `settings_set` aplicando e desfazendo; seção de Ajustes persistindo.
- **Abuso (suíte dedicada)**: injeção de prompt em `tool_result`, texto ditado malicioso, argumentos fora de faixa, pedido de ferramenta inexistente, tentativa de ler segredo, tentativa de `skin_install` sem confirmação, flood de chamadas.
- **Screenshot**: rotas novas da galeria (cartão de ferramenta, painel de confirmação, seção de Ajustes, o servidor MCP desligado); goldens existentes inalterados.
- **Real**: Anthropic de verdade (chave do Yan, só quando ele autorizar) no emulador; MCP com um cliente real.

## Riscos / mitigação

- **Injeção de prompt** → política central + confirmação com resumo do app + dados ≠ instruções + suíte de abuso (não existe garantia perfeita; por isso nada destrutivo roda sem o usuário).
- **Custo de tokens** → poda de histórico, limite de passos, skins parametrizadas, e o usuário vê o consumo.
- **Provedores com formatos diferentes** → histórico neutro e adaptadores finos testados com fixtures; um provedor por vez.
- **O Composable do chat virar monolito** → mover a lógica para `AgentController`; o `KeyflowPanel` só renderiza.
- **MCP expõe superfície de rede** → desligado por padrão, localhost por padrão, token, taxa, auto-desligamento, confirmação no aparelho.
- **Conflito com o trabalho em andamento no `KeyflowPanel`/Settings** → edições cirúrgicas; tudo novo em `agent/` e arquivos novos de UI.
