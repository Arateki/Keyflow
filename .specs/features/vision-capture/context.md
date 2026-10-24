# Vision Capture — Context (brainstorm 2026-07-01; revisto em 2026-10-24)

Decisões sobre o **modo Visão da orbe**. V1/V2/V4/V5 foram decididas pelo Yan no brainstorm de 2026-07-01. Em
**2026-10-24** o Yan fixou seis decisões que trocam o motor da Visão (V3, V6 e V7–V10 abaixo): o ML Kit sai e tudo o
que roda no aparelho passa a ser **modelo aberto baixado sob demanda**. O que é proposta minha (a confirmar) vem
marcado com ⚠️.

## Decisões do Yan de 2026-10-24 (fixas)

1. Todo modelo local segue a arquitetura dos modelos de voz: o usuário pede, o app baixa pelo **nosso catálogo**
   (`ModelCatalog.kt`, URL fixa + SHA-256) e roda no aparelho. Nada de modelo embutido no APK nem baixado por SDK de
   terceiros.
2. **Nenhum dado sai do aparelho, nem métrica.** Por isso o ML Kit sai da Visão (os SDKs mandam métricas ao Google).
3. Nada de GPL/AGPL/LGPL nem licença "não comercial" no que vai junto com o app.
4. Tradução offline: **reusar o Qwen3 0.6B** que já está no catálogo (LiteRT-LM, processo `:reasoning`). Tradução na
   nuvem fica como **opção**, com a chave do próprio usuário.
5. O modelo de OCR é baixado **só pela aba Visão** das configurações. Até lá, o gesto de captura não faz OCR e o
   usuário vê um aviso que leva à aba Visão (OCR-04).
6. Síntese de fala (aba Voz, feature futura): padrão = modelo neural no aparelho; TTS do sistema e nuvem são opções
   (ver `.specs/features/sintese-fala/context.md`).

> Pesquisa de 2026-07-01 (web), ainda válida para a captura: MediaProjection no Android 14/15 (FGS type
> `mediaProjection`, consentimento por sessão no 15, app-window vs tela inteira); APIs multimodais =
> padrão OpenAI-compatível `chat/completions` com imagem. A parte de ML Kit da pesquisa antiga foi superada pela V3.

## V1 — Conceito: "sentido = contrato, não modelo" ✅ (alinhado com o Yan)
A analogia dos sentidos se sustenta pelo **contrato** (Voz: áudio→texto; Visão: pixels→texto), não pelo tipo de
modelo — igual já ocorre na Voz (whisper-1 dedicado convive com gpt-4o-transcribe multimodal atrás do mesmo
contrato). "Descrição como intermediário para outro modelo" é anti-pattern (perde informação); o padrão certo é
**imagem bruta → multimodal**, viabilizado pela arquitetura de captura abaixo.

## V2 — Recorte do MVP: **ferramental de captura, útil sem IA generativa** ✅ (CONFIRMADO pelo Yan, 2026-07-01)
"Captura de material bruto da tela que poderia ser passado pra um multimodal configurado no futuro; mesmo sem essa
integração ainda é ferramental útil."
- **MVP**: capturar a tela → **OCR** (extrair texto) → **copiar / traduzir** + **copiar o print** para a área de
  transferência. OCR 100% local; tradução local por padrão (V8), nuvem só por opção.
- **Fase 2 (prevista na arquitetura, fora do MVP)**: a captura (imagem bruta) vira insumo de **origem multimodal
  configurável** espelhando a aba Voz — nativos (VLM via gerenciador de modelos + processo próprio) +
  OpenAI/Groq/Custom (`chat/completions` com imagem). "Descrever a tela"/perguntas viram casos dessa fase.
- **Vídeo da tela**: fora do MVP (P3). Mesma sessão MediaProjection gravando; entrega cara e pouco ligada ao fluxo de
  texto.

## V3 — Motor do OCR: PaddleOCR em ONNX, baixado sob demanda ✅ (decisão 1; modelo escolhido ⚠️ por medição)
**Substitui** "ML Kit Text Recognition v2 embutido". Motivos do Yan: telemetria (decisão 2) e modelo dentro do APK
(decisão 1).
- Pipeline do PaddleOCR (detector DB + reconhecedor CTC), modelos **Apache-2.0** em ONNX, rodando no ONNX Runtime que o
  app já carrega (V9). Latino cobre PT/EN/ES.
