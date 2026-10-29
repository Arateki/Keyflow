# Bancada de modelos — Design

Arquitetura da bancada. Requisitos em `spec.md` (BNC, BCP, BEX, BPT, BHS, BON, BAU, BFL, BRG); tarefas em `tasks.md`.

## Princípio condutor

**Quatro peças que não se conhecem, ligadas por contratos pequenos.**

1. **Corpus** — dados versionados (casos, prompts, esperados). Não tem código.
2. **Executor** — roda um caso por um motor de uma **capacidade** (texto, áudio, imagem) e mede. Não pontua.
3. **Pontuador** — função pura `(caso, saída) → Pontuacao`. Não conhece motor, rede nem aparelho.
4. **Repositório** — guarda e exporta execuções. Não conhece UI.

```
UI (ui/config/Bancada*.kt)            Automação (BancadaDeviceTest + scripts/bancada-aparelhos.sh)
   │ observa BancadaRunner.estado           │ am instrument -e bancada.*   → mesmo BancadaRunner
   ▼                                        ▼
BancadaRunner  (escopo do processo; Parar/Retomar; sobrevive a girar a tela)
   │  plano = Corpus.casos(teste) × modelo × opções
   ▼
ExecutorBancada ──► MotorDeCapacidade ◄── adaptadores: ReasoningEngine (local/online) · Transcritor · OCR/visão
   │  mede (tempo, tokens, RAM, térmico)        │ local: :reasoning (AIDL) · online: OkHttp + SSE
   ├──► Pontuador (por teste)                   
   └──► RepositorioDeExecucoes (arquivos JSON) ──► Exportador (bancada-resultado/1)
```

Pacote **`com.keyflow.bancada`** (sem UI, sem `Settings`, sem provedor concreto — o `BancadaIsolationTest` confere pelos
imports, como o `AuthIsolationTest`): `corpus/`, `pontuacao/`, `executor/`, `fluxo/`, `armazenamento/`. A UI fica em
`ui/config/` (`BancadaTestArea.kt`, `BancadaSubAba.kt`, `BancadaComparar.kt`); o que depende de Android entra por lambda
(relógio, térmico, bateria, RAM, `filesDir`), para o núcleo testar na JVM.

## 1. Corpus (BCP)

```
app/src/main/assets/bancada/v1/
  manifesto.json            { "corpus": 1, "arquivos": [ { "caminho", "sha256", "origem", "autor", "licenca" } ] }
  pontuacao.json  reescrita.json  json.json  acoes.json  contexto.json   (P1)
  traducao.json  ocr.json  fluxo-*.json                                   (P2)
  audio/<id>.mp3|m4a   imagem/<id>.png
```

Um arquivo por teste: `{ "teste": "pontuacao", "versao": 1, "capacidade": "TEXTO", "prompt": { "system": "…", "user": "…{entrada}…" },
"casos": [ … ] }`. Caso (exemplo de `pontuacao`):

```json
{ "id": "pt-03", "idioma": "pt", "entrada": "oi pessoal tudo bem eu queria confirmar a reunião de amanhã às duas da tarde",
  "esperado": "Oi, pessoal, tudo bem? Eu queria confirmar a reunião de amanhã às duas da tarde.",
  "maxTokensSaida": 96, "tokensEntrada": 40 }
```

- O **prompt** é do teste (não do caso) e é parte da versão; `{entrada}` é o único campo substituído. Instrução em português
  para todos os idiomas (o app é PT-first); o idioma muda só o texto do caso.
- `acoes.json` guarda a lista de ferramentas **como o app a enviaria** (nome, descrição, esquema) e, por caso, a chamada
  esperada ou `null` (recusa). O `CorpusIntegrityTest` cruza com o `ToolRegistry` real (BCP-05).
- `contexto.json`: documentos de ~300 e ~2 mil tokens escritos por nós (manual fictício do app, ata, e-mail) com um dado
  plantado; o mesmo documento aparece com a pergunta sobre o começo, o meio e o fim. `tokensEntrada` é uma estimativa
  (caracteres ÷ 4, rotulada) usada só para decidir "não se aplica".
