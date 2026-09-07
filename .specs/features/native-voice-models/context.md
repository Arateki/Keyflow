# Native Voice Models — Context (grill 2026-07-01)

Decisões do grill com o Yan sobre a feature de **STT on-device com gerenciador de modelos**.
Todas as gray areas foram resolvidas com o Yan (G1–G11).

> Pesquisa que embasou as opções (2026-07-01, web): sherpa-onnx (k2-fsa) docs + GitHub; Moonshine
> (sem PT no STT — descartado); ML Kit GenAI Speech Recognition (alpha; pt-BR beta; engine do
> sistema); Play for On-device AI / AI packs (beta; só via Play; atrelado ao release);
> parakeet-tdt-0.6b-v3 int8 (25 idiomas europeus incl. PT, ~640 MB).

## G1 — Engine de inferência: **sherpa-onnx**
Apache-2.0 (compatível com a decisão de licença AdMob já registrada no STATE). AAR pré-compilado +
API Kotlin oficial. Um único runtime roda **Whisper multilingual** (tiny/base/small int8),
**Parakeet TDT 0.6B v3 int8** (PT incluído; WER melhor que Whisper large-v3 e mais rápido por
arquitetura TDT) e futuros (Qwen3-ASR, Omnilingual ASR, streaming Zipformer, VAD Silero).
**Rejeitados**: whisper.cpp (MIT, mas sem SDK Android oficial — JNI/NDK próprio p/ manter, e roda
só Whisper); os dois engines juntos (dobra a superfície de manutenção).

## G2 — Pipeline: **batch, como hoje**
Grava → para → transcreve localmente → cola. `AudioRecorder`/orbe/teclado intocados; entra só um
passo de decodificação m4a→PCM 16 kHz antes da inferência. A interface do engine nasce assíncrona,
então transcrição ao vivo/streaming pode ser adicionada depois sem quebrar contratos.
**Rejeitados**: gravação PCM paralela (segundo caminho de gravação p/ manter) e ao-vivo no MVP
(PT não tem modelo streaming bom; mudaria UX de inserção).

