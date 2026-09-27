# Vision Capture — Design

Arquitetura do fluxo Visão (efêmero, 100% no aparelho). Decisões em `context.md` (V1–V6);
requisitos em `spec.md`. Feature irmã de `native-voice-models` — o "sentido" é um contrato
(pixels→texto); a UX reusa a linguagem de gestos que a orbe já tem.

## Princípio condutor

**O fluxo Visão é um "parêntese" sobre a máquina de gravação.** Ele nunca a substitui: pausa a
gravação ao abrir (se gravando), roda suas fases próprias (seleção → gestos → cópia), e devolve a
orbe exatamente ao estado que ficou (IDLE/PAUSED). A captura é a fundação; o processamento (OCR/
tradução hoje; multimodal na fase 2) é plugável sobre o mesmo bitmap.

```
Atalho VISION_MODE (rajada existente)
  │  [RECORDING? → coordinator.onIntent(PAUSE) — PTT ou Toggle]
  │  [SENDING/CANCELLING? → ignora]
  ▼
VisionFlow (NOVO — máquina própria, sobreposta e efêmera)
  CONSENT    VisionConsentActivity → createScreenCaptureIntent (a CADA acionamento)
  │            negado → aborta → restaura estado
  │            OK → FGS += type mediaProjection (só durante o fluxo)
  SELECTING  RegionSelectView sobre a TELA VIVA (retângulo + handles + tela-inteira + ✕)
  │            confirmar → ScreenFrameGrabber captura 1 frame (UI própria oculta) + recorte
  ACTIONS    orbe TRAVADA + VisionDragTargets (padrão DragArrows):
  │            🖼 copiar imagem · 📋 copiar texto (OCR) · 🌐 traduzir · ✕ cancelar (proposta)
  COPYING    CopyToast ~3s (texto/preview) — tap no toast → EphemeralInput (editar → re-copiar)
  │            tap na orbe (ícone da ação) → acelera (padrão desfazer/acelerar atual)
  ▼
fim → FGS -= mediaProjection → cache limpo → orbe restaurada (IDLE ou PAUSED que ficou)
```

## Componentes

### 1. `VisionFlow` (orquestrador; `MODE-*`, `ACT-06`)
Máquina efêmera `VisionFlowState { NONE, CONSENT, SELECTING, ACTIONS, COPYING }`, dona do fluxo e
da restauração:
- Disparo pelo atalho (novo caso em `OrbShortcutAction`; a resolução da rajada já existe).
  Guarda de entrada: `SENDING`/`CANCELLING` → ignora (MODE-03); `RECORDING` →
  `coordinator.onIntent(PAUSE)` **antes** de abrir (MODE-02) e memoriza `resumeToPaused = true`.
- **Não toca** a `ButtonStateMachine` além do PAUSE explícito (lição registrada do projeto:
  estados sobrepostos que mexem na máquina orfanizam jobs — ver memória CANCELLING/onTargetChanged).
  A orbe renderiza pelo `VisionFlowState` quando ≠ NONE; por baixo o `ButtonState` fica
  IDLE/PAUSED intacto (MODE-04).
- Qualquer saída (concluiu, cancelou, negou, revogou, erro) converge no mesmo epílogo:
  parar projeção → remover FGS type → limpar `cacheDir/vision/` → `state = NONE` (orbe volta a
  renderizar o `ButtonState` que ficou).

### 2. Consent + FGS (`CAP-01`, `STO-V01`)
- `VisionConsentActivity` transparente (`excludeFromRecents`): `createScreenCaptureIntent()` →
  resultado ao `VisionFlow` via callback/controller. **A cada acionamento** (efêmero; Android 15
  não cacheia token — V4; fricção aceita).
- Android 14+: `startForeground` com `MICROPHONE or MEDIA_PROJECTION` **somente após** o consent;
  ao fim do fluxo re-`startForeground` só com `MICROPHONE` (indicador do sistema some). Guardas
  por versão até minSdk 24. Manifest: permissão `FOREGROUND_SERVICE_MEDIA_PROJECTION` + type.