- **Janela do modelo.** O `.litertlm` do FunctionGemma traz `ekv1024` no nome; o catálogo não registra janela. A BNC-T7
  acrescenta `ModelSpec.contextTokens` (opcional, conferido no card/nome do arquivo) e o executor trata `tokensEntrada +
  maxTokensSaida > contextTokens` como "não se aplica" (BNC-03).
- **Áudio** (`fala`, fluxos de voz): trechos do **Common Voice (CC0)** em PT/EN/ES (D2). O processo fica em
  `.specs/features/bancada/corpus-audio.md` (escrito na BNC-T20): versão/idioma do conjunto, critérios (validado pela comunidade
  segundo os campos do próprio conjunto, duração 3–15 s, frase sem dado pessoal), amostragem por **semente fixa** por
  `scripts/bancada-escolher-audio.py` — que recebe o diretório do conjunto **já obtido** pelo canal oficial, conforme os termos
  transcritos na BNC-T1, e não baixa nada sozinho —, e por clipe no `manifesto.json`: id de origem, `CC0-1.0`, data da
  conferência. O decodificador é o `AudioDecoder` (MediaExtractor → 16 kHz mono); a T20 confere que ele lê o formato do
  conjunto e só reencoda para AAC se não ler (registrando a conversão). A transcrição de referência é a do próprio conjunto,
  revisada à mão contra o áudio.
  **Imagem** (`ocr`): PNG gerado por `scripts/gerar-corpus-ocr.py` a partir de textos conhecidos (e de rotas da galeria do
  app), para o esperado ser exato; o script e a semente entram no repositório.
- Orçamento (estimado, medido na T20): ~12 clipes curtos ≈ 0,4–0,7 MB; 10 PNGs ≈ 1 MB; texto ≈ 0,3 MB → **≈ 2 MB**, abaixo dos 3 MB (BCP-04).
  Medido no APK de release na BNC-T20, antes de o áudio entrar.

## 2. Capacidades, opções e métricas (BEX)

```kotlin
enum class Capacidade { TEXTO, AUDIO, IMAGEM }

/** Nulo = padrão do motor = comportamento de hoje (BEX-02). */
data class GenerationOptions(
    val temperature: Double? = null, val topK: Int? = null, val topP: Double? = null, val seed: Int? = null,
    val maxOutputTokens: Int? = null, val thinking: Boolean? = null,
)
data class ReasoningRequest(val system: String? = null, val user: String, val options: GenerationOptions = GenerationOptions())

sealed interface ReasoningEvent {            // + Metrics, emitido antes de Done; os `when` exaustivos ganham um ramo que o ignora
    data class Metrics(val aplicadas: Map<String, String>, val ignoradas: Set<String>,
                       val tokensEntrada: Int?, val tokensSaida: Int?, val tokensPorSegundo: Double?, val primeiroTrechoMotorMs: Long?) : ReasoningEvent
}
```

- `aplicadas`/`ignoradas` são a resposta do motor a "o que você fez com as minhas opções?" (BEX-02): a bancada nunca
  supõe. Um motor que não suporta temperatura devolve `ignoradas = {temperature}` e o resultado fica marcado
  `temperatura=padrão-do-provedor`; o comparador só compara execuções de mesma chave (BHS-05).
- **Local (conferido no AAR `litertlm-android` 0.16.1, `classes.jar`, em 2026-10-28; o comportamento é medido na BNC-T9):**
  `SamplerConfig(topK, topP, temperature, seed)` e `maxOutputToken` em `ConversationConfig`; `ThinkingConfig(enableThinking,
  thinkingTokenBudget)`; `Backend.CPU(threadCount)` e `Backend.GPU()` em `EngineConfig`; `Conversation.getBenchmarkInfo()` →
  `BenchmarkInfo(initTimeInSecond, timeToFirstTokenInSecond, lastPrefillTokenCount, lastDecodeTokenCount,
  lastPrefillTokensPerSecond, lastDecodeTokensPerSecond)` (a flag `ExperimentalFlags.enableBenchmark` e a vida útil do
  valor são a primeira coisa a medir). **Não está provado** que temperatura 0 + semente dê saída idêntica; por isso o BEX-03.
  Nada da 0.18.x foi conferido aqui — a própria bancada é o instrumento para medir essa troca.
