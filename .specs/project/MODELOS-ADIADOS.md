# Modelos Adiados e Descartados — KeyFlow

Documento de referência técnica e jurídica sobre modelos de inteligência artificial (LLMs, TTS e visão/escrita) pesquisados para o KeyFlow, detalhando o que foi adiado para fases futuras, o que foi descartado em definitivo e os caminhos necessários para retomada.

---

## Decisão de 25/10/2026

Em deliberação de 25/10/2026 (19:58), o Yan estabeleceu o escopo imediato e as exclusões do projeto:

1. **Voz (fala da aba Voz)**: por enquanto, uso exclusivo do sintetizador nativo do sistema Android via API `android.speech.tts.TextToSpeech`.
2. **Modelos de texto locais**: catálogo restrito aos modelos leves, com licença livre permissiva e download público desimpedido (ungated):
   - **SmolLM2-1.7B-Instruct** (licença Apache-2.0, tamanho ~1,73 GB);
   - **DeepSeek-R1-Distill-Qwen-1.5B** (licença MIT, tamanho ~1,83 GB).
   - **Entraram no `ModelCatalog` em 25/10/2026** (não estão mais pendentes), como "Intermediário" e "Raciocínio" na aba Raciocínio,
     com commit, tamanho e SHA-256 conferidos na API do HF e no arquivo inteiro (`scripts/verify-model-catalog.py`); o resultado no
     LiteRT-LM do app está no `STATE.md` ("Catálogo com mais dois modelos de texto").
3. **Modelos fora do catálogo por tamanho**:
   - **Phi-3-mini-4k-instruct** (MIT, ~3,85 GB) e **Phi-4-mini-instruct** (MIT, ~3,91 GB): descartados do catálogo padrão de teclado devido ao tamanho de download excessivo para dispositivos móveis.
4. **Itens adiados para o futuro**:
   - **Llama 3.2 (1B/3B)** com distribuição a partir de armazenamento próprio (S3/R2);
   - **Gemma (1, 2, 3/3n)**;
   - **ML Kit Digital Ink Recognition** (escrita à mão manuscrita, estritamente opcional e mediante consentimento explícito);
   - **Vozes offline próprias** no runtime sherpa-onnx (VITS Coqui pt-cv por caracteres; Piper pt_BR com léxico aberto no lugar do eSpeak-ng).
5. **Itens descartados em definitivo**:
   - **MobileLLM (125M, 350M, 1B)**: licença não-comercial (FAIR NC);
   - **Gemini Nano**: inexistência de modelo autônomo; dependência restrita ao Android AICore em aparelhos topo de linha.

---

## Itens Adiados

### 1. Llama 3.2 (1B e 3B) / Llama 4 (com cópia própria em S3/R2)

* **O que é**: Família de modelos compactos de linguagem da Meta, executados localmente no Android através da biblioteca LiteRT-LM (`.litertlm`).
* **Licença e obrigações concretas**: *Llama 3.2 Community License Agreement* (e *Llama Community License*). Permite uso comercial em aplicativos gratuitos monetizados com anúncios (AdMob), mas impõe:
  1. Atribuição visível e proeminente "Built with Llama" na interface do aplicativo e nos materiais de divulgação;
  2. Inclusão obrigatória de cópia da licença da Meta e do arquivo `NOTICE` original no aplicativo;
  3. Denominação obrigatória contendo "Llama" para qualquer modelo derivado ou customizado;
  4. Teto de 700 milhões de usuários ativos mensais (MAU) no mês anterior ao lançamento (acima disso requer licença comercial formal com a Meta);
  5. Cumprimento estrito da Política de Uso Aceitável (*Acceptable Use Policy* - AUP) da Meta.
* **O que bloqueia hoje**: No repositório oficial da Meta e no LiteRT Community (`litert-community/Llama-3.2-1B-Instruct-litert-lm`), os modelos são *gated* (exigem autenticação via Hugging Face token individual e aceite de termos por cada usuário, o que quebra o download anônimo sob demanda pelo catálogo do KeyFlow).
* **Caminho para adotar**:
  1. A cláusula 1(a) da *Llama 3.2 Community License* ("Redistribution and Use") autoriza explicitamente a redistribuição e disponibilização pública de cópias do modelo, desde que acompanhadas do texto da licença e do arquivo `NOTICE`;
  2. O KeyFlow pode converter os pesos oficiais para `.litertlm` (ou espelhar a conversão da comunidade) e hospedá-los em bucket próprio (AWS S3 ou Cloudflare R2) com URL pública sem autenticação;
  3. Adicionar telas de atribuição ("Built with Llama") e menção aos termos no fluxo do app antes de iniciar o download.
