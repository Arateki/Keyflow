# Licenças de terceiros

| Componente | Licença | Arquivo |
|---|---|---|
| Space Grotesk (fonte) | SIL OFL 1.1 | `OFL_SpaceGrotesk.txt` |
| Space Mono (fonte) | SIL OFL 1.1 | `OFL_SpaceMono.txt` |
| sherpa-onnx 1.13.8 (AAR recompilado sem TTS, reconhecimento de voz no aparelho) | Apache-2.0 | `Apache-2.0_sherpa-onnx.txt` |
| ONNX Runtime (`libonnxruntime.so`, dentro do AAR do sherpa-onnx) | MIT | `MIT_onnxruntime.txt` |
| Whisper base/small (OpenAI) — modelos baixados sob demanda | MIT (código e pesos, repositório oficial) | `MIT_whisper.txt` |
| Parakeet TDT 0.6B v3 (NVIDIA) — modelo baixado sob demanda | CC BY 4.0 | `CC-BY-4.0_parakeet-tdt-0.6b-v3.txt` |
| LiteRT-LM 0.16.1 (Google; AAR, LLM no aparelho — aba Raciocínio) | Apache-2.0 | `Apache-2.0_litert-lm.txt` (+ avisos das dependências nativas em `THIRD-PARTY_litert-lm-notices.txt`) |
| Gemma 4 E2B (Google) — modelo de texto baixado sob demanda | Apache-2.0 | `Apache-2.0_gemma-4-e2b.txt` |
| Gemma 4 E4B (Google) — modelo de texto baixado sob demanda | Apache-2.0 | `Apache-2.0_gemma-4-e4b.txt` |
| Qwen3 0.6B (Alibaba) — modelo de texto baixado sob demanda | Apache-2.0 | `Apache-2.0_qwen3-0.6b.txt` |
| SmolLM2 1.7B Instruct (Hugging Face) — modelo de texto baixado sob demanda | Apache-2.0 | `Apache-2.0_smollm2-1.7b.txt` |
| DeepSeek-R1-Distill-Qwen 1.5B (DeepSeek, derivado do Qwen2.5) — modelo de texto baixado sob demanda | MIT (e Apache-2.0 do Qwen2.5) | `MIT_deepseek-r1-distill-qwen-1.5b.txt` |
| Logos dos fabricantes de modelo no card (NVIDIA, Hugging Face, DeepSeek): paths de `nvidia.svg`, `huggingface.svg`, `deepseek.svg` do Simple Icons | CC0 1.0 (os desenhos; as marcas são dos donos) | — (domínio público, sem arquivo) |
| Logo da Qwen no card: path de `packages/static-svg/icons/qwen-color.svg` do lobe-icons (LobeHub) | MIT | `MIT_lobe-icons.txt` |
| AndroidSVG 1.4 (AAR, desenha os SVGs das skins importáveis) | Apache-2.0 | `Apache-2.0_androidsvg.txt` |

Fontes das licenças conferidas em 2026-09-25: `LICENSE` do sherpa-onnx na tag v1.13.8, do ONNX Runtime
e do Whisper no GitHub; a do Parakeet no card do Hugging Face (`license: cc-by-4.0`). Os cards do
Hugging Face dos pesos do Whisper declaram Apache-2.0; o repositório oficial da OpenAI declara MIT para
código e pesos — vale a atribuição das duas, e a aba Docs cita ambas.

Conferidas em 2026-09-30 (reasoning-models): o `LICENSE` e o `THIRD_PARTY_NOTICE.txt` vêm do próprio AAR do LiteRT-LM
0.16.1; as licenças dos dois modelos de texto, dos cards do Hugging Face (`license: apache-2.0` nos dois). O Gemma 3 1B
NÃO está no catálogo (repositório gated, licença Gemma) — se entrar um dia, precisa dos Termos do Gemma, não da Apache.

Conferida em 2026-10-02 (skin-packs): o `LICENSE` do AndroidSVG vem do repositório (BigBadaboom/androidsvg, Apache-2.0, "Copyright 2013-2018 Cave Rock Software Ltd"); o POM da 1.4 no Maven Central declara a mesma licença. Não há arquivo `NOTICE`.

Conferido em 2026-10-24 (sherpa-onnx sem TTS): o AAR oficial do sherpa-onnx é compilado com `SHERPA_ONNX_ENABLE_TTS=ON`, que embute o
eSpeak NG (GPL-3.0) nas `libsherpa-onnx-jni.so` e `libsherpa-onnx-c-api.so`. O KeyFlow não usa TTS e não leva nada GPL: o app usa o AAR
recompilado pelo `.github/workflows/sherpa-sem-tts.yml` (mesma tag v1.13.8, mesmas opções e a mesma ONNX Runtime 1.28.2; só o TTS
desligado e a API Kotlin de TTS fora), baixado do release do repositório e fixado por SHA-256 no `app/build.gradle.kts`. O
`SherpaAarSemGplTest` falha se o AAR de `app/libs/` tiver o eSpeak NG.

Conferidas em 2026-10-25 (catálogo com mais dois modelos de texto): `license: apache-2.0` no card do `litert-community/SmolLM2-1.7B-Instruct` e
no do original `HuggingFaceTB/SmolLM2-1.7B-Instruct`; `license: mit` no card do `litert-community/DeepSeek-R1-Distill-Qwen-1.5B` e no
`LICENSE` do `deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B`, que na seção 7 do README avisa que o 1.5B deriva do Qwen2.5 (Apache 2.0) — por isso o
arquivo do DeepSeek traz as duas licenças. Os dois repositórios do `litert-community` são `gated: false`.

Conferida em 2026-10-26 (Gemma 4 E4B): `license: apache-2.0` e `gated: false` no card do `litert-community/gemma-4-E4B-it-litert-lm` (commit
e6a3892a8c22cf87ff99583996149cf3cb32452b) e nas tags do original `google/gemma-4-E4B-it`; é a mesma licença do E2B, não os Termos do Gemma.

Conferidos em 2026-10-27 (logos dos fabricantes nos cards de modelo local): os paths do NVIDIA, Hugging Face e DeepSeek vêm do branch `develop` do
`simple-icons/simple-icons` (`LICENSE.md`: CC0 1.0 Universal); as cores de marca (`76B900`, `FFD21E`, `5786FE`) são as de `data/simple-icons.json`. A Simple
Icons não tem a Qwen: o path e a cor `#082DFF` vêm de `lobehub/lobe-icons` (licença MIT, "Copyright (c) 2023 LobeHub"). Google e OpenAI reaproveitam os paths
que o app já trazia nos provedores. Os logos são marcas registradas dos donos e só identificam quem fez o modelo.