- **Fronteira de processo.** O AIDL ganha `LocalGenOptions` (parcelable) em `generate(...)` e `onMetrics(...)` no
  `IReasoningCallback`; `IReasoningService` ganha `unload()` e `restart()` (descarrega e encerra o próprio processo, para a
  **carga fria**: processo novo). Carga quente = `unload()` + `preload()`. O arquivo no cache de páginas do sistema **não é
  controlado** (não há root): fica registrado `memDisponivelMb` no início. Pico de RAM: o `:reasoning` lê o próprio
  `/proc/self/status` (`VmHWM`, zerado antes do caso por `clear_refs` quando permitido; senão amostra `VmRSS` a cada 100 ms e
  guarda o máximo).
- **Online.** Cada motor mapeia as opções que o provedor **documenta** (temperatura, teto de saída, `usage` no fim do
  fluxo); a BNC-T1 cita a página oficial de cada um antes de a BNC-T10 mexer no corpo. O motor da OpenAI por login de plano
  continua enviando o corpo enxuto de hoje (RCL-02: o plano recusa `temperature` e `max_output_tokens`) e devolve
  `ignoradas`. Pensamento online só vira variante onde o provedor documenta. O acionador online é **sempre o usuário, pela
  interface** (chave ou login dele); a automação nossa nunca recebe credencial de provedor (BAU-10).
- **Máquina do executor** (`ExecutorBancada`, relógio e motor injetados): `Preparar → [Determinismo] → (Caso → Pontuar →
  Gravar)* → Concluir`, com **pausa** (térmico/bateria), **cancelar** (propaga ao motor; resultado parcial `incompleta`) e os
  estados de BEX-06. Estados de um caso: `OK | FALHA(OOM|QUEDA|TIMEOUT|ENGINE) | INVALIDO(REDE|LIMITE) | NAO_SE_APLICA`.
  Só `OK` e `FALHA` entram na média; `INVALIDO` e `NAO_SE_APLICA` são contados à parte.
- **Térmico/bateria**: `PowerManager.getCurrentThermalStatus()` (API 29+, com guarda de versão — o `lintDebug` cobra) e
  `BatteryManager`. Limites são constantes nomeadas (30%, `MODERATE`, 10 min), a ajustar na BNC-T19.
- **Tempo estimado (cálculo, não medição):** ~65 casos de texto × ≤ 128 tokens ≈ 8 mil tokens por modelo; a 5 tok/s
  são ~28 min, a 20 tok/s ~7 min, mais o prefill do `contexto`. Por isso `fumaca` (≤ 10 min) e `completo` (manual ou pré-release).

## 3. Pontuadores (BPT)

Interface `Pontuador { fun pontuar(caso: Caso, saida: String): Pontuacao }`; `Pontuacao(nota: Double, criterios: Map<String, Double>,
marcas: Set<Marca>)`. Todos usam a mesma `Normalizacao` (NFC, minúsculas, remoção de pontuação e espaços repetidos,
apóstrofo preservado; números como no esperado).

