# Parâmetros dos modelos locais — Tasks

**Spec**: `spec.md` · **Design**: `design.md`
**Status**: Draft. Nenhuma task começou. **Fora da v1**. Não toma a vez de frente aberta.

> **Para o agente executor**: leia `spec.md` + `design.md` + este arquivo e `.specs/codebase/TESTING.md`. Uma task por vez,
> na ordem, testes primeiro, gate verde antes de avançar.

**Goal**: lápis no card do modelo local baixado, modal com os parâmetros do LiteRT-LM 0.16.1, padrão = o comportamento de hoje.

**Tech**: Kotlin · Compose · DataStore · AIDL · LiteRT-LM 0.16.1. **Nenhuma dependência nova.**

## Global Constraints

- **Gradle**: `/srv/dev/ferramentas/pool/bin/pool compilar <tarefas>`. Gates: `quick` = `testDebugUnitTest`; `build` = `assembleDebug`; `lint` = `lintDebug`.
- **Goldens**: `pool telas`. `--approve` copia tudo — restaurar com `git checkout -- verification/golden/…` o que não for desta mudança.
- **Aparelho do Yan**: nunca `connectedAndroidTest`.
- Texto novo: `tr("português")` + `TranslationsEn` / `TranslationsEs` + `TranslationsTest`. `collectAsStateOnMain`. Nada de `runBlocking` novo.
- **Não fabricar API.** Faixas e defaults são os do `spec.md` (AAR conferido em 2026-10-28). Não preencher o sampler nulo com temperatura 1.
- **Commit** em português, um por assunto, sem citar agentes de IA e sem trailers. Ao fechar a task: Status aqui, traceability no `spec.md`, lição em `.specs/project/licoes/`.
- Desvio da spec: registrar na task, não em silêncio.

## Execution Plan

```
T1 (JVM, prefs e faixas) → T2 (AIDL e motor) → T3 (modal) → T4 (goldens) → T5 (contrato da bancada, JVM)
```

T5 não depende de UI e pode seguir T1 se a fila apertar. Não implementa a bancada.

---

### MPR-T1 — Registro e faixas `[P]`
- **What**: `LocalModelParams` + chave `reasoning_local_params` + `FaixaParametros` (MPR-01..06, MPP-01, MPP-06).
- **Where**: `app/src/main/java/com/keyflow/data/LocalModelParams.kt`, o DataStore em `SettingsRepository.kt`, testes em `app/src/test/.../LocalModelParamsTest.kt` e `FaixaParametrosTest.kt`.
- **Done when**: modelo ausente = sampler nulo, teto nulo, pensamento false, threads null; JSON ruim ou `v` ≠ 1 = vazio; `forget` tira o id; cada faixa aceita o extremo e recusa o passo seguinte com a chave de mensagem; `OPTIONAL` desligado descreve o par de hoje; `NONE`/`ALWAYS` não têm interruptor. Gate `quick`.
- **Gate**: `pool compilar testDebugUnitTest`.

### MPR-T2 — AIDL e aplicação no motor
- **What**: `cpuThreads` em `LocalLlmConfig` / `LlmModel` (igualdade); `LocalGenOptions` no `generate`; `LiteRtConfigs` aplica sampler, teto e pensamento por pedido; threads na carga; aquecimento intacto (MPP-02..05). Caracterização: tudo no padrão produz a mesma config de hoje.
- **Where**: `app/src/main/aidl/com/keyflow/reasoning/local/`, `LlmModel.kt`, `LiteRtBackend.kt`, `LocalReasoningEngine` (quem monta o parcel a partir do registro), testes de núcleo com backend falso.
- **Done when**: mudar threads marca recarga e não cancela geração em curso; mudar temperatura não recarrega e o pedido seguinte vê o valor; `samplerMode = 0` não cria `SamplerConfig`; pensamento `2` não altera `opensThink`; construtor do AAR recusado vira `ENGINE` sem a frase inglesa do `require`. `assembleDebug` compila o AIDL. Gate `quick`.
- **Gate**: `pool compilar testDebugUnitTest assembleDebug`.
- **Nota**: se houver aparelho com GPU livre, uma geração com duas sementes pode ser anotada na lição. Não é gate. Não usar o A32 sem `--confirmo-a32`.

### MPR-T3 — Lápis e modal
- **What**: ícone, modal, restaurar/cancelar/salvar, textos pt/en/es, `contentDescription` (MPU-01..05).
- **Where**: `LocalModels.kt` e o composable do card/modal em `ui/config/`, `TranslationsEn.kt`, `TranslationsEs.kt`.
- **Done when**: lápis só em `LITERT_LM` baixado (texto, ou visão Gemma); fala e OCR sem lápis; cancelar não chama `save`; restaurar no formulário volta ao padrão e salvar grava esse padrão; GPU esconde threads; `TranslationsTest` verde. Instrumentado com motor falso abre o modal e lê as descrições.
- **Gate**: `quick` + o instrumentado do modal (`pool instrumentar`, classe nova). `lintDebug` se entrar API nova.

### MPR-T4 — Goldens
- **What**: rota `config-raciocinio-parametros` claro/escuro no `RouteCatalog` (MPU-06). Aprovar só o que for o lápis nos cards de texto baixados e a rota nova.
- **Done when**: `pool telas` e `diff-screens.sh` — Visão e o resto bit-idênticos; as capturas de Raciocínio que mudarem são as do lápis e as duas do modal, vistas nos dois temas.
- **Gate**: `pool telas`.

### MPR-T5 — Contrato com a bancada `[P]`
- **What**: `parametrosDaExecucao` (MPB-01..04) e o formato do objeto `parametros` do resultado. Não cria sub-aba nem corpus. O interruptor "Usar os parâmetros do card" fica especificado para a UI da bancada (BNC); aqui só a função e um teste do JSON sem serial e sem credencial.
- **Where**: pacote da bancada se ele já existir na árvore; senão `com.keyflow.reasoning.local` com o nome do design, e um parágrafo no `design.md` da bancada apontando para cá — sem reescrever a spec da bancada.
- **Done when**: `padrao` ignora o registro; `card` devolve o registro; números explícitos vencem os dois; sampler nulo serializa `"padrao-do-motor"` e `null`, nunca 1,0/40/1,0/0. Gate `quick`.
- **Gate**: `pool compilar testDebugUnitTest`.
