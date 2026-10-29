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
| Qwen2.5 1.5B Instruct (Alibaba) — modelo de texto baixado sob demanda | Apache-2.0 | `Apache-2.0_qwen2.5-1.5b.txt` |
| Qwen3 4B Instruct-2507 (Alibaba) — modelo de texto baixado sob demanda | Apache-2.0 | `Apache-2.0_qwen3-4b-instruct-2507.txt` |
| PaddleOCR PP-OCRv3/v5 em ONNX (PaddlePaddle; conversão de monkt) — modelo de OCR da Visão baixado sob demanda (só no debug até a prova num aparelho) | Apache-2.0 | `Apache-2.0_paddleocr.txt` |
| Supertonic 3 (Supertone) — voz baixada sob demanda (só no debug até o motor falar) | OpenRAIL-M nos pesos (com Attachment A; aceite no download) e MIT no frontend e na inferência adaptados para Kotlin | `OpenRAIL-M_supertonic-3.txt`, `MIT_supertonic.txt` |
| VITS Coqui pt (espelho `csukuangfj/vits-coqui-pt-cv`; origem `neongeckocom/tts-vits-cv-pt`) — voz baixada sob demanda (só no debug até o motor falar) | BSD-3-Clause nos pesos; o código Coqui (MPL-2.0) não entra | `BSD-3-Clause_vits-coqui-pt.txt` |
| Monogramas Supertone e Coqui no card | Desenho próprio; não são os logos oficiais e não indicam endosso | — |
| SmolLM2 1.7B Instruct (Hugging Face) — modelo de texto baixado sob demanda | Apache-2.0 | `Apache-2.0_smollm2-1.7b.txt` |
| DeepSeek-R1-Distill-Qwen 1.5B (DeepSeek, derivado do Qwen2.5) — modelo de texto baixado sob demanda | MIT (e Apache-2.0 do Qwen2.5) | `MIT_deepseek-r1-distill-qwen-1.5b.txt` |
| Logos dos fabricantes de modelo no card (NVIDIA, Hugging Face, DeepSeek, PaddlePaddle): paths de `nvidia.svg`, `huggingface.svg`, `deepseek.svg`, `paddlepaddle.svg` do Simple Icons | CC0 1.0 (os desenhos; as marcas são dos donos) | — (domínio público, sem arquivo) |
| Logo da Nanbeige no card: redesenho vetorial do avatar oficial no Hugging Face | Desenho próprio; a marca e o avatar de referência pertencem à Nanbeige (sem licença aberta declarada para o avatar) | Referência e atribuição abaixo |
| Logo da Qwen no card: path de `packages/static-svg/icons/qwen-color.svg` do lobe-icons (LobeHub) | MIT | `MIT_lobe-icons.txt` |
| AndroidSVG 1.4 (AAR, desenha os SVGs das skins importáveis) | Apache-2.0 | `Apache-2.0_androidsvg.txt` |

Fontes das licenças conferidas em 2026-09-25: `LICENSE` do sherpa-onnx na tag v1.13.8, do ONNX Runtime
e do Whisper no GitHub; a do Parakeet no card do Hugging Face (`license: cc-by-4.0`). Os cards do
Hugging Face dos pesos do Whisper declaram Apache-2.0; o repositório oficial da OpenAI declara MIT para
código e pesos — vale a atribuição das duas, e a aba Docs cita ambas.

## Candidatas de Raciocínio só no debug (2026-10-27)

Não aparecem na Docs/site enquanto `validatedOnDevice = false`, como o OCR ainda não validado. Licenças registradas mesmo assim:

| Modelo | Licença | Arquivo |
| --- | --- | --- |
| FunctionGemma 270M | Termos do Gemma | `Gemma_functiongemma-270m.txt` |
| Qwen2.5-Coder 1.5B | Apache-2.0 | `Apache-2.0_qwen2.5-coder-1.5b.txt` |
| SmolLM3 3B | Apache-2.0 | `Apache-2.0_smollm3-3b.txt` |
| Granite 4.2 3B | Apache-2.0 | `Apache-2.0_granite-4.2-3b.txt` |
| Nanbeige4.2 3B | Apache-2.0 | `Apache-2.0_nanbeige-4.2-3b.txt` |
| Qwen3.5 4B | Apache-2.0 | `Apache-2.0_qwen3.5-4b.txt` |