| Teste | Critérios (peso) | Algoritmo |
| ----- | ---------------- | --------- |
| `pontuacao` | preservação (0,5), pontuação+maiúsculas (0,5) | 1 − WER (Levenshtein por palavra) normalizado; F1 sobre pares (posição da palavra, sinal `. , ? ! :`) + F1 sobre palavras iniciadas em maiúscula |
| `reescrita` | fatos (0,4), limite (0,2), sem invenção (0,2), idioma (0,2) | fatos = fração de `fatosObrigatorios` presentes (normalizados); limite = `len ≤ max`; invenção = todo número/nome próprio da saída aparece na entrada; idioma pela lista de palavras funcionais |
| `traducao` (P2) | termos (0,35), idioma (0,2), só tradução (0,15), chrF (0,3) | chrF (n-gramas de caracteres, β=2) contra a referência; "só tradução" = uma linha, sem prefixo de comentário da lista |
| `json` | válido (0,2), esquema (0,2), campos (0,6) | `org.json` (o mesmo dos testes do agente); datas ISO e números normalizados; campo extra não penaliza, faltante sim |
| `acoes` | ferramenta (0,4), argumentos (0,6) | JSON canônico (chaves ordenadas) do `{nome, args}`; caso `null`: saída vazia/`{"recusa":…}` = 1; qualquer chamada = 0; JSON ilegível = 0 |
| `contexto` | detalhe (1,0) | o valor normalizado aparece na resposta e nenhum "distrator" plantado aparece |
| `fala` | 1 − WER | WER/CER sobre texto normalizado; RTF = tempo ÷ duração |
| `ocr` (P2) | 1 − CER | CER de Levenshtein por caractere, espaços normalizados |

`acoes` tem **um** prompt para todos (lista de ferramentas em JSON + "responda só com JSON"), que é o que um modelo sem
chamada de ferramenta nativa consegue — por isso é comparável entre local e online. O FunctionGemma, que tem formato
próprio, entra com **variante de prompt** (`prompt: "functiongemma"`) gravada no resultado; a comparação com os demais fica
marcada. Chamada **nativa** de ferramenta online (AgentEngine) é variante futura, fora da média.

**Por que sem LLM-juiz** (BPT-10): (1) não é determinístico entre versões do juiz; (2) custa dinheiro e manda texto do corpus
a um terceiro; (3) é circular quando o juiz concorre com o julgado; (4) todos os critérios acima são verificáveis por regra.
O preço é não medir estilo/tom — deliberado.

## 4. Armazenamento e exportação (BHS)

`filesDir/bancada/execucoes/<AAAAMMDDThhmmss>-<modelo>-<backend>.json`, escrita atômica (arquivo `.tmp` + `rename`),
fora do backup (`data_extraction_rules`/`fullBackupContent` já excluem `models/`; ganha `bancada/`). Retenção: 20 por
modelo+backend (apaga as mais antigas completas; as `incompleta` mais velhas de 7 dias). Na abertura, uma execução sem
marca de fim é regravada como `incompleta` (BEX-10).

```json
{ "esquema": "bancada-resultado/1", "id": "…", "iniciada": "2026-10-28T14:03:11Z", "estado": "completa",
  "aparelho": { "fabricante": "samsung", "modelo": "SM-A325M", "soc": null, "abi": "arm64-v8a", "ramTotalMb": 3700, "sdk": 33 },
  "app": { "versionName": "1.0.0", "versionCode": 1, "build": "debug", "commit": "e5aa0e7" },
  "motor": { "nome": "litert-lm", "versao": "0.16.1" }, "corpus": 1,
  "modelo": { "id": "qwen3-0.6b-litertlm", "sha256": "c5f39b41…", "backend": "CPU", "threads": 4, "pensamento": false },
  "opcoes": { "aplicadas": { "temperature": "0", "seed": "42" }, "ignoradas": [] },
  "cargas": { "friaMs": 2310, "quenteMs": 640, "ramPicoMb": 980, "memDisponivelMb": 1210 },
  "testes": [ { "teste": "pontuacao", "versaoTeste": 1, "nota": 0.81, "n": 12, "invalidos": 0, "naoDeterministico": false,
      "primeiroTrechoMsP50": 420, "totalMsP50": 3100, "tokensPorSegundoMedia": 11.8,
      "casos": [ { "id": "pt-03", "estado": "OK", "nota": 0.9, "criterios": { "preservacao": 1.0, "pontuacao": 0.8 },
                   "saida": "…", "primeiroTrechoMs": 410, "totalMs": 2900, "tokensEntrada": 41, "tokensSaida": 33,
                   "termico": 0, "bateriaPct": 78 } ] } ] }
```

