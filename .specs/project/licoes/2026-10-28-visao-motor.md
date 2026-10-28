# 2026-10-28 — Motor de visão e Testar (VC-T15)

O Testar da aba Visão recebe a mesma imagem nossa desenhada em Canvas para PaddleOCR, Gemma 4, OpenAI, Anthropic, Google e Custom. A seleção escolhe somente uma origem; os provedores usam o cofre, autorização e clientes já usados pelo Raciocínio. A imagem só sai pela origem de nuvem selecionada e o Testar mostra a nota de envio. Captura de tela e gestos não entram nesta entrega.

## API conferida e contratos

O AAR `com.google.ai.edge.litertlm:litertlm-android:0.16.1` foi inspecionado com `javap`: `Content.ImageBytes(byte[])`, `EngineConfig.visionBackend` e `Conversation.sendMessageAsync(Message, MessageCallback, ...)`. Os bytes do PNG viram `Content.ImageBytes`, junto da instrução; nenhum upgrade do motor. As classes são Java 21: os testes JVM usam backends falsos; os instrumentados conferem as classes reais.

`VisionModelEngine.read/ask` devolve `Text`, `Empty`, `Unavailable` ou `Failed(ReasoningError)`. Um erro descarta os deltas parciais e a UI apresenta somente a causa, sem corpo HTTP ou exceção. PNG/JPEG/WebP limitados a 8 MiB; o bitmap é reduzido a 1024 px no lado maior. Nenhuma imagem/chave entra em log de produção.

## Processo e cancelamento

Gemma roda em `:reasoning`, com guarda `RamGuard.enoughFor` e ociosidade do mesmo núcleo usado para texto. A configuração inclui o encoder e a identidade residente inclui `vision`: um motor de texto sem encoder não serve para imagem. O PNG atravessa a fronteira por caminho privado único em cache, não por bytes no binder; tamanho e caminho são conferidos antes da leitura, depois da guarda de RAM. O cliente apaga o arquivo em sucesso, falha, morte ou cancelamento e trabalha no IO.

`generateRequest` e `cancelRequest` recebem um id por pedido. Cancelar na fila impede leitura/carga; cancelar um pedido antigo não afeta outro. A preparação do backend limpa o cancelamento anterior antes de o núcleo publicá-lo para a nova ficha, evitando apagar um cancelamento que chega na entrada da geração. Fichas de seleção de CPU/GPU e versão do arquivo permanecem as do PR #49. O trabalhador local instala StrictMode para rede; uma regra de arquitetura impede imports de HTTP/download no caminho local.

## Protocolos de nuvem conferidos em 2026-10-28

- [OpenAI: Images and vision](https://developers.openai.com/api/docs/guides/images-vision): Responses com conteúdo `input_text` + `input_image` e data URL; sem imagem, o corpo de texto continua idêntico.
- [Anthropic: Vision](https://platform.claude.com/docs/en/build-with-claude/vision): bloco `image`, `source.type=base64`, `media_type` e `data`.
- [Google: Image understanding](https://ai.google.dev/gemini-api/docs/image-understanding): `inlineData` com `mimeType` e base64.
- Custom: Chat Completions com `image_url.url` em data URL; header apenas do endpoint escolhido. Redirecionamentos não reenviam imagem a outra origem. SSE interrompido ou limitado não vira resposta completa.

## Provas e limites

O coordenador corrigiu a premissa inicial da tarefa: o emulador x86_64 roda LiteRT-LM na CPU. `GemmaVisionDeviceTest#e2bNaCpuLeAImagemDeExemplo` baixa uma única cópia do E2B, confere tamanho/SHA do catálogo e usa o backend de produção com encoder CPU; registra carga, pergunta, resposta e tempo, e limpa arquivos. Só roda quando o método é selecionado explicitamente pelo pool; o CI comum usa eco/fakes, sem pesos. Seus números provam funcionamento, não desempenho/qualidade em aparelho físico. A bancada do coordenador no Vaio continua necessária; `VISION_PROVEN_IDS` e `validatedOnDevice` não são alterados por esta entrega.

Prova executada por `pool instrumentar com.keyflow.reasoning.local.GemmaVisionDeviceTest#e2bNaCpuLeAImagemDeExemplo`: **1/1 aprovado**, em `keyflow_api34`, x86_64, CPU, 5.931 MiB de RAM total (4.575 MiB disponíveis no início). Arquivo de 2.588.147.712 bytes conferido por SHA-256. A imagem Canvas traz “Texto de teste do KeyFlow”; pergunta: “Qual é a palavra final da primeira linha da imagem? Responda somente com essa palavra.” Resposta nas duas fases: **KeyFlow**. Carga fria do backend: **7.900 ms**; primeiro trecho: **36.225 ms**; geração: **37.114 ms**. Pelo `LocalVisionEngine`/AIDL/`:reasoning`, incluindo nova carga e a guarda normal, **44.768 ms**, sem violação do StrictMode de rede. Log local: `/tmp/visao-motor/prova-pipeline-cache/instrumentar.log`. São números de funcionamento no emulador, sem extrapolação para aparelho.

A primeira execução de duas fases revelou um limite real de armazenamento do AVD: cada cache de pesos XNNPACK ocupa 1.010.699.904 bytes; manter os dois junto do modelo na partição de 6 GB causava `SIGABRT` em `weight_cache.cc` durante a segunda carga. A prova agora isola nomes temporários e libera o cache da primeira fase antes de passar pelo serviço (espaço livre medido: 575.090.688 → 1.585.811.456 bytes). O produto não mudou sua política de cache. O teste limpa somente os próprios pesos/caches no `finally`; a ausência desses arquivos foi conferida após o sucesso.

Validação da interface e integração: **78/78 instrumentados** (`VisaoPanelTest`, `VisionModelDeviceTest`, `LocalReasoningEngineTest`, `LiteRtBackendMappingTest`, `ReasoningPanelTest`, `ModelListTest`); **20/20 capturas de Visão** no diff oficial. Foram aprovados somente 12 `config-visao*`; os outros 236 goldens permaneceram com o mesmo hash. Provedores/Custom foram verificados por MockWebServer, sem credencial real. Resultado do gate JVM/lint registrado ao concluir a entrega.
