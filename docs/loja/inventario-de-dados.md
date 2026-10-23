# Inventário de Dados — KeyFlow (v1)

> Documento técnico de referência para a política de privacidade, a declaração de segurança de dados (Data Safety Form) e a submissão do KeyFlow na Google Play Store. Baseado na análise do código-fonte da versão 1.0.0.

---

## 1. Tabela Mestra de Fluxo de Dados (O que sai do aparelho)

| Funcionalidade | Dado que sai | Destino | Quando sai | Retenção / Armazenamento | Chave / Conta usada | Citação no Código |
|---|---|---|---|---|---|---|
| **Transcrição de Voz na Nuvem (STT)** | Arquivo de áudio gravado (`audio/m4a`, 16 kHz AAC) | Provedor escolhido pelo usuário: OpenAI (`api.openai.com`) ou Groq (`api.groq.com`) ou endpoint customizado compatível | Apenas sob demanda explícita (toque no botão de microfone do teclado ou orbe) | No aparelho: temporário em cache (`cacheDir/keyflow-recordings/`), descartado após envio ou cancelamento. No provedor: conforme termos do provedor | Chave de API do próprio usuário (BYOK), guardada no Android Keystore (`keyflow_auth`) | `AudioRecorder.kt:13,136-146`, `OverlayCoordinator.kt:295-313,565-583`, `WhisperApi.kt:20-33`, `Settings.kt:281-283` |
| **Raciocínio em Nuvem (LLM)** | Texto do prompt enviado para teste ou geração | Provedor escolhido pelo usuário: OpenAI (`api.openai.com/v1/responses`), Anthropic (`api.anthropic.com/v1/messages`), ou Google (`generativelanguage.googleapis.com`) | Apenas sob demanda do usuário ao testar/acionar raciocínio | No aparelho: efêmero (não há banco de histórico de chat na nuvem). No provedor: OpenAI usa `store: false`; Anthropic e Google retêm segundo suas políticas | Chave de API do usuário ou token OAuth via login por assinatura (ChatGPT, Claude, Google). Tokens cifrados no Keystore local | `OpenAiReasoningEngine.kt:46-120`, `AnthropicReasoningEngine.kt:40-100`, `GoogleReasoningEngine.kt:40-100`, `CredentialStore.kt:33-145` |
| **Agente KeyFlow (Fase 1)** | Mensagens de texto da conversa e instruções de ajuste visual de temas | Backend de IA configurado pelo usuário (OpenAI / Anthropic / Google) | Apenas quando o usuário abre o chat do agente no app de configuração e envia mensagens | Processamento da conversa no provedor de IA escolhido. Segredos de sistema são mascarados antes do envio | Mesma chave de API ou token OAuth configurado na aba Raciocínio | `AgentHost.kt:30-110`, `AgentSecrets.kt:20-80`, `AndroidSkinWorkshop.kt:40-120` |
| **Download de Modelos Locais (Voz e Raciocínio)** | Requisição HTTP GET para download dos arquivos de pesos dos modelos | Hugging Face (`huggingface.co`) | Apenas sob demanda do usuário ao clicar para baixar um modelo offline | Arquivos salvos no armazenamento local do app (`context.filesDir/models/`). Excluídos se o usuário desinstalar ou apagar dados | Nenhuma conta ou autenticação necessária (repositórios públicos abertos) | `ModelCatalog.kt:71-73,165-166`, `ModelDownloadService.kt:30-90` |
| **Compra Única (R$ 15 — Desbloqueio e Remoção de Anúncios)** | `purchaseToken`, `productId` ("unlock_all_skins"), token de integridade | Google Play Billing e API própria do KeyFlow (`keyflow-api.arateki.com/v1/purchases/redeem`) | No momento em que o usuário confirma a compra na folha de pagamento | Google Play processa o pagamento; a API do KeyFlow valida o token no Google Play Developer API e registra o status de desbloqueio para a identidade anônima | Conta Google do usuário no Google Play; token de acesso anônimo na API do KeyFlow | `BillingManager.kt:40-160`, `KeyFlowApiClient.kt:119-140`, `AnonymousIdentity.kt:30-60` |
| **Integridade e Licenciamento Anônimo** | Identificador anônimo gerado aleatoriamente (UUIDv4) e Integrity Token | API do KeyFlow (`keyflow-api.arateki.com/v1/register`) e Google Play Integrity API | Na primeira tentativa de compra ou restauração de compra | UUIDv4 gerado localmente e persistido em SharedPreferences e Blockstore; a API guarda o UUID e o estado de entitlements (sem vincular a nome, e-mail ou dados pessoais) | Nenhuma credencial pessoal. Token assinado emitido pelo Google Play Integrity | `AnonymousIdentity.kt:31-60`, `KeyFlowApiClient.kt:159-170` |
| **Anúncios no App de Configuração** | Identificadores de publicidade (Advertising ID / IP / telemetria padrão do Google Mobile Ads SDK) | Google AdMob (`google.com`) | Ao abrir o app de configuração (`SetupActivity`), apenas se o usuário NÃO comprou a remoção de anúncios | Coleta e retenção regidas pela política de privacidade do Google / AdMob. Interrompido imediatamente se `adsOff == true` | SDK do Google Mobile Ads. Consentimento gerenciado pelo Google UMP | `AdsController.kt:52-87`, `AndroidManifest.xml:26-27` |
| **Gerenciamento de Consentimento (UMP)** | Status de consentimento do usuário (LGPD / GDPR) | Google User Messaging Platform (UMP) | Na primeira abertura do app de configuração com anúncios ativos | Armazenado localmente em SharedPreferences pelo SDK do UMP e servidores de consentimento do Google | Serviços do Google Play | `AdsController.kt:97-110` |

