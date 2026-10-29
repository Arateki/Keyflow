# Parâmetros dos modelos locais — Design

Requisitos em `spec.md`. Só modelos locais LiteRT-LM. O processo `:reasoning` continua sem ler DataStore (RRUN-02).

## Princípio

Dois tempos, e só um deles recarrega o modelo.

| Tempo | Campos | Onde entra | Recarrega? |
| ----- | ------ | ---------- | ---------- |
| Carga | `cpuThreads` (`null` = `Backend.CPU()`) | `EngineConfig.backend` | Sim, antes do próximo uso. Não corta geração em curso |
| Pedido | sampler, `maxOutputToken`, pensamento | `ConversationConfig` daquele `generate` | Não |

GPU: `Backend.GPU()` não tem thread. O modal esconde threads. Amostrar na GPU **não foi medido** nesta spec (há relato upstream de sampler ignorado na GPU em versões antigas). O app manda o `SamplerConfig` do mesmo jeito; a MPR-T2 anota no aparelho, se a GPU estiver disponível, se a semente muda a saída. Não bloqueia a CPU, que é o caminho de hoje.

## Dados

Chave `reasoning_local_params` no mesmo DataStore de `Settings` (ao lado de `REASONING_LOCAL_BACKENDS`). Não é segredo.

```json
{ "v": 1, "models": {
  "qwen3-0.6b-litertlm": {
    "sampler": null,
    "maxOutputTokens": null,
    "thinking": false,
    "cpuThreads": null
  }
}}
```

`sampler`, quando não é `null`, traz os quatro: `temperature` (número), `topK` (inteiro), `topP` (número), `seed` (inteiro). Os quatro vão juntos porque o construtor do AAR exige `topK`, `topP` e `temperature`. `thinking` só é lido se o catálogo do modelo é `OPTIONAL`; em `NONE` e `ALWAYS` a chave é ignorada.

`LocalModelParams` é imutável, no estilo de `LocalBackendPrefs`: `for(modelId)`, `save(...)`, `forget(modelId)`, `decode`. `decode` de lixo ou de `v` diferente de 1 devolve vazio. Apagar o modelo chama `forget`.

A faixa (MPR-02..05) é função pura `FaixaParametros`, testada na JVM, **antes** de gravar. A UI não grava inválido. O `:reasoning` ainda assim trata exceção do construtor do `SamplerConfig` como `ReasoningErrorKind.ENGINE` com a frase já traduzida que o processo principal mandou — não com a frase em inglês do AAR.

## Fronteira

```
Card (lápis) → modal → LocalModelParams no DataStore
        │
        ▼
LocalReasoningEngine, no generate/preload:
   LocalLlmConfig.cpuThreads     (0 = null)
   LocalGenOptions no generate   (não no preload, não no aquecimento)
        │  AIDL, processo :reasoning não lê DataStore
        ▼
LiteRtBackend
   load:  Backend.CPU(threadCount) ou Backend.GPU()
   generate: ConversationConfig(samplerConfig?, maxOutputToken?, thinkingConfig, extraContext)
```

```
parcelable LocalGenOptions {
    int samplerMode;       // 0 = null (padrão do motor); 1 = usar os quatro
    double temperature;
    int topK;
    double topP;
    int seed;
    int maxOutputTokens;   // 0 = null
    int thinkingMode;      // 0 = desligado (hoje); 1 = ligado; 2 = não mexer (NONE e ALWAYS)
}
```

`LocalLlmConfig` ganha `int cpuThreads` (`0` = automático). Entra na igualdade de `LlmModel`: threads diferentes não reutilizam o motor residente. Amostragem **não** entra nessa igualdade.

`thinkingMode = 2` deixa o `LiteRtConfigs.conversation` como está hoje, inclusive o `promptOpensThink` que o `ReasoningBackends` já lê do catálogo embutido. `1` liga pensamento e `enable_thinking`. `0` é o par desligado de hoje. Quem decide é o processo principal, olhando `ModelSpec.thinking`.