FunctionGemma: [a documentação oficial](https://ai.google.dev/gemma/docs/functiongemma) permite uso comercial responsável, sujeito aos [Termos do Gemma](https://ai.google.dev/gemma/terms) (2026-04-01), com cópia dos termos, aviso e restrições de uso (3.1/3.2); não é Apache-2.0. A política de uso e o NOTICE acompanham o arquivo de licença. Antes de promovê-lo ao release, incorporar as obrigações ao fluxo de distribuição.

IBM: path e cor `#0F62FE` de [lobe-icons, commit 8b7bbc4](https://github.com/lobehub/lobe-icons/blob/8b7bbc433d38437fa2b0cca58ae896917162b812/src/IBM/components/Mono.tsx), MIT; texto em `MIT_lobe-icons.txt`. Nanbeige: redesenho vetorial próprio do [avatar publicado pela Nanbeige no Hugging Face](https://cdn-avatars.huggingface.co/v1/production/uploads/646f0d118ff94af23bc44aab/GXHCollpMRgvYqUXQ2BQ7.png), conferido em 2026-10-28: barra curva superior, faixa larga e cinco blocos, normalizados no grid 24×24. O PNG de referência não é distribuído no app; o path e a cor `#0B4A45` ficam em `KfIcons.LogoNanbeige` e `ModelVendorLogo`. A marca identifica o fabricante e não indica afiliação ou endosso. Não foi encontrada licença aberta do avatar; a licença Apache-2.0 dos pesos não é atribuída à marca.

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

Conferidos em 2026-10-27 (logos dos fabricantes nos cards de modelo local): os paths do NVIDIA, Hugging Face, DeepSeek e PaddlePaddle (o do card do PaddleOCR) vêm do branch `develop` do
`simple-icons/simple-icons` (`LICENSE.md`: CC0 1.0 Universal); as cores de marca (`76B900`, `FFD21E`, `5786FE`, `0062B0`) são as de `data/simple-icons.json`. A Simple
Icons não tem a Qwen: o path e a cor `#082DFF` vêm de `lobehub/lobe-icons` (licença MIT, "Copyright (c) 2023 LobeHub"). Google e OpenAI reaproveitam os paths
que o app já trazia nos provedores. Os logos são marcas registradas dos donos e só identificam quem fez o modelo.

Conferida em 2026-10-27 (Qwen2.5 1.5B Instruct): `license: apache-2.0` e `gated: false` no card do `litert-community/Qwen2.5-1.5B-Instruct` (commit
2f23bacf8ec78b83dfcd7271c5cb25777b1be5e0) e no do original `Qwen/Qwen2.5-1.5B-Instruct`. O Qwen3 0.6B passou para o commit fed81d17517e6ea64362e917ecf7e5accff12492
do mesmo repositório (mesma licença, `license: apache-2.0`).

Conferida em 2026-10-27 (Qwen3 4B Instruct-2507): `license: apache-2.0` e `gated: false` no card do `litert-community/Qwen3-4B-Instruct-2507` (commit
4d409771b414d86b270ca54d27ffc45032a81142; `base_model: Qwen/Qwen3-4B-Instruct-2507`, `license_link` para o LICENSE do original) e no card do original, também `license: apache-2.0`
e `gated: false`. O arquivo `Apache-2.0_qwen3-4b-instruct-2507.txt` traz esse texto.

Conferido em 2026-10-27 (vision-capture, VC-T2a): `license: apache-2.0` no card do `monkt/paddleocr-onnx` (commit
`7b02d0a30a07ba2b92ad1ff5a8941ae2c633de65`, repositório `gated: false`, sem arquivo LICENSE) e nos cards
`PaddlePaddle/latin_PP-OCRv5_mobile_rec` e `PaddlePaddle/PP-OCRv5_mobile_det`; o `LICENSE` do `PaddlePaddle/PaddleOCR` no GitHub é
Apache-2.0 (a API do GitHub devolve `Apache-2.0`). O arquivo `Apache-2.0_paddleocr.txt` traz esse texto.

Conferido em 2026-10-29 (aba Voz, FAL-T8 e FAL-T10): o `LICENSE` do `Supertone/supertonic-3` no commit
`3cadd1ee6394adea1bd021217a0e650ede09a323` é OpenRAIL-M, com Attachment A e a cláusula 8 (sem endosso). A cópia em
`OpenRAIL-M_supertonic-3.txt` é esse arquivo, sem edição, e a mesma vai para `assets/licenses/` — o diálogo de download
mostra o texto inteiro. O repositório `supertone-inc/supertonic` no GitHub é MIT ("Copyright (c) 2025 Supertone Inc.");
o README do Hugging Face também cita 2026. O código de inferência não é copiado para o app; `MIT_supertonic.txt` só
registra isso. O cartão de `csukuangfj/vits-coqui-pt-cv` (commit `9092b0c5e3717b6c61ae563cfd98dc86cc536b3a`) não declara
licença; o de origem `neongeckocom/tts-vits-cv-pt` declara `bsd-3-clause`. `BSD-3-Clause_vits-coqui-pt.txt` cita essa
origem e o texto BSD-3 com "Copyright (c) Coqui GmbH", sem ano inventado. Os monogramas do card são desenhos nossos.