Os valores acima são **ilustrativos**. Sem serial, Android ID, conta, chave ou cabeçalho (BHS-02). A **chave de
comparabilidade** é `(corpus, teste+versaoTeste, opcoes.aplicadas, backend, pensamento)`; o motor e o aparelho ficam de
fora de propósito (são as variáveis que a régua compara). O **resumo agregado** de `verification/bancada/` é o mesmo JSON sem
`casos[].saida` e sem `criterios` por caso.

## 5. UI (BNC)

```
┌ Testar ───────────────────────────────────────────────┐
│ ‹ Rápido │ Pontuação │ Reescrita │ JSON │ Ações │ … ›   │  ← faixa rolável; desabilitada = cinza + motivo
│ Mede se o modelo põe pontuação sem mudar as palavras.  │
│ [ Rodar ]   Rodar a bateria (≈ 24 min)                 │
│ ▓▓▓▓▓░░░░░ caso 6 de 12                                │
│ Nota 0,81 · 12 casos · 1º trecho 0,4 s · 11,8 tok/s    │
│ RAM 0,98 GB · CPU · pensamento off · 28/10 14:03  ▲0,04│
│ Histórico ▸ 28/10 0,81 ▲ · 27/10 0,77 · …              │
│ Casos ▸ pt-03  0,90  “Oi, pessoal, tudo bem?…”         │
└────────────────────────────────────────────────────────┘
```

- `BancadaRunner` é um objeto de **escopo do processo** (não do `rememberCoroutineScope`): sair da aba ou girar a tela não
  cancela uma bateria de 20 min; **Parar** cancela. A UI só coleta o `StateFlow<EstadoBancada>` com
  `collectAsStateOnMain` (convenção do `ArchitectureRulesTest`); sem `runBlocking`.
- Aviso de custo (BON-01): diálogo antes de `Rodar` quando a origem é online; mostra chamadas, tokens estimados, teto e
  custo máximo (se houver preço). Perfil reduzido por padrão no login por assinatura.
- Reuso: `PrimaryButton`, `SettingsGroup`, `SettingSub` e a paleta; a faixa de sub-abas é nova (`BancadaSubAbas`, rolável, no estilo do `Segmented`); o **Rápido** é o `ReasoningTestArea`
  de hoje, sem tocar nas tags nem nos tempos. A faixa aparece em todas as 20 rotas `config-raciocinio*` → 40 goldens mudam **por
  desenho** (BNC-06); revisar nos dois temas e aprovar só esses (`--approve` copia tudo: restaurar o resto).
- Transcrição e Visão: a mesma faixa, alimentada pelo adaptador de cada capacidade (BNC-08).

## 6. Fluxos (BFL)

```kotlin
data class Etapa(val id: String, val entrada: Capacidade, val saida: Capacidade, val teste: String)
data class Fluxo(val id: String, val etapas: List<Etapa>)      // voz = [fala, pontuacao]; visao = [ocr, contexto]; agente = [fala, acoes]
```

A etapa **reusa o teste isolado** (mesmo pontuador e mesmo prompt); o fluxo só troca a **entrada**: em vez do texto ideal do
caso, a saída da etapa anterior. Cada caso de fluxo guarda o par (entrada ideal, entrada real) e as duas notas — a diferença é
a perda por propagação. Os dois modelos (por exemplo `:inference` e `:reasoning`) não ficam residentes juntos se a RAM não
cobrir (BFL-04): primeiro roda-se a etapa 1 para todos os casos, descarrega-se, depois a etapa 2 sobre as saídas gravadas.
Isso também evita o produto cartesiano: etapa 1 roda **uma vez por modelo de STT**, etapa 2 roda **uma vez por modelo de LLM**
sobre a saída guardada de cada STT (BFL-06 limita a quais STT).

A aba Fluxos mantém seus três cartões; cada um ganha uma linha somente leitura "melhor medido neste aparelho" (a maior nota
da etapa entre os modelos com histórico comparável) e um atalho para a bancada. O editor visual de nós continua fora de escopo.

## 7. Automação nos aparelhos (BAU)

