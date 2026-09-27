# Modelos candidatos para stack mobile (TTS / STT / LLM)

Levantamento de modelos pra rodar on-device em Android/iOS. Cada entrada tem
tipo, parâmetros, RAM esperada e link, pra quem for implementar não precisar
pesquisar de novo. Validado nesta sessão = testado de fato neste
repositório; o resto é pesquisa (ver observações).

Formato de cada entrada:
`nome | tipo | parâmetros | RAM esperada | runtime mobile | link | observação`

---

## 1. Multilíngue (inclui português)

### TTS

- **Piper (pt_BR: faber / cadu / edresson / jeff)** | TTS, VITS (não-autoregressivo) | ~20-30M por voz | 15-63MB em disco, RAM ativa ~100-200MB | sherpa-onnx (Android/iOS confirmado em produção) | https://github.com/OHF-Voice/piper1-gpl | **Validado nesta sessão**: RTF 7.8-10x em CPU x86, pt_BR nativo, só vozes masculinas disponíveis oficialmente. Fine-tuning documentado (~1300 frases, precisa GPU NVIDIA — não treina no Steam Deck).

- **Kokoro** | TTS, StyleTTS2 | 82M | 169MB (fp16) / 88MB (int8) em disco, RAM ativa ~300-400MB | sherpa-onnx (suportado, não visto exposto em apps como NekoSpeak/VoxSherpa ainda) | https://github.com/hexgrad/kokoro · https://github.com/thewh1teagle/kokoro-onnx | **Validado nesta sessão**: RTF 1.97x (fp16+4threads) em CPU x86. int8 é mais LENTO que fp16 em CPU sem AVX512-VNNI — testar isso de novo em ARM mobile, pode inverter. Voz feminina pt nativa: `pf_dora`.

- **Pocket TTS** | TTS, flow-matching autoregressivo | 100M | ~176MB ONNX em disco (export comunitário), RAM ativa ~400-600MB estimado | ONNX Runtime via exportador de terceiros, já usado no app NekoSpeak | https://github.com/kyutai-labs/pocket-tts (oficial, PyTorch) · https://github.com/KevinAHM/pocket-tts-onnx-export (export ONNX comunitário, não oficial) | **Validado nesta sessão** (versão desktop): RTF 1.6-2.2x, clonagem de voz funcional (requer aceitar termos + login HF: `kyutai/pocket-tts` é gated). Export ONNX lista "portuguese" e "portuguese_24l" explicitamente. Streaming preservado no grafo ONNX (caches como I/O explícito). https://github.com/lookbe/pocket-tts-unity
https://huggingface.co/lookbe/pocket-tts-onnx-v2/tree/main/portuguese
https://huggingface.co/lookbe/pocket-tts-onnx-v2
http://github.com/asus4/onnxruntime-unity

### STT

- **Whisper (tiny/base/small/medium/large-v3)** | STT, encoder-decoder transformer | 39M / 74M / 244M / 769M / 1.5B | 18MB a ~1.1GB em disco (int8/q5), RAM ativa 2-4x o tamanho em disco | **whisper.cpp** (não faster-whisper/CTranslate2 — sem build mobile oficial) | https://github.com/ggml-org/whisper.cpp | **Validado nesta sessão** (via faster-whisper, proxy de qualidade): `small` RTF 1.4-1.65x em CPU x86, transcrição pt-br 100% correta. 99 idiomas. Pra mobile, migrar pra whisper.cpp (kernels ARM NEON + Core ML no iOS).

- **Parakeet TDT 0.6B v3** | STT, transducer (TDT) | 0.6B | ~650MB int8 em disco, RAM ativa ~1-1.5GB | sherpa-onnx (Android/iOS) | https://github.com/k2-fsa/sherpa-onnx · modelo: https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3 | **Validado nesta sessão**: RTF ~10x em CPU x86, transcrição pt-br 100% correta. 25 idiomas europeus incl. português.

- **Parakeet TDT/CTC 110M** | STT, transducer/CTC | 110M | ~120MB em disco, RAM ativa ~300MB estimado | sherpa-onnx com build QNN dedicado (Snapdragon NPU) | https://github.com/k2-fsa/sherpa-onnx/releases (asr-models-qnn) | Não testado nesta sessão. Variante leve da mesma família do Parakeet 0.6B, com pacote pronto pra NPU Qualcomm — prioridade alta pra testar em Android real.

### LLM — texto

- **Gemma 3 1B** | LLM, dense transformer | 1B | ~720MB (Q4_K_M), RAM ativa mínima ~4GB no aparelho | llama.cpp, LiteRT-LM, ExecuTorch | https://huggingface.co/google/gemma-3-1b-it | Não testado nesta sessão. Recomendado pra aparelhos com 4GB RAM.

