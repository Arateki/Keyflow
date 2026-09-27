# Vision Capture — Context (brainstorm 2026-07-01)

Decisões do brainstorm com o Yan sobre o **modo Visão da orbe**. V1/V2/V4/V5 decididas pelo Yan
(recorte + interação efêmera detalhada em 2026-07-01); V3/V6 e os pontos marcados ⚠️ dentro de
V5 são propostas minhas a confirmar antes/durante a execução.

> Pesquisa (2026-07-01, web): ML Kit Text Recognition v2 (on-device, Latin/PT, offline);
> ML Kit Translation (pares baixáveis ~30 MB, on-device); MediaProjection no Android 14/15 (FGS
> type `mediaProjection`, consentimento por sessão no 15, app-window vs tela inteira); VLMs
> pequenos on-device (Gemma 3n via MediaPipe LLM Inference, SmolVLM, Moondream) — outra stack de
> runtime (LiteRT/MediaPipe), pesados; APIs multimodais = padrão OpenAI-compatible
> `chat/completions` com imagem (OpenAI, Groq/Llama-4-vision, self-hosted).

## V1 — Conceito: "sentido = contrato, não modelo" ✅ (alinhado com o Yan)
A analogia dos sentidos se sustenta pelo **contrato** (Voz: áudio→texto; Visão: pixels→texto),
não pelo tipo de modelo — igual já ocorre na Voz (whisper-1 dedicado convive com gpt-4o-transcribe
multimodal atrás do mesmo contrato). "Descrição como intermediário para outro modelo" é
anti-pattern (perde informação); o padrão certo é **imagem bruta → multimodal**, viabilizado pela
arquitetura de captura abaixo.

## V2 — Recorte do MVP: **ferramental de captura, útil sem IA generativa** ✅ (proposto e CONFIRMADO pelo Yan)
"Captura de material bruto da tela que poderia ser passado pra um multimodal configurado no
futuro; mesmo sem essa integração ainda é ferramental útil." Confirmado em 2026-07-01: "pode ir
na primeira opção das coisas nativas" — MVP 100% nativo/offline, sem provedores de API.
- **MVP (tudo nativo/offline, sem chave de API)**: capturar a tela → **OCR** (extrair texto) →
  **copiar / colar no campo / traduzir** + **copiar o print** para a área de transferência.
- **Fase 2 (prevista na arquitetura, fora do MVP)**: a captura (imagem bruta) vira insumo de
  **origem multimodal configurável** espelhando a aba Voz — nativos (VLM via gerenciador de
  modelos + processo `:inference` da feature `native-voice-models`) + OpenAI/Groq/Custom
  (chat/completions com imagem). "Descrever a tela"/perguntas viram casos dessa fase.
- **Vídeo da tela**: fora do MVP (P3). Tecnicamente é a mesma sessão MediaProjection gravando;
  encaixa depois sem re-arquitetura, mas é a entrega mais cara e menos ligada ao fluxo de texto.
Variante confirmada: MVP com OCR + tradução + copiar print (rejeitados: só-OCR menor; e já
incluir provedores de API no MVP).

## V3 — Engines nativos do MVP ⚠️ ASSUMIDA (recomendação)
- **OCR**: ML Kit Text Recognition v2, **bundled** (`com.google.mlkit:text-recognition`, ~4 MB no
  APK) — funciona offline e também em **sideload sem Play Services**; unbundled (via Play
  Services) economiza APK mas quebra no sideload. Latin cobre PT/EN/ES.
- **Tradução**: ML Kit Translation on-device com **pacotes de idioma baixáveis** (~30 MB/par,
  gerenciados pelo `RemoteModelManager` do ML Kit) — mini-gerenciador na config (padrão de
  "baixar modelo" que o app já adota na Voz). Idioma de origem: detecção via ML Kit Language ID
  sobre o texto extraído; destino: configurável (default PT).
- Ambos são Google/grátis/on-device (não são OSS; alternativas OSS — Tesseract/PaddleOCR — têm
  qualidade/integração piores em tela; registrado).