```
scripts/bancada-aparelhos.sh --aparelhos vaio,tab[,a32 --confirmo-a32] --perfil fumaca|completo [--modelos id,id] [--baixar] [--rotulo vX.Y.Z]
  1. APK: assembleDebug assembleDebugAndroidTest (pool compilar; confere onde o APK parou, ou usa o artefato `apks` do CI do mesmo commit)
  2. por aparelho (um por vez, flock /tmp/keyflow-aparelho-<serial>.lock, ANDROID_SERIAL explícito):
     a. pré-voo: adb presente? bateria ≥ 30% ou carregando? térmico? RAM livre?  (senão: pula e diz por quê)
     b. adb install -r app + teste (nunca desinstala; o teclado ativo não muda)
     c. adb shell am instrument -w -r -e class com.keyflow.bancada.BancadaDeviceTest -e bancada.executar true -e bancada.modelos … \
          -e bancada.testes … -e bancada.perfil … -e bancada.rotulo … com.arateki.keyflow.test/androidx.test.runner.AndroidJUnitRunner
     d. recolhe os JSON (`adb exec-out run-as com.arateki.keyflow cat files/bancada/execucoes/…`; app de debug, `run-as` vale)
  3. agrega → verification/bancada/<rotulo>/<aparelho>.json (sem saídas) e imprime a tabela
scripts/bancada-comparar.py ANTES.json DEPOIS.json [--tol-nota 0.05 --tol-tempo 0.25]   → tabela; saída 1 se nota cair além da tolerância
```

- **Só modelos locais** (BAU-10): `--modelos` com id de provedor online é recusado pelo script e pelo `BancadaDeviceTest`; nenhum
  segredo nem login passa pelo script.
- **Etapa antes da tag `app-vX.Y.Z`** (D1, BAU-09): no commit candidato, `--perfil completo --rotulo vX.Y.Z` nos 3 aparelhos grava
  `verification/bancada/vX.Y.Z/{a32,tab,vaio}.json` e um `atestado.json` (rótulo, `versionName`, commit testado, corpus, motor,
  aparelhos, data). `scripts/bancada-atestado.py vX.Y.Z` o valida (esquema, 3 aparelhos, `versionName` = rótulo, nenhuma mudança em
  `app/src/main` entre o commit testado e o que vai receber a tag, `bancada-comparar.py` sem regressão contra a versão anterior).
  A tag é criada à mão, depois. O `versionName` seguir a tag é a dependência BNC-T30: semântica a partir de `app-v0.1.0`, com o `versionCode` derivado da tag (`MAJOR×1 000 000 + MINOR×1 000 + PATCH`), porque ele nunca pode diminuir.
- Os seriais dos aparelhos ficam num arquivo de configuração **local, fora do git** (`.equipe/` não vai para o repositório); os de
  depuração sem fio entram por `adb connect <ip>:<porta>`, que muda a cada sessão — o script aceita `--serial nome=ip:porta`.
- O A32 é o teclado do Yan: rodar **só combinado e carregando** — o `am instrument` reinicia o processo do app (o teclado some
  um instante) e o `:reasoning` ocupa RAM por minutos. Por isso `--confirmo-a32`.
- Modelos: o `BancadaDeviceTest` usa o mesmo `ModelStore`/`ModelDownloader` do app; com `--baixar` baixa o que falta (Wi‑Fi,
  SHA-256), sem ele marca "ausente". Modelo barrado pela guarda de RAM (`RamGuard`) = "não se aplica".
- Candidatas (`validatedOnDevice = false`) só existem em build de debug (`ModelCatalog.visibleIn`): a bancada de automação
  roda em debug, então as enxerga — é o caminho da BRG-02.
- Não usa o pool para *rodar* nos aparelhos (eles só são alcançáveis pelo adb do servidor); o pool serve para **compilar**. O
  emulador e `pool instrumentar` rodam o `BancadaDeviceTest` só na versão **motor falso** (BAU-08).

## 8. Onde cada coisa roda (CI, pool, aparelhos)

