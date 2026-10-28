# 2026-10-28 — OCR PaddleOCR: medição de CER, tempo e RAM no emulador (vision-capture VC-T6, branch equipe/visao-ocr)

Primeira fatia de código do motor da Visão: `OcrEngine`/`PaddleOcrEngine` (`vision/ocr/`), o processo `:vision` (`vision/service/`) e o
cliente com `DeathRecipient` (`vision/client/`), sobre o runtime de `2026-10-28-runtime-onnx-sem-ndk.md`. Aba, fluxo de captura e
gestos ficam para outra branch. **Esta medição foi feita só no emulador do pool; os aparelhos reais (A32, Tab S10 Lite, Vaio) ainda
não foram medidos** — o que falta e como rodar está no fim.

## Como foi medido

- **Aparelho**: emulador do pool, `sdk_gphone64_x86_64`, Android 14, 4 vCPU e 5,9 GB, num servidor compartilhado e carregado
  (carga/núcleo 1–3 durante as rodadas): os tempos são de ordem de grandeza, não de benchmark. ORT 1.28.2 (a do sherpa), CPU.
- **Modelo**: o par do catálogo (`OCR_PADDLE_LATIN`, `monkt/paddleocr-onnx`: detector PP-OCRv3 `det.onnx` + reconhecedor PP-OCRv5 latino
  `rec.onnx` + `dict.txt`). Os SHA-256 dos três arquivos foram **recalculados em 2026-10-27** baixando de novo (`ee40e800…`, `614ffc2d…`,
  `3c0a8a79…`: batem com o catálogo). Forma lida dos `.onnx`: detector `[N,3,H,W] → [N,1,H,W]` (dinâmico); reconhecedor `[N,3,48,W] →
  [N,T,504]`; `dict.txt` com 502 linhas = 504 classes com o branco (0) e o espaço (último).
- **Corpus**: 30 imagens geradas em `Canvas` (`OcrTestSupport.synthetic`): 5 grupos (pt com `ã ç é õ`, en, es com `¿ ñ ó ¡`, código em
  monoespaçada, interface com números, `·` e `%`) x 6 estilos (12, 14, 16, 20, 24 e 32 px; fundo claro e escuro), 720 px de largura, frases
  quebradas por palavra para nada ser cortado na borda. Verdade = as frases desenhadas. **CER** = distância de edição por caractere sobre o
  texto com espaços e quebras colapsados (layout não conta como erro de leitura).
- **Código**: `OcrMeasurementTest` (relatório por fase) e `OcrSpeedProbeTest` (latência crua da ORT), os dois só por pedido explícito da classe.

## Resultados (emulador)

**CER** (média das 30 imagens; parâmetros do `OcrParams` escolhidos abaixo): **0,0020** (0,20%). Por grupo: pt 0,000, en 0,000, es 0,000,
código 0,004, interface 0,007 (o único erro sistemático é o `·` lido como `-`). Por estilo: 12 px 0,0016 · 14 px 0,0045 · 16 px 0,0045 ·
20 px 0,000 · 24 px 0,000 · 32 px 0,0014. O limite provisório do design (≤ 5% em texto sintético nítido) **passa com folga**; o texto
sintético é limpo demais para discriminar parâmetros (as diferenças abaixo são de décimos de ponto). O limite de ≤ 15% em capturas reais
**não foi medido**: não há capturas reais do A32 nesta rodada.

Escolhas pela medição (design §6.2: "não presumir"):

| Parâmetro | Candidatos → CER médio | Escolha |
| --- | --- | --- |
| Detector: canais e normalização | imagenet-BGR 0,0028 · imagenet-RGB 0,0028 · 0–1 RGB 0,0051 | **imagenet, BGR** (a referência do PaddleOCR; a ordem BGR/RGB é indiferente em texto cinza, o `config.json` do monkt "RGB 0–1" é pior) |
| Reconhecedor: normalização | `(v/255−0,5)/0,5` BGR 0,0020 · idem RGB 0,0020 · 0–1 RGB 0,0022 | **BGR, ±1** (a referência) |
| Limiar de caixa do detector | 0,5 = 0,6 (0,7 igual na 1ª rodada) | **0,6** (a referência; as caixas do corpus passam de 0,7 de pontuação, o limiar só vai importar em captura real) |
| `unclip` | 1,5 → 0,0028 · 2,0 → 0,0020 | **2,0** (caixa mais alta, menos ampliação para a altura 48 do reconhecedor) |
| Descarte por pontuação do reconhecedor | 0,3 = 0,5 (0,7 pior) | **0,5** (a referência) |
| Lote do reconhecedor | 6 → 12,2 s · 1 → 10,8 s (tela 720x1600) | **1 caixa por chamada**: o tempo por pixel é o mesmo (0,27–0,33 ms/px) e o preenchimento mais as ativações x6 custam RAM |
| Alocador em arena da ORT | sem arena 16,9 s · com arena 10,8 s (tela 720x1600, 2 threads) | **com arena**: reconhecedor 2 a 3x mais rápido na sonda; custa RSS (abaixo) |
| Threads | 1 → 13,4 s · 2 → 10,8 s · 4 → 12,6 s (tela 720x1600) | **2** (4 não ajudou no emulador de 4 vCPU; a re-medir no A32, 2 big + 6 little) |