- **Par escolhido (⚠️, confirmado por medição na VC-T6)**: `monkt/paddleocr-onnx` (HF, `license: apache-2.0` no cartão),
  commit `7b02d0a30a07ba2b92ad1ff5a8941ae2c633de65`: detector `detection/v3/det.onnx` (PP-OCRv3 mobile, 2.429.873 B) +
  reconhecedor `languages/latin/rec.onnx` (PP-OCRv5 mobile latino, 7.862.832 B) + `languages/latin/dict.txt` (1.634 B) =
  **10.294.339 B (~10,3 MB)**. SHA-256 recalculados baixando os arquivos (VC-T2a registra os valores).
- **Plano B (medido, mesma classe de tamanho)**: `deepghs/paddleocr`, commit `e7bd63d9…`: `det/ch_PP-OCRv4_det/model.onnx`
  (4.745.517 B) + `rec/latin_PP-OCRv3_rec/model.onnx` (8.989.686 B) + `dict.txt` (468 B) = 13,7 MB. É o par que o relatório
  de pesquisa recomendou, mas o cartão do repositório diz `license: other` (aviso de "distribuição de modelos" do
  deepghs), **não Apache-2.0**; os pesos são do PaddleOCR (Apache-2.0), mas a cadeia de licença fica menos limpa. Entra só
  se o par escolhido reprovar na medição da VC-T6 (a troca é dado do catálogo).
- Descartados: detector `PP-OCRv5_server_det` (88 MB, pesado demais para o MVP); Tesseract (qualidade pior em tela,
  registrado desde a V3 original).

## V4 — Captura: MediaProjection, consentimento por acionamento ✅ (decorrência do modo efêmero — V5)
- Única rota sem AccessibilityService (D2 do projeto veta AccessibilityService).
- Android 14+: FGS type **`mediaProjection`** somado **após** o consentimento e removido ao fim do fluxo; Android 15:
  token não cacheável → como o modo é **efêmero** (V5), o diálogo do sistema aparece **a cada acionamento** do atalho.
  Fricção conhecida e aceita; a alternativa (sessão de projeção viva) exibiria o indicador de captura permanente na
  status bar — rejeitada por privacidade/UX.
- Consentimento via Activity transparente (`createScreenCaptureIntent` exige Activity); frame por `VirtualDisplay` +
  `ImageReader` com a UI do KeyFlow oculta.
- **Seleção sobre a tela viva** (Yan): o seletor de área abre ANTES da captura, sobre a tela real (retângulo ajustável
  + tela inteira + cancelar); confirmar → captura 1 frame e recorta.

## V5 — Interação: **modo efêmero integrado à gravação** ✅ (Yan, 2026-07-01)
O "modo" não é persistente — existe só **entre a captura e a ação pós-captura**:
1. **Atalho** (`OrbShortcutAction.VISION_MODE`, rajada 1/2/3 toques) aciona o fluxo **mesmo gravando**: a gravação (PTT
   ou Toggle) entra em **pause automático**; em idle segue direto. Exceção deliberada ao gating atual de atalhos
   durante gravação — só para VISION_MODE.
2. Consentimento do sistema → **seletor de área** sobre a tela viva.
3. Confirmar → captura + recorte da área.
4. A orbe **trava** (sem reposição) e entra na fase de **gestos de arrasto com ícones/setas** — mesmo padrão DragArrows
   da gravação, com **3 direções**: **Copiar imagem** · **Copiar texto (OCR)** · **Traduzir texto**. **Cancelar = um
   toque na orbe** — semanticamente uma ação de **retorno ao modo de gravação** (Yan, 2026-07-01; rejeitada a 4ª direção
   de arrasto).
5. Ao copiar texto/traduzir → **estado de "cópia"**: toast de ~3 s com o texto; **tocar no toast abre um input
   efêmero** para manipular o texto; **tap na orbe** (exibindo o ícone da ação) **acelera** o fim — mesmo padrão do
   excluir/desfazer/acelerar existente. ⚠️ proposta: copiar imagem usa o mesmo toast, sem input.