| Camada | O quê | Onde | Gate |
| ------ | ----- | ---- | ---- |
| Lógica | pontuadores, corpus, esquema, runner com motor falso, comparador (Python) | JVM / `scripts/tests` | `quick` no `checks.yml` (obrigatório) |
| UI | sub-abas, aviso de custo, histórico, exportar | emulador com motor falso | `full` no `checks.yml` + goldens `config-raciocinio*` |
| Motor real, rápido | 1 modelo pequeno (opt-in), só para checar o encanamento | `pool instrumentar` (emulador) — não gera número válido | manual |
| Motor real, medida | bateria nos 3 aparelhos | `scripts/bancada-aparelhos.sh` a partir do servidor | **manual; etapa obrigatória antes da tag `app-vX.Y.Z` (D1)** |

**D1 (opção A, decidida):** a bancada não é um job do CI — o CI não alcança os aparelhos. Ela é a etapa manual do procedimento de
release: (1) o `versionName` do commit candidato bate com a tag a criar; (2) `bancada-aparelhos.sh --perfil completo --rotulo vX.Y.Z`;
(3) `bancada-atestado.py vX.Y.Z` verde; (4) só então a tag `app-vX.Y.Z`. O procedimento entra em `TESTING.md` na BNC-T29.

## 9. Riscos e ideias descartadas

- **Determinismo não provado** (BEX-03) e **cache de páginas** não controlado: a bancada mede e registra, não promete.
- **Tempo no A32** (3,7 GB, ~1,1–1,5 GB livres): modelos de 1,5 B+ podem não caber → "não se aplica" legítimo; o A32 é o
  aparelho que mais vai ver `fumaca`.
- **Corpus pequeno = nota ruidosa**: 10–20 casos por teste dão tendência, não estatística; as comparações usam tolerância
  (`--tol-nota`) e o relatório mostra `n`. Aumentar o corpus sobe a versão (BCP-06).
- **Descartado**: LLM-juiz (BPT-10); serviço em primeiro plano para bateria longa (risco de política da loja e de
  permissão nova na release; a bateria roda com a tela ligada, e o automatizado roda sob `am instrument`); rodar modelos
  reais no emulador do CI (número não representa o aparelho; 600 MB por rodada); executar código/comando do modelo (ver §10).

## 10. Futuro (cortado por ora, decisão do Yan de 2026-10-28)

Fica registrado para quando os usos amadurecerem; nada disto entra nas tasks.

- **Terminal** (volta com a aba Computador, hoje sem motor nem spec): pedido → comando, pontuado por regra — comando dentro de um
  conjunto de equivalentes (regex normalizada), pedido destrutivo espera **recusa**, saída que casa um padrão perigoso da lista vale 0
  e leva a marca `perigoso`. Nunca executa o comando.
- **Código** (volta quando houver uso real de código gerado no app): "passa casos de teste" exige executar a saída, o que o app não
  faz (o `agent-harness` o exclui). Só no host: o app guarda a saída e um script a roda num sandbox sem rede, na automação.
- **TTS→STT→WER** (ida e volta) quando a síntese de voz tiver motor.



## Imagens e reconhecimento de objetos (BNC-T24/T31)

O corpus usa imagens reproduzíveis, com o caminho, origem, licença e SHA-256 no manifesto; os arquivos não são enviados a provedores até a confirmação da rodada. O teste OCR compara o texto reconhecido com a referência por CER. O teste Objetos pede uma lista de rótulos, normaliza sinônimos pt/en para um vocabulário fixado no corpus e mede precisão, revocação e F1 por conjuntos; cada rótulo desconhecido conta como falso positivo. As imagens procedurais e suas referências são versionadas juntas.

As etapas de imagem recebem bytes e metadados tipados e retornam texto ou erro, sem depender da UI. O adaptador OCR utiliza o motor local; o adaptador de visão utiliza `VisionModelEngine` para o modelo local ou o provedor selecionado. O executor conserva o cancelamento e nunca muda de origem como fallback. A faixa da Visão mostra apenas capacidades suportadas e preserva o teste rápido.