## G3 — Catálogo: **curado e enxuto, 3–4 modelos**
Nomes amigáveis (não jargão), tamanho, idiomas e nota qualidade×velocidade por modelo. Candidatos:
Whisper base int8 ("Rápido"), Whisper small int8 ("Equilibrado"), Parakeet TDT v3 int8 ("Máxima
qualidade"). **Rejeitados**: catálogo amplo (mais estados p/ testar, UX técnica) e 1 modelo só
(gerenciador sem razão de existir).

## G4 — Catálogo embutido no app
Hardcoded (Kotlin, estrutura serializável), versionado com o app. Zero infra e zero modo de falha
novo; adicionar modelo/corrigir URL exige release — aceitável com catálogo curado. A estrutura de
dados nasce serializável para permitir **catálogo remoto no futuro** sem re-arquitetura.

## G5 — Fonte dos pesos: **upstream HuggingFace (csukuangfj)**
Baixar **arquivos individuais** (`*.onnx` + `tokens.txt`) direto dos repos HF que o próprio
sherpa-onnx publica — sem tar.bz2 (sem lib de descompressão, retomada/progresso por arquivo).
**Risco aceito**: reorganização/remoção upstream quebra o download num app publicado; correção
exige release (mitigado pelo catálogo serializável — migrar p/ espelho próprio ou catálogo remoto
fica barato). Rejeitados: espelho próprio no HF (recomendação — Yan preferiu zero manutenção);
GitHub releases .tar.bz2 (descompressão + espaço 2× + sem progresso por arquivo).

## G6 — Mecânica de download: **DownloadManager do sistema**
Feito para downloads grandes: sobrevive à morte do app e a reboot, retomada automática,
notificação de progresso do sistema de graça, política de rede nativa. Zero dependência nova. O
app verifica SHA256 ao concluir e move para o diretório interno de modelos. Progresso na UI via
polling da query (padrão consolidado). Rejeitados: WorkManager+OkHttp (dependência nova + código
próprio sem ganho claro aqui); OkHttp com tela aberta (frágil p/ 640 MB).

## G7 — UI: **5ª opção "Local" no Segmented da aba Voz**
Novo valor no enum `TranscriptionSource` → entra no `Segmented` existente e segue o padrão das
outras origens: selecionou "Local", o painel abaixo vira o **gerenciador de modelos**. Se 5 opções
não couberem na largura, o Segmented quebra em 2 linhas (detalhe de implementação). Consistente
com `effectiveConfigFor`, gating e testes atuais. Rejeitados: reorganizar a aba em Nuvem/Local
(re-arquitetura sem ganho funcional); seção fixa com toggle (conflita com o modelo de origem única).

## G8 — Comportamentos do gerenciador (pacote aprovado como proposto)
1. **Rede**: Wi-Fi baixa direto; em dados móveis, diálogo de confirmação com o tamanho.
2. **1 download por vez** (fila/bloqueio simples).
3. **Espaço em disco** verificado antes de baixar (tamanho × 1,2); erro amigável.
4. **Auto-seleção**: modelo recém-baixado vira o selecionado se nenhum estiver.
5. **Deletar o selecionado** (com confirmação) deixa a origem Local "incompleta" → gravação avisa
   erro de config (mesmo tratamento `CONFIG_MISSING` atual).
6. **Aviso de peso** ("pode ser pesado neste aparelho") p/ modelos grandes em devices com pouca
   RAM — aviso, não bloqueio. Ganha importância extra com a decisão G11 (sem corte de catálogo
   por hardware).
7. **Sem fallback automático para nuvem**: falha local = erro honesto. Quem escolheu Local pode
   ter escolhido por privacidade; o app **nunca** envia áudio à rede sem ação explícita do usuário.

## G9 — Execução da inferência: **processo `:inference` separado** (escolha do Yan)
Serviço bound (`exported=false`) num segundo processo dedicado à inferência. Se o modelo estourar
memória, o SO mata o `:inference` — **teclado/overlay não caem**; o fluxo mostra erro e segue.
IPC via AIDL (request: paths do modelo + áudio + idioma; response assíncrona: texto ou erro).
Consequência importante: o processo `:inference` **não lê DataStore/Settings** (DataStore é
single-process) — tudo que o engine precisa chega pela chamada IPC.
Rejeitado: mesmo processo (recomendação — Yan preferiu o isolamento de OOM).

## G10 — Modelo **sempre carregado** (escolha do Yan)
Pré-carrega quando o modo ativa com origem Local + modelo selecionado (bind + preload no start do
serviço), e fica em RAM — resposta imediata em toda ditação, sem descarga por timeout. A descarga
é delegada ao SO: sob pressão de memória o sistema mata o processo `:inference` (isolado pela G9);
o próximo uso faz rebind + reload transparente (paga o load uma vez). Troca de modelo → reload.
Rejeitados: lazy+timeout (recomendação); carregar a cada transcrição (latência em toda ditação).

## G11 — **Sem Gate 0** (escolha do Yan)
"Siga direto, vai rodar em outros celulares também mais fortes, mesmo que fique lento quero rodar
aqui como teste." Implementação direta, confiando nos benchmarks públicos; o Galaxy A32 é **teste
informativo no final**, não critério de corte do catálogo. Modelos pesados ficam disponíveis com o
aviso de peso (G8.6). Rejeitado: PoC prévio com APKs demo no A32 (recomendação).

## G12 — Transcrição parcial ao pausar (adição do Yan, 2026-07-01)
Dois toggles novos na aba Voz (além do gerenciador de modelos nativos que esta spec já cria):
1. **"Transcrever parcialmente ao pausar"** — ao PAUSAR a gravação, o áudio desde o último ponto
   é transcrito pela **origem ativa** (local ou remota — agnóstico) e o texto fica **em memória**
   (buffer da sessão), **sem tocar** clipboard/inserção. Retomar e pausar de novo **complementa**
   o buffer (append). Um **indicador sutil próximo à orbe** mostra que há conteúdo parcial
   enquanto pausado. No envio final, só o último segmento falta transcrever → resposta mais rápida.
2. **"Visualizar e editar transcrição parcial"** — habilitado apenas com o 1º ligado: tocar no
   indicador **visualiza** o texto; tocar novamente abre um **input editável** (a orbe permanece
   visível e o teclado pode ser acionado — inclusive o KeyFlow); dá para **retomar a gravação
   dali**, e ao pausar de novo o texto novo aparece **em conjunto**; pode-se sair da edição a
   qualquer momento (volta ao indicador sutil, conteúdo preservado enquanto pausado). **Só ao
   enviar** o conteúdo (com as edições do usuário) segue para a entrega e o input efêmero é
   destruído.
**Implicação técnica aceita**: gravação vira **segmentada** — pausar finaliza o arquivo do
segmento (o .m4a precisa ser finalizado p/ transcrever); retomar inicia um novo arquivo. O texto
final = concatenação dos textos (buffer editado + último segmento). Edições do usuário são a
verdade (novas transcrições fazem append, nunca sobrescrevem). Falha na transcrição de um
segmento não perde o áudio (re-tentativa no envio). Cancelar a gravação descarta o buffer; o
desfazer do cancelamento restaura o buffer junto.

## Alternativas investigadas e descartadas (registro)
- **Moonshine**: PT não suportado no STT (só TTS) — fora.
- **whisper.cpp**: ver G1.
- **ML Kit GenAI Speech Recognition** (alpha): engine do sistema (AICore), pt-BR beta, sem
  gerenciador de modelos próprio; API 31+/Pixel p/ modo avançado. Candidata a origem "Sistema"
  **futura**, não substitui esta feature.
- **`SpeechRecognizer.createOnDeviceSpeechRecognizer`** (API 33+): depende do engine do
  OEM/device; sem controle de modelo. Mesma categoria da anterior (futuro, fora do escopo).
- **Play for On-device AI (AI packs, beta)**: entrega até 1,5 GB/pack, mas **só via Play**
  (sideload atual fica de fora) e packs atrelados ao release do AAB. Descartado p/ o gerenciador;
  pode ser revisitado pós-loja como otimização de entrega.
- **Voxtral/Qwen3-ASR grandes**: pesados p/ mobile hoje; Qwen3-ASR aparece nas docs do sherpa —
  candidato natural a entrada futura do catálogo quando validado.
