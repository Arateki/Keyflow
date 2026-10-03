# Licenças de terceiros

| Componente | Licença | Arquivo |
|---|---|---|
| Space Grotesk (fonte) | SIL OFL 1.1 | `OFL_SpaceGrotesk.txt` |
| Space Mono (fonte) | SIL OFL 1.1 | `OFL_SpaceMono.txt` |
| sherpa-onnx 1.13.8 (AAR, reconhecimento de voz no aparelho) | Apache-2.0 | `Apache-2.0_sherpa-onnx.txt` |
| ONNX Runtime (`libonnxruntime.so`, dentro do AAR do sherpa-onnx) | MIT | `MIT_onnxruntime.txt` |
| Whisper base/small (OpenAI) — modelos baixados sob demanda | MIT (código e pesos, repositório oficial) | `MIT_whisper.txt` |
| Parakeet TDT 0.6B v3 (NVIDIA) — modelo baixado sob demanda | CC BY 4.0 | `CC-BY-4.0_parakeet-tdt-0.6b-v3.txt` |
| LiteRT-LM 0.16.1 (Google; AAR, LLM no aparelho — aba Raciocínio) | Apache-2.0 | `Apache-2.0_litert-lm.txt` (+ avisos das dependências nativas em `THIRD-PARTY_litert-lm-notices.txt`) |
| Gemma 4 E2B (Google) — modelo de texto baixado sob demanda | Apache-2.0 | `Apache-2.0_gemma-4-e2b.txt` |
| Qwen3 0.6B (Alibaba) — modelo de texto baixado sob demanda | Apache-2.0 | `Apache-2.0_qwen3-0.6b.txt` |
| AndroidSVG 1.4 (AAR, desenha os SVGs das skins importáveis) | Apache-2.0 | `Apache-2.0_androidsvg.txt` |

Fontes das licenças conferidas em 2026-09-25: `LICENSE` do sherpa-onnx na tag v1.13.8, do ONNX Runtime
e do Whisper no GitHub; a do Parakeet no card do Hugging Face (`license: cc-by-4.0`). Os cards do
Hugging Face dos pesos do Whisper declaram Apache-2.0; o repositório oficial da OpenAI declara MIT para
código e pesos — vale a atribuição das duas, e a aba Docs cita ambas.

Conferidas em 2026-09-30 (reasoning-models): o `LICENSE` e o `THIRD_PARTY_NOTICE.txt` vêm do próprio AAR do LiteRT-LM
0.16.1; as licenças dos dois modelos de texto, dos cards do Hugging Face (`license: apache-2.0` nos dois). O Gemma 3 1B
NÃO está no catálogo (repositório gated, licença Gemma) — se entrar um dia, precisa dos Termos do Gemma, não da Apache.

Conferida em 2026-10-02 (skin-packs): o `LICENSE` do AndroidSVG vem do repositório (BigBadaboom/androidsvg, Apache-2.0, "Copyright 2013-2018 Cave Rock Software Ltd"); o POM da 1.4 no Maven Central declara a mesma licença. Não há arquivo `NOTICE`.