- **Gemma 3 4B** | LLM, dense transformer | 4B | ~2.9GB (Q4_K_M), RAM ativa ~8GB no aparelho | llama.cpp, LiteRT-LM, ExecuTorch | https://huggingface.co/google/gemma-3-4b-it | Não testado nesta sessão.

- **Phi-4 Mini** | LLM, dense transformer | 3.8B | ~2.7GB (Q4_K_M), RAM ativa ~8GB no aparelho | llama.cpp, ExecuTorch | https://huggingface.co/microsoft/Phi-4-mini-instruct | Não testado nesta sessão. Melhor raciocínio por parâmetro da categoria (chain-of-thought), mas treino não prioriza português.

- **Qwen3 (0.6B / 1.7B / 4B / 8B...)** | LLM, dense transformer | 0.6B a 32B (família) | varia por tamanho — 0.6B ≈ ~450MB (Q4_K_M) | llama.cpp, MLC-LLM, ExecuTorch | https://huggingface.co/Qwen/Qwen3-0.6B (trocar tamanho no nome) | Não testado. 119 idiomas no pré-treino, mas composição do corpus em pt-br não é divulgada — força multilíngue "genérica", não especializada.

- **Tucano2 (0.5B / 1.5B / 3.7B)** | LLM, dense transformer (arquitetura Qwen, continuado em corpus PT) | 0.5B / 1.5B / 3.7B | 0.5B ≈ ~350MB (Q4_K_M), 3.7B ≈ ~2.3GB | llama.cpp/GGUF (mesma arquitetura Qwen) | https://huggingface.co/collections/Polygl0t/tucano2 | Não testado. Especialista em português (320B tokens de corpus PT + 9.3B sintéticos), não multilíngue genérico. Candidato natural se PT-BR for prioridade sobre generalidade.

### LLM — multimodal (imagem/áudio + texto)

- **Gemma 4 E2B** | LLM multimodal nativo (texto, imagem, áudio) | ~2.3B efetivo | QAT ~3GB RAM; variante texto-only sem embeddings por camada fica <1GB | LiteRT-LM (**suporte oficial Google**, embutido no APK), MediaPipe/AICore, llama.cpp/GGUF (Unsloth) | modelo: https://huggingface.co/google/gemma-4-E2B-it · GGUF: https://huggingface.co/unsloth/gemma-4-E2B-it-qat-mobile-GGUF · LiteRT: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm | Não testado nesta sessão. **Sucessor do Gemma 3n** — lançado abr/2026, licença Apache 2.0 (sem as restrições do Gemma 3). Roda em Android 12+ com 3GB RAM total. 52 tok/s decode via GPU (OpenCL) no Android; 7.6 tok/s em Raspberry Pi 5 (CPU). Tem quant mobile dedicada (`wNa8o8`, camadas de 2-bit).

- **Gemma 4 E4B** | LLM multimodal nativo | ~4.5B efetivo | QAT ~5GB RAM | mesmos runtimes do E2B | modelo: https://huggingface.co/google/gemma-4-E4B-it · LiteRT: https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm | Não testado. Mesma arquitetura do E2B, mais capacidade — ponto de partida se 5GB de RAM for aceitável.

- **Qwen3.5 Small (0.8B / 2B / 4B / 9B)** | LLM multimodal nativo, todos os tamanhos (não é variante à parte) | 0.8B a 9B | 4B em Q4_K_M ≈ 2.5-3GB disco | llama.cpp/GGUF (Unsloth, Bartowski) | https://huggingface.co/unsloth/Qwen3.5-9B-GGUF · https://huggingface.co/bartowski/Qwen_Qwen3.5-2B-GGUF | Não testado. Lançado mar/2026. Contexto de 262K nativo. 9B compete com modelos de 120B em benchmark. Confirmado rodando em iPhone (5-12 tok/s via LM Studio) — Android deve funcionar igual por ser o mesmo llama.cpp, mas não achei relato específico testado. Cobertura de PT-BR não verificada com dado concreto (herda a base multilíngue do Qwen3, ~119 idiomas no pré-treino, mas sem benchmark PT publicado que eu tenha achado).

- **Gemma 3n E2B/E4B** | LLM multimodal | 5B/8B efetivo, roda como ~2B/~4B | RAM ativa ~2-3GB | LiteRT-LM, MediaPipe, llama.cpp | https://ai.google.dev/gemma/docs/gemma-3n | **Superado pelo Gemma 4** acima — mantido aqui só como fallback se o tooling do Gemma 4 ainda não estiver maduro o bastante num runtime específico.