6. Fim do fluxo → orbe **volta ao estado anterior** (IDLE ou o PAUSED que ficou). Se a gravação retomar e for
   transcrita, o clipboard é sobrescrito — **esperado e aceito** (Yan).
- Entrega é **clipboard-only** no MVP (sem "colar no campo").
- Consentimento negado / cancelar em qualquer fase → aborta com aviso e restaura o estado (item 6).
- Atalho ignorado durante SENDING/CANCELLING (estados transitórios da voz).
- **Novo (decisão 5)**: sem o modelo de OCR, soltar em "Copiar texto"/"Traduzir" não faz OCR: mostra o aviso que leva
  à aba Visão e o fluxo encerra pelo epílogo (a captura é descartada; depois de baixar, o usuário captura de novo).

## V6 — Config: a aba Visão ⚠️ (revisto)
A aba **já existe** na barra (`ConfigTab.VISAO`, grupo Capacidades, hoje com o cartão "Em breve"); a "6ª aba" do
desenho original ficou para trás quando a barra foi reorganizada em grupos. Conteúdo do MVP: cartão **Leitura de
texto** (baixar/apagar o modelo de OCR, tamanho, licença), cartão **Tradução** (idioma-destino; motor: no aparelho com o
Qwen3 0.6B ou nuvem por opção; baixar/apagar o Qwen3), nota de privacidade e o stub "Origem multimodal — em breve"
(fase 2). **Gating**: o código atual não gateia as abas de capacidade por modo (a Transcrição e o Raciocínio ficam
sempre habilitadas) — a Visão segue assim, para o usuário poder baixar o modelo antes de ligar a orbe (⚠️ muda o
"habilitada com o modo overlay ativo" original).
O painel antigo (origem Créditos/OpenAI/Google/Anthropic/Custom, catálogo de "modelos de visão", botão "Testar OCR" com
`delay(60)`) contradiz as decisões 1 e 2 e tinha dados fabricados no catálogo (`STATE.md` › "vision-capture — aba Visão
existente × spec"): a VC-T11 o reduz ao conteúdo acima.

## V7 — Modelos só por catálogo, baixados só pela aba Visão ✅ (decisões 1 e 5)
- Entrada nova no `ModelCatalog` (`kind = VISION`, `engineType = OCR`), com URL de **commit** fixo e SHA-256, como os
  modelos de voz; mesmo downloader/`ModelStore` (retomável, SHA conferido). O app **não** carrega modelo no APK nem por
  SDK de terceiros.
- O fluxo de captura **nunca** baixa nem inicia download (regra de arquitetura testável: `com.keyflow.vision` não
  importa o downloader). Falta o modelo → aviso + atalho para a aba (OCR-04, TRD-02).
- Custo de APK da Visão = só o shim nativo do runtime (V9), medido na VC-T2b.

## V8 — Tradução: Qwen3 0.6B local; nuvem opcional ✅ (decisão 4)
- **Padrão**: o Qwen3 0.6B do catálogo (`qwen3-0.6b-litertlm`, 614.236.160 B, Apache-2.0, já em `app/licenses/`), pelo
  `ReasoningEngine`/`:reasoning` que a aba Raciocínio já usa (thinking desligado, descarga por ociosidade de 120 s,
  guarda de RAM). Sem modelo novo, sem decodificador autoregressivo próprio.
- **Ressalva de qualidade/peso**: 0,6B é modesto para texto longo e pesado (586 MB de download; ~1 GB de RAM,
  `recommendedRamMb = 1024`). Texto longo é traduzido em pedaços; o aviso de RAM da lista de modelos vale aqui também.
  Qualidade medida no A32 na VC-T14 (sem número de BLEU inventado: o relatório de pesquisa citou "BLEU > 35" sem fonte).
- **Opção**: nuvem com a chave do próprio usuário (cofre `CredentialStore`; mesmas conexões da aba Raciocínio). Envia
  **só o texto reconhecido**, nunca a imagem; é opt-in explícito e a aba diz isso (TRD-05).
- Descartado por ora: OPUS-MT ONNX (CC-BY-4.0; encoder 52,9 MB + decoder 193 MB, só pares ROMANCE→en; exigiria
  decodificador autoregressivo próprio) — o Qwen3 já está no app e cobre pt/en/es nos dois sentidos.

## V9 — Runtime ONNX: JNI própria sobre a `libonnxruntime.so` do AAR do sherpa ⚠️ (escolha de design; ver design §7)
O AAR do sherpa-onnx 1.13.8 traz a ORT **1.28.2** (`strings`/`nm -D` na `libonnxruntime.so`). Opções comparadas no design:
JNI própria (C, `dlopen`) × `onnxruntime-android` + `pickFirst`. Escolhida a JNI própria: uma só ORT no processo, a do
sherpa (a transcrição local é o núcleo do produto e não pode quebrar por causa do OCR); custo = NDK/CMake no build.

## V10 — Identificação de idioma: pelos dicionários de correção do app, sem modelo novo ⚠️ (escolha de design)
- O app já tem `assets/dict/{pt,en,es}.kfd` (~100 mil palavras cada) do corretor do teclado. A origem é a que tem a
  maior taxa de palavras reconhecidas; poucas palavras ou empate = **indeterminado**, e isso **não bloqueia** a
  tradução: o Qwen recebe "detecte o idioma" (TRD-03).
- **Lingua recusada**: a biblioteca `lingua` (Apache-2.0) tem jar de **80.412.159 B** no Maven Central (1.2.2, conferido
  por HEAD em 2026-10-24) porque embute todos os modelos — violaria a decisão 1 e o APK cresceria ~80 MB.
- **fastText `lid.176.ftz`** (917 KB) descartado: segundo as provas da pesquisa, o cartão no HF marca CC-BY-NC-4.0
  (decisão 3) e a licença do artigo é CC-BY-SA 3.0 (não reconferido aqui; descartado de qualquer forma por precisar de
  biblioteca nativa própria e por haver alternativa sem modelo novo).
- ML Kit Language ID sai junto com o resto do ML Kit.

## Pesquisa de 2026-10-24 — o que foi conferido e o que o relatório errou
Fonte: `modelos-abertos.md` e `provas.txt` da equipe (gem2), reconferidos:
- **Confirmado**: Qwen3 0.6B no catálogo (SHA `555579ff…`, 614.236.160 B, URL no commit `a3c5d805…`); arquivos do
  `monkt/paddleocr-onnx` e do `deepghs/paddleocr` existem nos commits citados e respondem 206 a `Range`.
- **Corrigido**: (a) `deepghs/paddleocr` declara `license: other`, não Apache-2.0 (V3); (b) a licença da voz pt-BR do
  Coqui é BSD-3-Clause, não MPL-2.0 (ver `sintese-fala/context.md`); (c) `onnxruntime-android` + `pickFirst` custa +0,23 MB
  no melhor caso, não +15 MB — o problema real é a divergência de versões (design §7); (d) a Lingua tem 80 MB (V10).
- **Achado que vai além da Visão**: o AAR do sherpa-onnx 1.13.8 contém o eSpeak NG (GPL-3.0) estaticamente ligado
  (`sintese-fala/context.md` e `STATE.md`).
- **Lição do VC-T2 revertido**: o ML Kit custaria +30,0 MB instalados no arm64 (+49%; debug 161,9 → 243,1 MB) e manda
  métricas de uso ao Google (identificador por instalação, dados do aparelho, idiomas); nada disso é opcional dentro do
  SDK. A tradução do ML Kit só existe embutida.

## Fora do MVP (registrado para fase 2/3)
- Origem multimodal da Visão (nativos VLM + APIs) consumindo a captura bruta — reusa o gerenciador de modelos e um
  processo próprio (runtime LiteRT/MediaPipe para VLM). Os VLMs que estavam no catálogo (SmolVLM, Florence) tinham dados
  fabricados e saem na VC-T2a; voltam na fase 2 com URL/SHA reais.
- Perguntar sobre a tela ("o que é esse erro?") e descrição de acessibilidade.
- Gravar vídeo da tela (P3; mesma sessão MediaProjection).
- OCR por API dedicada (Google Cloud Vision etc.) — mandaria a imagem para fora; sem ganho vs VLM.
