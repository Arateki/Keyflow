# Síntese de fala (aba Voz) — Tasks

**Spec**: `spec.md` · **Design**: `design.md` · **Contexto**: `context.md`
**Status**: rascunho. Nenhuma task começou. **Fora da v1**. Não toma a vez de frente aberta.

> **Para o agente executor**: leia `spec.md` + `design.md` + este arquivo e `.specs/codebase/TESTING.md`. Uma task por vez,
> na ordem abaixo, testes primeiro, gate verde antes de avançar. FAL-T8 espera o Yan (D1).

**Goal**: a aba Voz no molde da Visão, com Testar que reproduz áudio e um `SpeechEngine` que um fluxo futuro pode chamar.

**Tech**: Kotlin · Compose · ONNX Runtime já empacotada · `TextToSpeech` · HTTP. **Nenhuma dependência nativa nova.** Não recompilar o sherpa.

## Global Constraints

- **Gradle**: `flock /tmp/keyflow-gradle.lock /srv/dev/ferramentas/pool/bin/pool compilar <tarefas>`. Gates: `quick` = `testDebugUnitTest`; `build` = `assembleDebug`; `lint` = `lintDebug`.
- **Goldens**: `pool telas`. `--approve` copia tudo — restaurar com `git checkout -- verification/golden/…` o que não for desta mudança.
- **Aparelho do Yan**: nunca `connectedAndroidTest`. Escuta no A32 só com `--confirmo-a32`.
- Texto novo: `tr("português")` + `TranslationsEn` / `TranslationsEs` + `TranslationsTest`. `collectAsStateOnMain`. Nada de `runBlocking` novo.
- **Não fabricar pin, voz, preço nem URL.** O que a spec não congela (path da ElevenLabs, corpo do Google) a task confere na doc oficial do dia e anota o link na lição.
- **Commit** em português, um por assunto, sem citar agentes de IA e sem trailers. Ao fechar a task: Status aqui, traceability no `spec.md`, lição em `.specs/project/licoes/`.
- Desvio da spec: registrar na task, não em silêncio.

## Execution Plan

```
T1 (contrato, JVM) → T2 (frontend, JVM) → T3 (:speech, sem modelo no catálogo)
                 ↘ T4 (painel) → T7 (goldens)
                 ↘ T5 (nuvem e Custom, JVM + cliente)
                 ↘ T6 (sistema)
T8 pin do catálogo — bloqueada por D1, não começa com as outras
```

T4, T5 e T6 dependem só de T1. T3 depende de T2. T7 depende de T4.

---

### FAL-T1 — Contrato `[P]`
- **What**: `SpeechRequest`, `SpeechEvent`, `SpeechEngine`, `SpeechEngines.forSource` (FENG-01, FENG-02, FENG-05 no nível do motor).
- **Where**: `app/src/main/java/com/keyflow/speech/`, teste `app/src/test/.../speech/SpeechEngineTest.kt`.
- **Done when**: motor falso emite `Audio`, `FirstAudio` no primeiro buffer e `Done` com a duração das amostras; cancelar não emite `Done`; origem Local sem id de modelo emite `MODEL_MISSING` e não troca a origem. Gate `quick`.
- **Gate**: `pool compilar testDebugUnitTest`.

### FAL-T2 — Frontend de caracteres `[P]`
- **What**: normalizador de dígitos e frontend VITS (FLOC-03, FPRV-01). `tokens.txt` de teste, minúsculo, no `src/test`. Não baixar o modelo de 71 MB.
- **Where**: `com.keyflow.speech.local`.
- **Done when**: "2" em pt vira a palavra combinada; caractere fora do mapa some; com `add_blank` o id de blank fica entre os outros; frase corta em `.`. Nenhum arquivo do teste cita eSpeak. Gate `quick`.
- **Gate**: `pool compilar testDebugUnitTest`.