**Plano B** (`deepghs/paddleocr`, commit `e7bd63d9…`, detector PP-OCRv4 + reconhecedor PP-OCRv3 latino; arquivos baixados e com SHA-256
conferido: `30a86f57…`, `f26feb21…`, `8e6d4e36…`): uma avaliação com as convenções de referência deu **CER 0,0332** (pt 0,078, es 0,053), contra 0,0028 do par do
catálogo com os mesmos parâmetros. **O par do catálogo fica**; o plano B não entra (e teria a licença `other`). O detector v4 leva minutos por
avaliação no emulador, então a grade completa dele não foi feita.

**Tempo** (média de 2 execuções depois de 1 de aquecimento, sessões carregadas, no mesmo processo do teste; CER das telas 0,000–0,003):

| Entrada | 1 thread | 2 threads | 4 threads |
| --- | --- | --- | --- |
| ¼ de tela 360x800 (23 caixas) | 3,9 s | **3,1 s** | 3,9 s |
| Tela cheia 720x1600, A32 (41 caixas, 2 faixas) | 13,4 s | **10,8 s** | 12,6 s |
| Tela cheia 1080x2400 (63 caixas, 3 faixas) | 22,7 s | **18,2 s** | 19,8 s |

Antes da arena e do lote 1 (linha de base da 1ª rodada, 1 thread, sem arena, lote 6): ¼ de tela 18,3 s e 720x1600 39,7 s. O reconhecedor é
~88% do tempo (≈ 0,25 s por caixa); o detector custa 0,3–1,6 s por faixa. **A meta provisória "tela cheia ≤ 5 s" NÃO fecha no emulador**
(10,8 s a 720x1600, 2 threads); o ¼ de tela (o uso típico, que é uma região selecionada e não a tela toda) fica em ~3 s. O tempo do reconhecedor é proporcional à
quantidade de texto (caixas e largura delas), não à área da captura.
**Nada foi escondido nem ajustado para caber**: as alavancas que restam são fora do escopo desta fatia (reconhecedor quantizado em int8,
reconhecimento em paralelo com duas sessões, parar cedo e entregar as primeiras linhas); decisão do Yan depois da medição no A32.

**RAM do `:vision`** (VmHWM zerado antes de cada chamada, lido pelo próprio serviço): processo ocioso sem modelo **119 MB** de RSS (o
`Application` inteiro sobe nele); pico com o modelo: ¼ de tela **213 MB** (+91 MB), 720x1600 **306 MB** (+182 MB), 1080x2400 **362 MB**
(+237 MB); com o modelo carregado e ocioso **319 MB** (a arena segura a memória até a descarga por ociosidade, 30 s). A meta provisória
"pico ≤ 250 MB" fecha **como acréscimo sobre o processo ocioso** (91 a 237 MB) e **não** como RSS total (213 a 362 MB). Sem arena o RSS
é menor (na sonda, 313 MB contra 475 MB no processo do teste) e o tempo 1,5x pior — troca consciente, a decidir no A32 (4 GB).

**Outros números úteis**: carga fria dos dois modelos 0,3 s (arquivos de 10 MB, mapeados); `OutOfMemoryError` simulado e modelo ausente
cobertos na JVM; processo morto no meio (`Process.killProcess` do `:vision`) → `Failed(PROCESS_DIED)` na hora e o uso seguinte religa;
cancelar a corrotina manda `cancel()` ao serviço e o seguinte funciona; o `:vision` roda com StrictMode `detectNetwork` + `penaltyDeath`
na thread principal e na de trabalho (debug) e leu texto de verdade sem ser morto (sem rede, OCR-03).

## O que fica por medir em aparelho real (e como)

1. **A32 (4 GB), Tab S10 Lite (6 GB), Vaio TL10 (8 GB)**: tempo (¼ de tela, 720x1600, 1080x2400; 1, 2 e 4 threads), RAM de pico do `:vision` e
   se a arena cabe no A32; CER em **capturas reais** (≤ 15% é a proposta do design; colocar PNG + `nome.txt` com a verdade em uma pasta e passar
   `-e ocrRealDir`); decidir `threads` e `cpuArena` por aparelho, e a descarga por ociosidade (30 s, ponto de partida).
2. Comando (nunca `connectedAndroidTest` no aparelho do Yan; só `installDebug installDebugAndroidTest`):
   `adb -s <serial> shell am instrument -w -e class com.keyflow.vision.ocr.OcrMeasurementTest -e ocrModelDownload true -e ocrRealDir <pasta no aparelho> com.arateki.keyflow.test/androidx.test.runner.AndroidJUnitRunner`
   (o aparelho baixa os 10 MB pelo downloader do app, o que também confere o SHA-256 do catálogo no aparelho; o relatório sai no texto da
   instrumentação e em `files/ocr-measure.txt`). A sonda crua: `-e class com.keyflow.vision.ocr.OcrSpeedProbeTest`. Os testes de verdade do
   OCR: `-e class com.keyflow.vision.ocr.PaddleOcrEngineDeviceTest,com.keyflow.vision.VisionServiceTest -e ocrModelE2E true`.
3. Depois da medição, promover o OCR: `validatedOnDevice = true` em `OCR_PADDLE_LATIN` (hoje `false`, só no debug) e conferir o `doc.json`
   do site (a Docs cita a licença quando a flag vira `true`). Não foi feito aqui de propósito.
4. Ideias já anotadas para ganhar tempo/RAM: `memory.enable_memory_arena_shrinkage` da ORT ao fim de cada chamada (devolve o RSS da arena sem
   descarregar o modelo); o `Application` inteiro subindo no `:vision` (119 MB ociosos) merece um olhar de quem for dono dele.