- `MediaProjection.Callback` de revogação → aborto gracioso (edge case).

### 3. `RegionSelectView` (`CAP-02`)
Janela overlay fullscreen **transparente sobre a tela viva** (não frame congelado — decisão V4):
retângulo ajustável (4 handles) com máscara escurecida fora, ações ⛶ tela-inteira e ✕ cancelar.
Geometria/hit-test/clamp em funções puras (`RegionGeometry.kt` — padrão da grade do teclado,
unit-testável). Confirmar → entrega o `Rect` em coordenadas de tela.
Rotação/resize durante a seleção → cancela com oferta de recomeço (edge case).

### 4. `ScreenFrameGrabber` (`CAP-03`)
No confirmar: oculta seletor + orbe (~2 frames), `VirtualDisplay` + `ImageReader` (RGBA_8888,
métricas reais) → 1 frame → `Bitmap.createBitmap(frame, rect)` → restaura UI. `FLAG_SECURE` →
frame preto detectado (heurística de luminância ~zero) → aviso + aborto gracioso.

### 5. Fase de gestos — `VisionDragTargets` (`ACT-01`, `MODE-05`)
Reusa o padrão **DragArrows** existente (setas/alvos, coords físicas→locais — fix do DRAG-07 já
aplicado): orbe travada para reposição; arrastar até o alvo e soltar executa — **3 direções**:
- 🖼 **Copiar imagem** → §7 · 📋 **Copiar texto** → OCR (§6) → clipboard · 🌐 **Traduzir** →
  OCR + tradução (§8) → clipboard.
- **Tap na orbe = cancelar/retornar ao modo de gravação** (epílogo do fluxo → MODE-04).
- Soltar fora de alvo: nada executa, fase continua (comportamento do DragArrows atual).
- Ícones/alvos novos são dados do mesmo componente de setas (não um componente novo).

### 6. `OcrEngine` (`OCR-01/02/03`)
Wrapper do ML Kit Text Recognition v2 **bundled** (`com.google.mlkit:text-recognition`, ~4 MB —
offline e sideload-safe; V3). `recognize(bitmap)` → texto com quebras por blocos/linhas; vazio →
`Empty` → aviso e epílogo (OCR-02). 100% local.

### 7. Copiar imagem (`ACT-04`, `CAP-04`, `STO-V02`)
Bitmap → PNG em `cacheDir/vision/` → `FileProvider` (escopado ao cache) → `ClipData.newUri`.
Limpeza no epílogo do fluxo.

### 8. `TranslateEngine` (`TRD-01..04`)
Language ID (origem; confiança < limiar → aviso + original copiável — TRD-03) + `Translator`
(origem→`Settings.visionTranslateTarget`); pacote ausente → **oferta inline** com ~tamanho →
`downloadModelIfNeeded` → traduz (TRD-02). Pacotes listados/deletáveis via `RemoteModelManager`
na aba Visão. Tudo on-device (TRD-04). Falha de download → erro amigável + oferecer copiar o
original.

