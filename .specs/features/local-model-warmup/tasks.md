# Aquecimento de modelos locais — tarefas

## AQ-T1 — Aquecer o Parakeet na pré-carga (main, fora da v1)

- **Status:** implementado; falta só a medida no A32 (coordenador).
- **What/Where:** `InferenceServiceCore`, `InferenceService`, `SherpaEngine`; fila existente,
  inferência descartável de 1 s, uma tentativa por carga e sem resposta visível.
  Pré-carga explícita de texto via AIDL com dois tokens; indicador indeterminado e tempo nos cards selecionados.
  Galeria e goldens de voz/texto em preparação, sem alterar os goldens existentes.
- **Requirements:** AQ-01..07.
- **Done when:** testes de carga/duplicação/concorrência/falha passam; todos os unitários e lint pelo pool;
  instrumentados afetados; decisões por modelo e limitações registradas.
- **Tests/Gate:** `pool compilar testDebugUnitTest`, `pool compilar lintDebug`,
  `pool instrumentar InferenceServiceTest LocalTranscriberDeviceTest LocalReasoningEngineTest ModelListTest LocalPanelTest ReasoningPanelTest`;
  `pool telas` usa o fluxo `verify-screens.sh` → `diff-screens.sh`; benchmark no emulador se viável.
