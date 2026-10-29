# Síntese de fala (aba Voz) — Tasks

**Spec**: `spec.md` · **Design**: `design.md` · **Contexto**: `context.md`
**Status**: rascunho. Nenhuma task começou. **Fora da v1**. Não toma a vez de frente aberta.

> **Para o agente executor**: leia `spec.md` + `design.md` + este arquivo e `.specs/codebase/TESTING.md`. Uma task por vez,
> na ordem abaixo, testes primeiro, gate verde antes de avançar. Supertonic e VITS entram no catálogo nesta leva (D1 e D2). O nome do VITS é "português", nunca "pt-BR".

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
T1 (contrato, JVM) → T3 (:speech) → T9 (motor Supertonic)
                 ↘ T2 (frontend do VITS) → T11 (motor VITS)
                 ↘ T4 (painel) → T7 (goldens)
                 ↘ T5 (nuvem e Custom, JVM + cliente)
                 ↘ T6 (sistema)
T8 (pin do Supertonic + aceite) e T10 (pin do VITS, nome "português") — não esperam escuta
```

T4, T5 e T6 dependem só de T1. T3 depende de T1. T2 não trava T3 nem T9. T11 depende de T2 e T3. T7 depende de T4. T9 depende de T3. T8 e T10 podem seguir em paralelo com T4.

---

### FAL-T1 — Contrato `[P]`
- **What**: `SpeechRequest`, `SpeechEvent`, `SpeechEngine`, `SpeechEngines.forSource` (FENG-01, FENG-02, FENG-05 no nível do motor).
- **Where**: `app/src/main/java/com/keyflow/speech/`, teste `app/src/test/.../speech/SpeechEngineTest.kt`.
- **Done when**: motor falso emite `Audio`, `FirstAudio` no primeiro buffer e `Done` com a duração das amostras; cancelar não emite `Done`; origem Local sem id de modelo emite `MODEL_MISSING` e não troca a origem. Gate `quick`.
- **Gate**: `pool compilar testDebugUnitTest`.

### FAL-T2 — Frontend de caracteres `[P]`
- **What**: normalizador de dígitos em português e frontend VITS de caracteres (FLOC-03, FPRV-01). `tokens.txt` de teste, minúsculo, no `src/test`. Não baixar o modelo de 71 MB. O rótulo do modelo, quando a task de pin o mostrar, é "português".
- **Where**: `com.keyflow.speech.local`.
- **Done when**: "2" em pt vira a palavra combinada; caractere fora do mapa some; com `add_blank` o id de blank fica entre os outros; frase corta em `.`. Nenhum arquivo do teste cita eSpeak. Gate `quick`.
- **Gate**: `pool compilar testDebugUnitTest`.

### FAL-T3 — Processo `:speech`
- **What**: serviço `exported=false`, AIDL, sessão ORT preguiçosa, ociosidade de 120 s, sem `OfflineTts` e sem segunda `libonnxruntime.so` (FLOC-04, FLOC-05, FLOC-06, FPRV-02). Sem arquivo de modelo, `speak` local devolve `MODEL_MISSING` e não abre socket.
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

### FAL-T8 — Pin do Supertonic e aceite `[P]`
- **What**: uma entrada real do Supertonic 3 em `ModelCatalog.VOICE` (FLOC-01, FLOC-02, FPRV-03). Baixar de novo o ONNX oficial, gravar SHA-256 e tamanho completos, licença no comentário e em `app/licenses/`. Não copiar o prefixo do `context.md` como se fosse o hash. Diálogo de aceite (licença + Attachment A) antes do download; sem aceite, o downloader não é chamado. O VITS é a FAL-T10, não esta task.
- **Done when**: o id do Supertonic baixa só depois do aceite, o SHA confere e o catálogo não lista Kokoro, Piper nem ONNX quantizado. Gate `quick` + `build`.
- **Gate**: `pool compilar testDebugUnitTest assembleDebug`.

### FAL-T9 — Motor Supertonic
- **What**: síntese no `:speech` com os quatro ONNX e o índice Unicode, a partir do código MIT oficial, idioma `pt` (FLOC-03, FLOC-04). Sem `OfflineTts`.
- **Where**: `com.keyflow.speech.local`.
- **Done when**: texto de teste com o índice falso produz ids; arquivo ausente emite `MODEL_MISSING` sem socket; a velocidade da aba chega ao fator `speed`. O modelo de ~398 MB não entra no teste de JVM. Gate `quick`.
- **Gate**: `pool compilar testDebugUnitTest`.

### FAL-T10 — Pin do VITS `[P]`
- **What**: entrada real do VITS Coqui pt em `ModelCatalog.VOICE`, ao lado do Supertonic (FLOC-01, D1). Nome visível `tr("português")` — não "pt-BR". Baixar de novo, gravar SHA-256 e tamanho completos, licença BSD-3 no comentário e em `app/licenses/`. Não copiar o prefixo do `context.md`.
- **Done when**: as duas entradas existem; o nome do VITS é "português"; o SHA confere; Kokoro e Piper continuam de fora. Gate `quick` + `build`.
- **Gate**: `pool compilar testDebugUnitTest assembleDebug`.

### FAL-T11 — Motor VITS
- **What**: síntese no `:speech` com o frontend da FAL-T2 e o `model.onnx` de caracteres (FLOC-03, FLOC-04). `length_scale = 1 / speed`. Sem `OfflineTts`. Sem chamar o modelo de pt-BR.
- **Where**: `com.keyflow.speech.local`.
- **Done when**: o normalizador + frontend da T2 alimentam a sessão; arquivo ausente emite `MODEL_MISSING` sem socket; velocidade 1,0 usa `length_scale` 1. O modelo de 71 MB não entra no teste de JVM. Gate `quick`.
- **Gate**: `pool compilar testDebugUnitTest`.
