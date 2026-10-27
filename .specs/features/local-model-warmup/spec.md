# Aquecimento de modelos locais — requisitos

Escopo: main, fora da v1. Complementa `native-voice-models` e avalia `reasoning-models`.

| ID | Requisito | Prova | Estado |
| --- | --- | --- | --- |
| AQ-01 | Depois da pré-carga do Parakeet, transcrever 1 s de silêncio uma vez por carga; descartar texto | `InferenceServiceCoreTest` | Implementado |
| AQ-02 | Carga, aquecimento, áudio real e descarga serializados fora da interface; resposta real intacta | `InferenceServiceCoreTest`, `InferenceServiceTest` | Implementado |
| AQ-03 | Falha descartável, inclusive OOM Java, não impede uso do modelo; sem repetição automática | `InferenceServiceCoreTest` | Implementado |
| AQ-04 | Sem carregar modelo não selecionado; pedido real que provoca carga não paga outra inferência antes dele | `InferenceServiceCoreTest` | Implementado |
| AQ-05 | Avaliar cada modelo local e evitar trabalho sem oportunidade de reduzir espera | `design.md` | Implementado |
| AQ-06 | Selecionar texto instalado pede pré-carga; aquecimento separado limitado a dois tokens por carga | `ReasoningServiceCoreTest`, `LocalReasoningEngineTest` | Implementado |
| AQ-07 | Card selecionado mostra indicador indeterminado e tempo até pronto, inclusive aquecimento; erro encerra espera | `ModelPreparationTest`, `ModelListTest`, goldens | Implementado |
| AQ-08 | Pré-carga de texto só com RAM livre ≥ max(estimativa, 2048 MB); senão não carrega e o card avisa que carrega no primeiro uso | `ReasoningServiceCoreTest`, `RamGuardTest` | Implementado |

Não há gravação, mudança de origem, download automático nem fallback para rede.
Textos novos da interface passam por `tr`, com inglês e espanhol.
Falha nativa fatal continua isolada nos processos `:inference`/`:reasoning`, com erro e rebind já existentes no cliente.