- **MiniCPM-V** | LLM multimodal (encoder de visão + LLM) | encoder 400M + LLM 7.5B | RAM ativa ~8GB | llama.cpp (GGUF), MLC-LLM | https://huggingface.co/openbmb/MiniCPM-V | Não testado. Nível GPT-4V em benchmarks de visão, mas pesado pra mobile e sem dado confiável de robustez em português.

---

## 2. Só inglês (não repetindo o que já apareceu acima)

### TTS

- **Kitten TTS (nano)** | TTS, ONNX (arquitetura não confirmada — usa espeak-ng/misaki para fonemização) | 15M | 25MB em disco (int8), RAM ativa ~100MB estimado | ONNX Runtime — sem SDK mobile oficial ainda (roadmap), mas incluído no app NekoSpeak | https://github.com/KittenML/KittenTTS | **Bloqueado nesta sessão**: dependência `misaki>=0.9.4` não suporta Python 3.14 (o Python deste sistema). Em mobile isso não deveria ser problema — não usa esse Python. Sem treino documentado. Só inglês no release atual.

- **Piper (vozes en_US/en_GB)** | TTS, VITS | ~20-30M por voz | 15-63MB por voz conforme qualidade | sherpa-onnx | https://huggingface.co/rhasspy/piper-voices/tree/main/en | Dezenas de vozes em todas as qualidades (x_low a high), muito mais maduro que a cobertura em pt_BR.

### STT

- **Moonshine** | STT, encoder-decoder | 245M | ~250-300MB estimado em disco | ONNX Runtime / sherpa-onnx | https://github.com/usefulsensors/moonshine | Não testado. Foco em inglês, eficiência declarada equivalente a modelos 6x maiores.

### LLM — texto

- **SmolLM2 1.7B** | LLM, dense transformer | 1.7B | ~1.1GB (Q4_K_M), RAM ativa ~6GB no aparelho | llama.cpp, ExecuTorch | https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct | Não testado. Mais rápido da categoria (~26-32 tok/s em iPhone 17 Pro), otimizado majoritariamente em inglês.

- **Llama 3.2 3B** | LLM, dense transformer | 3B | ~2.2GB (Q4_K_M), RAM ativa ~8GB no aparelho | llama.cpp, ExecuTorch (React Native ExecuTorch suporta nativamente) | https://huggingface.co/meta-llama/Llama-3.2-3B-Instruct | Não testado.

### LLM — multimodal

- **SmolVLM** | LLM multimodal (visão + texto) | variantes 256M/500M/2.2B | 2.2B ≈ ~1.5GB (Q4_K_M) | llama.cpp (GGUF), ONNX Runtime | https://huggingface.co/HuggingFaceTB/SmolVLM2-2.2B-Instruct | Não testado. Focado em eficiência extrema, cobertura de idioma majoritariamente inglês/dados web gerais.

---

## Runtimes mobile (referência cruzada)

- **sherpa-onnx** — TTS (Piper, Kokoro) + STT (Whisper, Parakeet) + VAD + diarização, tudo via ONNX Runtime. Android/iOS/HarmonyOS. https://github.com/k2-fsa/sherpa-onnx
- **whisper.cpp** — só Whisper, mas é o caminho certo pra mobile (não faster-whisper). https://github.com/ggml-org/whisper.cpp
- **llama.cpp / GGUF** — runtime mais portável pra LLM, CPU-first. https://github.com/ggml-org/llama.cpp
- **ExecuTorch** — runtime mobile nativo do PyTorch, sucessor do PyTorch Mobile (descontinuado). Base de 50KB, 12+ backends de hardware. https://github.com/pytorch/executorch
- **MLC-LLM** — compila modelo pra código nativo por hardware via Apache TVM, pode superar llama.cpp em hardware suportado. https://github.com/mlc-ai/mlc-llm
- **LiteRT-LM / MediaPipe** — caminho nativo do Google pra Android, integra com Gemini Nano via AICore. https://ai.google.dev/edge/mediapipe
- **Apple Foundation Models** — framework nativo iOS 18+/26, acesso ao modelo ~3B da Apple direto em Swift. Vantagem de integração, mas preso ao modelo deles (sem controle sobre pt-br).

## Apps de referência (já rodando isso em produção)

- **NekoSpeak** — Android, MIT, roda Kokoro + Piper + Kitten TTS + Pocket TTS num único APK. https://github.com/siva-sub/NekoSpeak
- **VoxSherpa** — Android, roda Piper + Kokoro via sherpa-onnx (testado pelo usuário: só Piper aparece pra download no app hoje).
