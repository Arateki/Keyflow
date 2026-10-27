# Aquecimento de modelos locais — desenho

Main, fora da v1. `InferenceServiceCore` extrai a fila de voz para testes JVM;
`SpeechEngine` isola o sherpa-onnx. Serviço injeta executor único, arquivos, RAM e logs.
O núcleo não lê Settings nem catálogo. Após `load`, somente a pré-carga de TRANSDUCER
executa `transcribe(FloatArray(16_000))`; texto descartado e stream liberado no `finally` de `SherpaEngine`.
O callback responde vazio após a tentativa. Repetir a pré-carga não recarrega nem reaquece.
Troca de modelo/idioma ou novo processo permitem outra tentativa.

Texto: a seleção explícita agora pede `IReasoningService.preload`, pelo mesmo bind das gerações.
`ReasoningServiceCore` carrega uma vez e chama `LlmBackend.warmUp` na fila existente.
LiteRT abre conversa separada, envia "Oi", limita `ConversationConfig.maxOutputToken` a **2** e fecha a conversa.
API conferida no `classes.jar` do LiteRT-LM 0.16.1 (`ConversationConfig`, `Conversation.sendMessage`).
Sem callback de delta, sem `ThoughtStripper`: até dois tokens internos do DeepSeek já bastam, não esperamos resposta completa.
A conversa real mantém a configuração anterior, sem esse limite e sem histórico do aquecimento.
Pré-carga mantém o timeout de ociosidade de 120 s; após descarga, nova seleção pode carregar/aquecer novamente.

Pedidos reais durante o aquecimento aguardam a mesma fila; não disputam o motor.
Se o próprio pedido real provoca a carga, ele aquece o caminho sem inferência sintética antes dele.
Exceção ou OOM Java descartável geram somente log de duração/falha, sem erro para quem aguardava a pré-carga.
Não capturamos falha fatal de VM/JNI: o isolamento e rebind existentes continuam necessários.
A guarda de RAM prévia permanece. **Pré-carga de texto tem guarda mais estrita** (`RamGuard.enoughForPreload`):
só carrega ao selecionar se a RAM livre for ≥ max(estimativa do catálogo, 2048 MB); senão o card mostra
"Pouca memória livre agora: o modelo carrega no primeiro uso." e o primeiro pedido real segue a guarda comum.
Motivo (medido no Galaxy A32, 3,7 GB, 1,1–1,5 GB livres, guarda desligada): SmolLM2 1.7B morto pelo lmkd acima de ~1,7 GB de RSS;
DeepSeek R1 1.5B pico 1,74 GB (1º trecho 195 s); Gemma 4 E2B pico 1,73 GB (45 s); Qwen3 0.6B pico 1,66 GB (166 s, resposta ruim).
As estimativas do catálogo (`recommendedRamMb/2`) ficam abaixo desses picos e não foram alteradas aqui (outra tarefa).
Na prática, nesse aparelho nenhum modelo de texto é pré-carregado. PCM temporário de voz: 64.000 bytes, além dos buffers nativos;
texto: uma conversa temporária com os buffers/KV do motor já residente, sem segundo modelo.
Isso não é medição de pico de RAM, energia ou ganho no A32.

| Modelo do catálogo | Decisão e custo/ganho |
| --- | --- |
| Parakeet TDT 0.6B v3 int8 | Aquecer na pré-carga: primeira inferência lenta observada no A32; 1 s de silêncio pode antecipar inicialização. Custo único de CPU/buffers por carga. Ganho físico ainda precisa de medida. |
| Whisper base int8 | Não aquecer: a implementação acrescenta 1000 quadros de cauda (~10 s) por padrão, mesmo ao silêncio curto. Sem ganho demonstrado que justifique outra execução do encoder, CPU/bateria e buffers. Decisão conservadora, revisável com medida. |
| Whisper small int8 | Mesmo padding e modelo maior; manter sem inferência sintética extra pelo mesmo motivo. |
| Qwen3 0.6B LiteRT-LM | Aquecer após seleção explícita: prompt mínimo e dois tokens, preparando prefill/decodificação antes do pedido real. Não carregar ao simplesmente abrir a aba. |
| Gemma 4 E2B LiteRT-LM | Mesmo aquecimento limitado, apenas se passou na guarda de pré-carga (≥ 2048 MB livres) e foi selecionado; custo de uma conversa, sem pesos adicionais. Ganho ainda não medido por modelo. |
| SmolLM2 1.7B LiteRT-LM | Mesmo limite de dois tokens, somente após seleção e guarda de pré-carga (≥ 2048 MB livres; no A32 é morto pelo lmkd acima de ~1,7 GB); não muda a visibilidade de debug nem a falta de validação física. |
| DeepSeek R1 1.5B LiteRT-LM | Mesmo limite de dois tokens, inclusive pensamento interno; não deixa o aquecimento raciocinar até uma resposta longa. Sem ganho físico demonstrado ainda. |

Fonte da decisão Whisper: [implementação oficial v1.13.8](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-recognizer-whisper-impl.h), `actual_frames = min(num_frames + tail_padding_frames, 3000)`;
não é uma janela sempre fixa de 3000 quadros.
`VISION` e `VOICE` estão vazios: não existe motor local OCR/TTS integrado para aquecer.
TextToSpeech do Android pertence ao sistema.

## Card de preparação

`LocalModelActions.prepare` inicia somente a seleção explícita de modelo instalado, fora da thread da tela.
O card recebe `ModelPreparation` observável do cliente; o callback de pronto chega somente após carga + aquecimento.
Indicador indeterminado e tempo monotônico decorrido, sem porcentagem/previsão. Pronto remove a linha;
falha encerra a espera e mostra erro traduzido com possibilidade de selecionar novamente.
`ModelPreparationTracker` usa identidade de pedido para uma resposta antiga não apagar seleção nova.
A UI filtra estado por modelo selecionado e instalado. Estado não é persistido como falso "pronto".
A configuração solta o cliente de voz já criado ao sair de Local ou apagar a seleção, mesmo com modos desligados;
`release` deixa pedidos em andamento terminarem antes de desfazer o bind. Não cria cliente para liberar.
A galeria injeta relógio fixo para os dois estados novos (voz/texto); não inicia modelo nem download.