---

## 2. O Que Fica Exclusivamente no Aparelho (Zero Saída de Rede)

1. **Digitação e Toques no Teclado (Keystrokes):**
   - O teclado Canvas (`KeyFlowKeyboardService`) processa cada tecla tocada exclusivamente na memória do processo do IME.
   - Os caracteres são entregues diretamente ao campo de texto em foco através da interface de sistema `InputConnection.commitText()` ou `setComposingText()`.
   - **Nenhum caractere digitado, palavra sugerida, correção ortográfica ou toque é enviado para a rede ou gravado em logs em builds de produção** (`KeyFlowKeyboardService.kt:582,683,722`).
2. **Transcrição de Voz Local (Offline via sherpa-onnx):**
   - Quando o usuário seleciona a origem de voz **Local**, o áudio é processado inteiramente no dispositivo no processo isolado `:inference`.
   - Nenhum byte de áudio ou texto resultante sai do aparelho (`RoutingTranscriber.kt:23-24`, `LocalTranscriber.kt:70-130`).
3. **Raciocínio Local (Offline via LiteRT-LM):**
   - Modelos locais (Gemma 4 E2B, Qwen3 0.6B) rodam 100% no dispositivo no processo isolado `:reasoning`.
   - Nenhum texto de entrada ou saída sai do aparelho (`LocalReasoningEngine.kt:34-80`).
4. **Chaves de API e Credenciais de Autenticação:**
   - Chaves da OpenAI, Groq, Anthropic, Google AI Studio e tokens de acesso OAuth são cifrados usando o Android Keystore (chave mestra AES-256-GCM) no arquivo `keyflow_auth`.
   - São excluídos explicitamente das regras de backup do Android (`@xml/backup_rules`, `@xml/data_extraction_rules`), garantindo que não sejam transferidos para backups em nuvem (`CredentialStore.kt:33-145`).
5. **Área de Transferência (Clipboard Fallback):**
   - Caso uma transcrição seja concluída e não haja campo de texto aberto para inserção, o texto transcrito é copiado para o Clipboard local do sistema operacional como contingência. Não há sincronização externa desse conteúdo (`OverlayCoordinator.kt:588-592`).

---

## 3. Mapeamento de Permissões do AndroidManifest.xml

| Permissão | Finalidade Técnica | Por que é estritamente necessária |
|---|---|---|
| `android.permission.RECORD_AUDIO` | Captura do áudio do microfone | Essencial para o funcionamento do teclado de voz (ditado sob demanda do usuário). |
| `android.permission.INTERNET` | Comunicação HTTPS segura com a internet | Usado para: (1) envio de áudio e prompts para os endpoints de nuvem configurados pelo usuário; (2) download dos arquivos de modelos de voz/texto do Hugging Face; (3) validação da compra única na API do KeyFlow; (4) carregamento de anúncios AdMob no app de configuração. |
| `android.permission.FOREGROUND_SERVICE` | Execução de serviços em primeiro plano | Necessário a partir do Android 9 para manter serviços essenciais ativos com notificação ao usuário. |
| `android.permission.FOREGROUND_SERVICE_MICROPHONE` | Gravação de voz em segundo plano/overlay | O `OverlayService` captura o áudio enquanto o usuário está com o teclado ou a orbe aberta sobre outros aplicativos (ex: WhatsApp, navegadores). Exige notificação contínua visível ao usuário. |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | Download de modelos de inteligência artificial | O `ModelDownloadService` baixa modelos de 40 MB a 200 MB+ em segundo plano com notificação de progresso na barra de status, evitando que o sistema mate o download se o usuário alternar de app. |
| `android.permission.SYSTEM_ALERT_WINDOW` | Desenho da orbe flutuante sobre outros apps | Permite exibir a orbe flutuante do KeyFlow sobre qualquer tela para acionamento rápido do microfone e controle de gravação. |
| `android.permission.ACCESS_NETWORK_STATE` | Consulta ao tipo de conexão (Wi-Fi vs Dados Móveis) | O downloader de modelos verifica se a conexão é medida antes de iniciar o download de modelos pesados, prevenindo consumo indesejado do plano de dados (DLM-04). |
| `android.permission.POST_NOTIFICATIONS` | Exibição de notificações no Android 13+ (API 33+) | Obrigatório para exibir as notificações contínuas dos serviços em primeiro plano de gravação (`OverlayService`) e de progresso de download (`ModelDownloadService`). |
| `android.permission.VIBRATE` | Resposta tátil (Haptic feedback) | Fornece retorno tátil discreto ao tocar nas teclas do teclado Canvas e em gestos na orbe. |