* **Custo e tamanho**:
  - Tamanho: ~1,3 GB (1B quantizado int8/int4) a ~2,5 GB (3B);
  - Custo: custos operacionais de tráfego de saída (egress) de storage em nuvem (S3/R2) bancados pelo projeto.
* **Fontes**:
  - [Llama License](https://llama.meta.com/license) (consultado em 2026-10-25)
  - [Llama 3.2 Community License Agreement](https://github.com/meta-llama/llama-models/blob/main/models/llama3_2/LICENSE) (consultado em 2026-10-25)
  - [litert-community/Llama-3.2-1B-Instruct-litert-lm (Gated)](https://huggingface.co/litert-community/Llama-3.2-1B-Instruct-litert-lm) (consultado em 2026-10-25)

---

### 2. Google Gemma (Gemma 1, Gemma 2, Gemma 3 / 3n; o Gemma 4 não está adiado)

* **O que é**: Família de modelos abertos desenvolvida pelo Google DeepMind, com variantes leves (ex.: Gemma 2-2B-IT).
* **Licença e obrigações concretas**: *Gemma Terms of Use* e *Gemma Prohibited Use Policy*. Não é licença aberta aprovada pela OSI (Gemma 1, 2 e 3 não adotam Apache-2.0 nem MIT). **Exceção: o Gemma 4 é Apache-2.0 e sem gate** (`litert-community/gemma-4-E2B-it-litert-lm`, conferido na API do HF em 2026-10-25), e o Gemma 4 E2B já está no catálogo do app como o modelo "Equilibrado"; o adiamento vale só para as gerações 1 a 3. Permite exploração comercial gratuita, mas inclui cláusula anti-concorrência proibindo utilizar as saídas do modelo para treinar ou aprimorar modelos de linguagem concorrentes (salvo modelos derivados do próprio Gemma). Exige redistribuição acompanhada dos termos.
* **O que bloqueia hoje**: Todas as distribuições oficiais e conversões comunitárias para LiteRT (`google/gemma-2-2b-it`, `litert-community/Gemma2-2B-IT`) são mantidas como *gated* no Hugging Face (requerem token de autenticação e preenchimento de formulário de consentimento da Google). Não há justificativa técnica para o esforço de hospedagem própria quando SmolLM2-1.7B (Apache-2.0) e DeepSeek-R1-Qwen-1.5B (MIT) já estão disponíveis de forma totalmente ungated e sob licenças livres padrão.
* **Caminho para adotar**: Caso benchmarks futuros justifiquem o modelo, o KeyFlow precisará gerar a versão `.litertlm` internamente e disponibilizá-la em bucket próprio S3/R2 com as declarações de licença da Google.
* **Custo e tamanho**: ~1,6 GB a ~2,6 GB (Gemma 2-2B em formato LiteRT quantizado).
* **Fontes**:
  - [Gemma Terms of Use](https://ai.google.dev/gemma/terms) (consultado em 2026-10-25)
  - [Gemma Prohibited Use Policy](https://ai.google.dev/gemma/prohibited_use_policy) (consultado em 2026-10-25)
  - [litert-community/Gemma2-2B-IT (Gated)](https://huggingface.co/litert-community/Gemma2-2B-IT) (consultado em 2026-10-25)

---

### 3. ML Kit — Escrita à Mão / Digital Ink Recognition (Opcional com Consentimento)

* **O que é**: Biblioteca de visão computacional da Google (`com.google.mlkit:digital-ink-recognition`) capaz de reconhecer traços manuscritos em tempo real no teclado, convertendo desenhos de letras, números e símbolos em texto.
* **Licença e obrigações concretas**: SDK proprietário e gratuito do Google. Não possui contaminação GPL. Entretanto, o SDK embute rotinas de telemetria técnica e diagnóstico de uso que transmitem métricas de hardware (fabricante, modelo, versão do Android, aceleradores gráficos), versão do app, ID de instalação e estatísticas de chamadas da API via `DataTransport` (`firebaselogging.googleapis.com`) para servidores do Google. Não existe método ou chave oficial na API para desativar essa telemetria. *Nota: o conteúdo dos traços, imagens ou texto digitado é processado 100% no aparelho e não é transmitido ao Google*.
* **O que bloqueia hoje**:
  1. *Inicialização precoce no boot*: Por padrão, a biblioteca inclui no manifesto um ContentProvider (`com.google.mlkit.common.internal.MlKitInitProvider`) que inicializa os componentes no momento em que o processo do KeyFlow é criado, antes de qualquer interação do usuário;
  2. *Ficha da Play Store*: A inclusão da biblioteca obriga a atualizar a ficha de "Segurança dos Dados" da Google Play Console para declarar a coleta de diagnósticos e identificadores de dispositivo;
  3. *Postura de privacidade*: A política do KeyFlow veda telemetria sem consentimento. É necessário garantir que nenhum byte de telemetria seja disparado a menos que o usuário ative expressamente a funcionalidade de escrita à mão.
* **Caminho para adotar**:
  1. Declarar a dependência do SDK standalone (`com.google.mlkit:digital-ink-recognition`), que não exige o Google Play Services instalado no sistema;
  2. No `AndroidManifest.xml`, remover explicitamente o ContentProvider automático:
     ```xml
     <provider
         android:name="com.google.mlkit.common.internal.MlKitInitProvider"
         tools:node="remove" />
     ```
  3. Inicializar a biblioteca de forma manual e tardia via `com.google.mlkit.common.sdkinternal.MlKitContext.initialize(context)` (*não confirmado* quanto à estabilidade de longo prazo por se tratar de classe sob pacote `sdkinternal`), invocando-a **apenas** quando o usuário ligar a escrita à mão na interface de ajustes;
  4. Apresentar diálogo de consentimento prévio informando a coleta de telemetria técnica pelo SDK do Google;
  5. Baixar o pacote de reconhecimento do idioma escolhido sob demanda via `RemoteModelManager` (~20 MB por língua);
  6. Atualizar a declaração de "Segurança dos dados" na Google Play Store, marcando a coleta de telemetria como "Opcional" (atrelada ao consentimento da funcionalidade).
* **Custo e tamanho**: ~20 MB de aumento no APK/AAB base; ~20 MB adicionais por modelo de idioma baixado dinamicamente; funcionamento 100% offline após o download do modelo.
* **Fontes**:
  - [ML Kit Android Data Disclosure](https://developers.google.com/ml-kit/android-data-disclosure) (consultado em 2026-10-25)
  - [Digital Ink Recognition Android Guide](https://developers.google.com/ml-kit/vision/digital-ink-recognition/android) (consultado em 2026-10-25)
  - [ML Kit Model Installation Paths](https://developers.google.com/ml-kit/android/choose-an-installation-path) (consultado em 2026-10-25)

---

### 4. Vozes Offline Próprias (TTS Neural pt-BR via sherpa-onnx)

* **O que é**: Síntese de fala neural local rodando no motor `sherpa-onnx` do KeyFlow, permitindo que a aba Voz do agente fale sem depender de motores TTS proprietários do sistema operacional.
* **Licença e obrigações concretas**:
  - Runtime `sherpa-onnx`: Licença Apache-2.0 permissiva.
  - Modelos e dados: VITS Coqui pt-cv (treinado no Common Voice); vozes Piper pt_BR (cadu, faber). Licença dos pesos e dos dados de cada voz: *não confirmado* — conferir o model card de cada uma antes de adotar.
  - *Armadilha da licença do fonemizador*: O mecanismo padrão de vozes do Piper depende do `eSpeak-ng`, que é distribuído sob **GPL-3.0**. Caso o `eSpeak-ng` seja compilado ou embutido junto ao APK do KeyFlow (via JNI ou linkagem estática), a cláusula de copyleft do GPL-3.0 infecta o app inteiro, violando a regra de ouro de manter o KeyFlow sob licença Apache-2.0 sem copyleft.
* **O que bloqueia hoje**:
  1. *Complexidade de fonemização sem GPL*: Necessidade de pipeline fonético livre para pt-BR ou de modelo baseado diretamente em caracteres;
  2. *Sobrecarga de download*: Cada voz neural exige entre 60 MB e 75 MB de download pelo usuário;
  3. *Solução nativa suficiente*: A API do sistema `android.speech.tts.TextToSpeech` (AOSP, Apache-2.0) já atende integralmente a aba Voz sem adicionar peso ao APK e sem custos de infraestrutura.
* **Caminho para adotar**:
  - **Abordagem A (Recomendada — Grapheme-to-Speech / Char-based)**: Adotar o modelo `csukuangfj/vits-coqui-pt-cv` (~71 MB ONNX). Esse modelo foi treinado com `use_phonemes: false` e utiliza o frontend nativo de caracteres (`OfflineTtsCharacterFrontend`) do sherpa-onnx. Ele mapeia caracteres latinos diretamente para IDs de tokens, dispensando completamente o `eSpeak-ng` e qualquer outro fonemizador GPL.
  - **Abordagem B (Piper com Léxico Livre)**: Utilizar modelos Piper pt_BR (como `vits-piper-pt_BR-cadu-medium`, ~60 MB) alimentando o sherpa-onnx com um léxico fonético aberto em arquivo de texto (ex.: dicionário fonético pt-BR do Grupo FalaBrasil `falabrasil/dicts-br`; licença *não confirmada*) ou com um conversor G2P baseado em regras ortográficas do português em Kotlin puro. Se o sherpa-onnx consegue rodar vozes Piper só com `lexicon.txt`, sem o eSpeak-ng embutido: *não confirmado* — testar antes de adotar.
* **Custo e tamanho**: ~60 MB a ~75 MB por voz neural instalada (download sob demanda). Zero impacto adicional de bibliotecas binárias no APK (runtime sherpa-onnx já faz parte do app).
* **Fontes**:
  - [k2-fsa/sherpa-onnx (Offline TTS VITS)](https://github.com/k2-fsa/sherpa-onnx) (consultado em 2026-10-25)
  - [csukuangfj/vits-coqui-pt-cv no Hugging Face](https://huggingface.co/csukuangfj/vits-coqui-pt-cv) (consultado em 2026-10-25)
  - [rhasspy/piper-voices pt_BR](https://github.com/rhasspy/piper-voices) (consultado em 2026-10-25)
  - [Grupo FalaBrasil Dicionários Fonéticos (licença não confirmada)](https://github.com/falabrasil/dicts-br) (consultado em 2026-10-25)

---

## Itens Descartados em Definitivo

### 1. MobileLLM (125M, 350M, 1B)
* **Motivo do descarte**: Distribuído pela Meta sob a licença de pesquisa *FAIR Noncommercial Research License* (FAIR NC). Essa licença veda expressamente qualquer uso em aplicações comerciais, monetizadas ou empresariais. Como o KeyFlow é distribuído publicamente com monetização via anúncios Google AdMob, o uso do MobileLLM é juridicamente proibido.
* **Fonte**: [facebookresearch/MobileLLM no GitHub](https://github.com/facebookresearch/MobileLLM) (consultado em 2026-10-25).

### 2. Google Gemini Nano
* **Motivo do descarte**: O Gemini Nano não é disponibilizado pelo Google como arquivo de pesos autônomo (.bin / .litertlm) para download livre pelo aplicativo. O acesso é exclusivo via *Android AICore* (sistema operacional), disponível unicamente em dispositivos topo de linha certificados (família Pixel 8/9, Galaxy S24/S25) executando Android 14+ com serviços do Google Play atualizados. Não atende o requisito do KeyFlow de autonomia e universalidade na base de aparelhos Android compatíveis (`minSdk 24`).
* **Fonte**: [Android AICore Documentation](https://developer.android.com/aicore) (consultado em 2026-10-25).

### 3. Phi-3-mini-4k-instruct e Phi-4-mini-instruct
* **Motivo do descarte do catálogo padrão**: Embora possuam licença permissiva MIT e distribuição ungated no LiteRT Community, seus arquivos possuem aproximadamente ~3,85 GB (`phi3_mini_4k_instruct_int8.litertlm`) e ~3,91 GB (`Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm`). Esse tamanho é incompatível com a experiência de uso de um teclado mobile (tempo e custo de download excessivos na rede celular do usuário), quando modelos na faixa de 1,7 GB a 1,8 GB (SmolLM2 e DeepSeek-R1-Qwen) atendem o perfil on-device com consumo de memória significativamente menor.
* **Fontes**:
  - [litert-community/Phi-3-mini-4k-instruct](https://huggingface.co/litert-community/Phi-3-mini-4k-instruct) (consultado em 2026-10-25)
  - [litert-community/Phi-4-mini-instruct](https://huggingface.co/litert-community/Phi-4-mini-instruct) (consultado em 2026-10-25)

---

## Tópicos Classificados como "Não Confirmado"

Em cumprimento ao rigor metodológico das pesquisas técnicas do projeto, os seguintes pontos permanecem marcados como **não confirmados**:

1. **Disponibilidade oficial ungated de Llama 3.2**: Permanece não confirmado se a Meta ou o consórcio LiteRT pretendem lançar em algum momento um repositório ungated oficial sem necessidade de token de acesso individual.
2. **Estabilidade da inicialização tardia do ML Kit**: Permanece não confirmado se a inicialização programática manual via `MlKitContext.initialize` será mantida pública e retrocompatível pelo Google no longo prazo, dado que reside sob o pacote interno `com.google.mlkit.common.sdkinternal`.
3. **Disponibilidade universal offline da voz pt-BR no Android TextToSpeech**: Permanece não confirmado que todos os aparelhos e fabricantes do mercado (especialmente OEMs customizados de entrada) tragam o pacote de dados offline de pt-BR pré-instalado de fábrica. Em aparelhos Android puro/AOSP, o usuário pode precisar acionar o download do pacote de voz nas configurações de acessibilidade do sistema.