## V4 — Captura: MediaProjection, consentimento por acionamento ✅ (decorrência do modo efêmero — V5)
- Única rota sem AccessibilityService (D2 do projeto veta AccessibilityService).
- Android 14+: FGS type **`mediaProjection`** somado **após** o consentimento e removido ao fim
  do fluxo; Android 15: token não cacheável → como o modo é **efêmero** (V5), o diálogo do
  sistema aparece **a cada acionamento do atalho**. Fricção conhecida e aceita; a alternativa
  (sessão de projeção viva em background) exibiria o indicador de captura permanente na status
  bar — rejeitada por privacidade/UX.
- Consentimento via Activity transparente (o resultado de `createScreenCaptureIntent` exige
  Activity); frame por `VirtualDisplay` + `ImageReader` com a UI do KeyFlow oculta.
- **Seleção sobre a tela viva** (Yan): o seletor de área abre ANTES da captura, sobre a tela
  real (retângulo ajustável + tela inteira + cancelar); confirmar → captura 1 frame e recorta.

## V5 — Interação: **modo efêmero integrado à gravação** ✅ (Yan, 2026-07-01)
O "modo" não é persistente — existe só **entre a captura e a ação pós-captura**:
1. **Atalho** (`OrbShortcutAction.VISION_MODE`, rajada 1/2/3 toques) aciona o fluxo **mesmo
   gravando**: a gravação (PTT ou Toggle) entra em **pause automático**; em idle segue direto.
   Exceção deliberada ao gating atual de atalhos durante gravação — só para VISION_MODE.
2. Consentimento do sistema → **seletor de área** sobre a tela viva.
3. Confirmar → captura + recorte da área.
4. A orbe **trava** (sem reposição) e entra na fase de **gestos de arrasto com ícones/setas** —
   mesmo padrão DragArrows da gravação, com **3 direções**: **Copiar imagem** · **Copiar texto
   (OCR)** · **Traduzir texto**. **Cancelar = um toque na orbe** — semanticamente uma ação de
   **retorno ao modo de gravação** (Yan, 2026-07-01; rejeitada a 4ª direção de arrasto).
5. Ao copiar texto/traduzir → **estado de "cópia"**: toast de ~3 s com o texto; **tocar no toast
   abre um input efêmero** para manipular o texto; **tap na orbe** (exibindo o ícone da ação)
   **acelera** o fim — mesmo padrão do excluir/desfazer/acelerar existente. ⚠️ proposta: copiar
   imagem usa o mesmo toast, sem input.
6. Fim do fluxo → orbe **volta ao estado anterior** (IDLE ou o PAUSED que ficou). Se a gravação
   retomar e for transcrita, o clipboard é sobrescrito — **esperado e aceito** (Yan).
- Entrega é **clipboard-only** no MVP (sem "colar no campo"; a pendência/inserção da voz não
  participa — registrado como refinamento futuro possível).
- Consentimento negado / cancelar em qualquer fase → aborta com aviso e restaura o estado (item 6).
- Atalho ignorado durante SENDING/CANCELLING (estados transitórios da voz).

## V6 — Config: aba nova "Visão" ⚠️ ASSUMIDA (validar largura do TabBar com 6 abas)
Sexta aba nas configs (Modo · Voz · Teclado · Sobrepor · **Visão** · Docs), gateada pelo modo
overlay (o modo Visão vive na orbe). Conteúdo MVP: idioma-destino da tradução + gerenciador de
pacotes de idioma (baixar/deletar), nota de privacidade (tudo no aparelho), stub "Origem
multimodal — em breve" (fase 2 espelha a aba Voz: nativos/OpenAI/Groq/Custom). Se 6 abas não
couberem: scroll horizontal no TabBar ou reorganização (decisão visual na implementação).

## Fora do MVP (registrado para fase 2/3)
- Origem multimodal da Visão (nativos VLM + APIs) consumindo a captura bruta — reusa o
  gerenciador de modelos e o `:inference` da `native-voice-models` (runtime diferente:
  MediaPipe/LiteRT p/ VLM nativo).
- Perguntar sobre a tela ("o que é esse erro?") e descrição de acessibilidade.
- Gravar vídeo da tela (P3; mesma sessão MediaProjection).
- OCR por API dedicada (Google Cloud Vision etc.) — protocolo próprio, sem ganho vs VLM.