### FAL-T3 — Processo `:speech`
- **What**: serviço `exported=false`, AIDL, sessão ORT preguiçosa, ociosidade de 120 s, sem `OfflineTts` e sem segunda `libonnxruntime.so` (FLOC-04, FLOC-05, FLOC-06, FPRV-02). Sem entrada no catálogo, `speak` local devolve `MODEL_MISSING` e não abre socket.
- **Where**: manifest, `app/src/main/aidl/`, `com.keyflow.speech.local`.
- **Done when**: `assembleDebug` gera o processo; teste de arquitetura falha se o módulo de fala importar `OfflineTts` ou o downloader; teste com backend falso não abre rede no caminho local. Não editar a URL do sherpa. Gate `quick` + `build`.
- **Gate**: `pool compilar testDebugUnitTest assembleDebug`.

### FAL-T4 — Painel
- **What**: `SYSTEM` no enum; Créditos desligado; tirar cota fictícia e o `delay(60)`; Custom via `CustomProviderFields` mais voz e formato; campo, Testar/Parar e a linha de tempos ligados a um `SpeechEngine` injetável (FUX-01..07, FUX-09, FENG-03..06). A aba só sai de "Em breve" se T3 e T5 (ou T6) já estiverem no mesmo app — senão deixar o gating e anotar.
- **Where**: `FalaPanel.kt`, `Models.kt`, `CustomProviderFields.kt` (parâmetros opcionais), `Settings` se faltar chave de voz/formato do Custom.
- **Done when**: instrumentado com motor falso mostra a linha de tempos; Local sem modelo desliga o botão e mostra o motivo; Sistema mostra a nota de privacidade; OpenAI do painel não recebe `ssoFiveHourFraction`; Visão e Raciocínio seguem sem os campos voz/formato. `TranslationsTest` verde.
- **Gate**: `quick` + `pool instrumentar com.keyflow.ui.config.FalaPanelTest`. `lintDebug` se entrar API nova.

### FAL-T5 — Nuvem e Custom
- **What**: clientes OpenAI, ElevenLabs, Google Cloud TTS e Custom (FCLD-01..07). Conferir na doc do dia o path da ElevenLabs e o corpo do `text:synthesize` antes de gravar. Nota da OpenAI com a data 2027-01-06.
- **Where**: `com.keyflow.speech.cloud`.
- **Done when**: HTTP falso — JSON da OpenAI com `pcm` e sem leitura de cota; 401 vira `UNAUTHORIZED` sem o corpo cru; Custom na URL de speech da Groq usa o mesmo corpo; resposta que não é áudio vira `ENGINE` com a frase da spec. Gate `quick`.
- **Gate**: `pool compilar testDebugUnitTest`.

### FAL-T6 — Sistema
- **What**: `SystemSpeechEngine` sobre um `TextToSpeech` injetável (FSYS-01..05). O instrumento não depende do motor do emulador.
- **Where**: `com.keyflow.speech.system`.
- **Done when**: `onStart` produz `FirstAudio`; idioma ausente produz `TTS_DATA_MISSING`; a origem padrão do `Settings` novo continua `LOCAL`. Gate `quick`.
- **Gate**: `pool compilar testDebugUnitTest`.

### FAL-T7 — Goldens
- **What**: rotas do FUX-08 no `RouteCatalog`. Aprovar só o que for da aba Voz.
- **Done when**: `pool telas` e `diff-screens.sh`. As outras abas ficam bit-idênticas. A rota `config-fala-testar` está parada, sem áudio.
- **Gate**: `pool telas`.

### FAL-T8 — Pin do catálogo `[P]` — bloqueada
- **What**: uma entrada real em `ModelCatalog.VOICE` (FLOC-01, FLOC-02), só depois do Yan registrar a escuta (D1). Baixar de novo, conferir SHA-256 e tamanho, licença no comentário e em `app/licenses/`. Não copiar o prefixo do `context.md` como se fosse o hash completo.
- **Done when**: o id escolhido baixa, o SHA confere e o Testar Local nesse modelo emite áudio no aparelho de teste. Sem decisão D1, esta task não começa e o catálogo segue vazio.
- **Gate**: `quick` + `build`. Escuta no A32 só com `--confirmo-a32`.