---

## 4. Serviços em Primeiro Plano (Foreground Services)

O aplicativo declara e utiliza dois tipos de serviços em primeiro plano, em conformidade com as políticas do Android 14+ (targetSdk 36):

### 4.1. `OverlayService` (`foregroundServiceType="microphone"`)
- **Propósito:** Captura contínua do áudio do microfone para ditado por voz enquanto o usuário interage com outros aplicativos. Como o teclado (IME) roda em uma janela de sistema, o microfone precisa operar fora da Activity principal.
- **Início:** Iniciado exclusivamente por ação explícita do usuário (toque no botão de microfone do teclado ou toque na orbe flutuante).
- **Notificação:** Exibe uma notificação contínua ("KeyFlow — Ditado por voz ativo") no canal `keyflow_overlay` com botão de pausar/parar.
- **Encerramento:** Finaliza imediatamente quando a gravação é encerrada, cancelada ou quando o áudio é enviado para transcrição.

### 4.2. `ModelDownloadService` (`foregroundServiceType="dataSync"`)
- **Propósito:** Download em segundo plano de modelos locais de transcrição (sherpa-onnx Whisper/Parakeet) e raciocínio (LiteRT-LM Gemma/Qwen) a partir do Hugging Face.
- **Início:** Iniciado exclusivamente quando o usuário solicita o download de um modelo na tela de configurações.
- **Notificação:** Exibe notificação contínua ("Baixando modelo de voz... XX%") com barra de progresso visual.
- **Encerramento:** Encerra-se automaticamente assim que o download é concluído ou se o usuário cancelar a operação.

---

## 5. Riscos de Reprovação na Google Play Store e Mitigações

| Fator de Risco | Motivo de Escrutínio do Revisor da Play Store | Mitigação Implementada no App |
|---|---|---|
| **Combinação IME + Microfone + Internet** | Sinal comum de apps espiões (spyware): um teclado que pode ler teclas, gravar áudio e acessar a internet. | 1. **Prominent Disclosure no Onboarding:** Tela inicial com aviso explícito e consentimento obrigatório informando que o áudio só é gravado sob demanda e que nenhuma tecla é transmitida.<br>2. **Isolamento de Código:** A classe `KeyFlowKeyboardService` não possui nenhuma referência a bibliotecas de rede nem envia texto digitado.<br>3. **Indicador Visual:** A orbe e o microfone pulsam com indicador de gravação ativo visível em tela cheia e notificação em primeiro plano. |
| **Logins por Assinatura de Terceiros (ChatGPT, Claude, Google)** | O revisor pode questionar se o app usa métodos não oficiais de login ou se captura senhas do usuário. | 1. **OAuth 2.0 com PKCE Padrão:** O app abre o navegador do sistema (`AndroidBrowserLauncher.kt`) na URL oficial do provedor (ex.: `accounts.google.com`, `claude.com`, `chatgpt.com`). O app nunca toca na senha do usuário.<br>2. **Cliente Público:** Sem segredos de cliente (`client_secret`) no APK.<br>3. **Armazenamento Seguro:** Tokens são salvos exclusivamente no Keystore local (`keyflow_auth`) e nunca passam pelos servidores da Arateki. |
| **Envio de Texto para Provedores de IA** | A política da Play Store exige declarar o envio de conteúdo gerado pelo usuário para nuvem no Data Safety. | Declarado explicitamente no Data Safety como "Mensagens/Conteúdo do usuário", coletado sob demanda, criptografado em trânsito (HTTPS) e compartilhado apenas com o provedor que o usuário escolheu. |
| **Uso de Permissão `SYSTEM_ALERT_WINDOW`** | Apps com janelas flutuantes sofrem análise para evitar sobreposição enganosa de telas (tapjacking/clickjacking). | A orbe é transparente, arrastável, recolhível e possui opção de desativação instantânea nas configurações e na barra de notificações. |
| **Anúncios em Teclados** | A Google Play proíbe anúncios intrusivos ou acidentais em áreas de digitação e sobreposições. | Os anúncios AdMob estão **100% restritos à `SetupActivity` (tela de configurações do app)**. O teclado Canvas e a orbe flutuante possuem ZERO componentes de anúncio. Com a compra de R$ 15, os anúncios são desativados de forma permanente. |
| **Declaração de Serviços em Primeiro Plano (FGS)** | Desde o Android 14, o Google Play exige justificativa detalhada e vídeo demonstrativo para FGS de microfone e sincronização de dados. | Roteiro de vídeo e justificativas técnicas preparadas para envio no formulário do Play Console (ver `docs/loja/listing.md`). |