O aquecimento (`warmUp`, teto 2, pensamento do caminho atual) **não** recebe `LocalGenOptions`.

`ReasoningRequest` ganha `GenerationOptions? = null`. `null` significa "use o registro do modelo, ou o padrão se não houver registro". A bancada, no modo comparável, passa um objeto explícito de padrão (`sampler` nulo, teto nulo, pensamento off, threads null) para não herdar o card. Opções com números (temperatura 0, semente fixa da BEX-02) vencem o registro. Isso é o mesmo `GenerationOptions` desenhado na bancada; os nomes dos campos ficam iguais aos de lá para a BNC-T8 não inventar outro tipo.

## UI

O lápis fica no card de `LocalModels` quando `status == INSTALLED` e `engineType == LITERT_LM` e (`kind == TEXT` ou (`kind == VISION` e o card é Gemma com visão)). Gemma com visão ainda não está no catálogo: a condição fica pronta e nenhuma rota de Visão muda agora.

Modal no padrão `SettingsGroup`, um grupo "Amostragem" (interruptor + quatro campos), um campo de limite, o interruptor de pensamento só se `OPTIONAL`, o stepper de threads só se o backend efetivo do card é CPU. Restaurar padrão recoloca o formulário no estado vazio (sampler nulo, limite vazio, pensamento off, threads automático) sem gravar. Salvar grava e fecha. Cancelar fecha sem gravar.

Rótulos (chaves pt, EN/ES na task de UI): "Ajustar parâmetros de {nome}", "Parâmetros de {nome}", "Usar padrão do motor", "Temperatura", "Top-k", "Top-p", "Semente", "Limite de resposta (tokens)", "Pensar antes de responder", "Threads da CPU", "Automático", "Restaurar padrão", "Cancelar", "Salvar", mais as mensagens do MPR-06 e "Este modelo sempre pensa antes de responder." / "A GPU não regula threads."

## Bancada

Função pura, sem UI da bancada:

```kotlin
fun parametrosDaExecucao(registro: LocalModelParams.Entry?, pedido: GenerationOptions?): Efetivos
```

- `pedido == null` → registro ou, sem registro, padrão.
- pedido "comparável" (marca `origem = padrao`) → padrão, registro ignorado.
- pedido com números → esses números; o que vier `null` no pedido cai no padrão, não no card.

O JSON do resultado (quando a bancada gravar) acrescenta, ao lado do que o design da bancada já tem:

```json
"parametros": {
  "origem": "padrao",
  "sampler": "padrao-do-motor",
  "temperature": null, "topK": null, "topP": null, "seed": null,
  "maxOutputTokens": null, "pensamento": false, "threads": null
}
```

`sampler: "padrao-do-motor"` com os quatro `null` é obrigatório: não preencher com 1,0 / 40 / 1,0 / 0, que são só o ponto de partida da tela. Origem `card` repete os números gravados. A chave de comparabilidade ganha `origem` e, se `card`, o conteúdo de `parametros`.

## Testes

| Camada | O quê | Gate |
| ------ | ----- | ---- |
| JVM | faixas, decode, forget, identidade do `LlmModel` (threads sim, sampler não), `parametrosDaExecucao` | `quick` |
| JVM com núcleo falso | recarga só quando threads mudam; pedido em curso não é cancelado | `quick` |
| UI | lápis, modal, restaurar/cancelar/salvar, descrições | instrumentado com motor falso |
| Goldens | `config-raciocinio-parametros` claro/escuro; lápis nos cards de texto já baixados | `pool telas` |
| Aparelho | fora desta spec, a não ser a nota opcional da GPU na MPR-T2 | — |

Nenhuma dependência nova. Sem `runBlocking` novo. Tela que coleta fluxo usa `collectAsStateOnMain`.