### 9. Estado de "cópia" — `CopyToast` + `EphemeralInput` (`ACT-05/06`)
- **`CopyToast`**: janela overlay estilo do banner de desfazer atual: preview do texto (ou "print
  copiado"), ~3 s. **Tap na orbe** (que exibe o ícone da ação) **acelera** o fim — mesma mecânica
  excluir/desfazer/acelerar já implementada (reusar o timer/render do banner de cancelamento).
- **Tap no toast** → **`EphemeralInput`**: overlay com o texto **editável** + ações Copiar
  (re-copia o editado) e fechar. Janela de input em overlay precisa de foco próprio
  (`FLAG_NOT_FOCUSABLE` removido **só nesta janela**, técnica distinta do overlay principal —
  atenção ao R1: não afetar o `InputConnection` de terceiros mais que o necessário; janela
  separada e curta).
- Só ao encerrar o COPYING o epílogo roda e o estado anterior é liberado (ACT-06).

### 10. Aba Visão (`CFG-V01..03`)
`ConfigTab.VISAO` + `VisaoPanel`: dropdown idioma-destino (reusa `Dropdown`/`languageLabel`),
gerenciador de pacotes (lista com estado + ~30 MB/par), `HelperNote` de privacidade, card stub
"Origem multimodal — em breve" (fase 2). Gating padrão. TabBar com 6 abas: rolagem/compactação se
apertar (validação visual). Rotas de galeria + goldens.

### 11. Dados
`Settings` += `visionTranslateTarget: String = "pt"`. `OrbShortcutAction` += `VISION_MODE`
(labels na `ShortcutActionSheet`/aba Sobrepor). Sem catálogo próprio (OCR bundled; pacotes de
tradução são do ML Kit).

## O que NÃO muda
`ButtonStateMachine` (o fluxo só emite o PAUSE já existente e lê o estado p/ restaurar),
gravação/transcrição/pendência da voz, mic do teclado, notificação, abas existentes, DragArrows
da gravação (ganha um modo de alvos, não um fork).

## Fase 2 (registrada, fora do MVP)
`VisionProcessor` com origem configurável espelhando a Voz: `NATIVE_OCR / MULTIMODAL_LOCAL /
OPENAI / GROQ / CUSTOM` — multimodal local via gerenciador+`:inference` (runtime MediaPipe/LiteRT),
APIs via `chat/completions` com imagem; novas ações na fase de gestos (ex. "perguntar sobre a
tela"). Vídeo (P3) = mesma infra de projeção gravando. "Colar no campo" como ação extra é
refinamento futuro (hoje clipboard-only).

## Plano de testes (TESTING.md)

- **Unit (JVM)**: `VisionFlow` (máquina + guardas MODE-02/03 + restauração MODE-04 + epílogo
  único) com fakes de projeção/engines; `RegionGeometry` (handles/clamp/recorte);
  montagem de texto do OCR (blocos→quebras); orquestração do traduzir (detect→pacote→traduz;
  falhas); `resolveShortcut` com VISION_MODE; timer do CopyToast (acelerável — clock injetável,
  padrão do projeto).
- **Instrumented**: `OcrEngine` com bitmap gerado em teste (texto desenhado em `Canvas` →
  extrai; em branco → Empty); `RegionSelectView` (handles respondem, confirmar entrega o Rect);
  gestos de ação com fakes (arrastar → ação certa disparada — padrão dos testes de DragArrows);
  `CopyToast`/`EphemeralInput` (tap abre input; editar+copiar atualiza clipboard; tap na orbe
  acelera); integração pause/restauração dirigindo o `OverlayService` real via intents (padrão
  `OverlayServiceCancelTest`); aba Visão (Compose, fakes). Consent real: UiAutomator best-effort.
- **Manual (Galaxy A32)**: e2e com consent real (inclusive DURANTE ditação), FLAG_SECURE,
  revogação, tradução com download real, print colado em outro app, modo avião.
- **Screenshot**: goldens da aba Visão (claro/escuro); overlays efêmeros validados manualmente.

## Riscos / mitigação
- **Consent a cada acionamento** (fricção) → decisão consciente (V4); revisitar só se doer no uso
  real (alternativa exibe indicador permanente).
- **Consent dialog não automatizável** → camadas puras + UiAutomator best-effort + manual.
- **FGS types combinados** (microphone|mediaProjection) e re-`startForeground` dinâmico → validar
  cedo no Android 14/15 (VC-T3 é a primeira task de device); fallback: FGS separado p/ projeção.
- **`EphemeralInput` focável em overlay** → janela própria e efêmera; testar que não quebra o
  `InputConnection` de apps de terceiros (relação com R1 histórico do projeto).
- **Interação PAUSE automático × PTT** → o pause do PTT já existe na máquina (decisão da fase de
  gestos); testes cobrem os dois modos (MODE-02).
- **6ª aba apertada** → rolagem/compactação (golden captura).
