# Inventário de Dados — KeyFlow (v1)

> Documento técnico de referência para a política de privacidade, a declaração de segurança de dados (Data Safety Form) e a submissão do KeyFlow na Google Play Store. Baseado na análise do código-fonte da versão 1.0.0.

---

## 1. Tabela Mestra de Fluxo de Dados (O que sai do aparelho)

| Funcionalidade | Dado que sai | Destino | Quando sai | Retenção / Armazenamento | Chave / Conta usada | Citação no Código |
|---|---|---|---|---|---|---|
| **Transcrição de Voz na Nuvem (STT)** | Arquivo de áudio gravado (`audio/m4a`, 16 kHz AAC) | Provedor escolhido pelo usuário: OpenAI (`api.openai.com`) ou Groq (`api.groq.com`) ou endpoint customizado compatível | Apenas sob demanda explícita (toque no botão de microfone do teclado ou orbe) | No aparelho: temporário em cache (`cacheDir/keyflow-recordings/`), apagado imediatamente após envio/conclusão, erro ou cancelamento (janela de desfazer de 3s). Limpeza de arquivos residuais > 24h na inicialização. No provedor: conforme termos do terceiro | Chave de API do próprio usuário (BYOK), guardada no Android Keystore (`keyflow_auth`) | `AudioRecorder.kt:187-193`, `OverlayCoordinator.kt:552-555`, `WhisperApi.kt:20-33`, `Settings.kt:281-283` |
| **Raciocínio em Nuvem (LLM)** | Texto do prompt enviado para teste ou geração | Provedor escolhido pelo usuário: OpenAI (`api.openai.com/v1/responses`), Anthropic (`api.anthropic.com/v1/messages`), ou Google (`generativelanguage.googleapis.com`) | Apenas sob demanda do usuário ao testar/acionar raciocínio nas configurações | No aparelho: efêmero (não há histórico mantido pelo app). No provedor: OpenAI usa `store: false` (retenção para abuso por até 30 dias sob termos do provedor); Anthropic e Google retêm segundo suas políticas | Chave de API do usuário ou token OAuth via login por assinatura (ChatGPT, Claude, Google). Tokens cifrados no Keystore local e transmitidos diretamente ao provedor | `OpenAiReasoningEngine.kt:46-120`, `AnthropicReasoningEngine.kt:40-100`, `GoogleReasoningEngine.kt:40-100`, `CredentialStore.kt:33-145` |
| **Customização Assistida / Agente (Em breve na v1)** | Mensagens de texto e parâmetros de ferramentas (metadados de skins instaladas e imagens de prévia geradas pelo app) | Anthropic via chave de API | Apenas sob demanda ao acionar o assistente nas configurações | Processamento e parâmetros técnicos enviados à Anthropic. Rate limit de 24 chamadas/minuto e segredos do cofre mascarados antes do envio | Chave de API da Anthropic configurada pelo usuário | `ToolExecutor.kt:63-95,242-251`, `SkinTools.kt:169,205`, `AgentSecrets.kt:20-80` |
| **Download de Modelos Locais (Voz e Raciocínio)** | Requisição HTTP GET para download dos arquivos de pesos dos modelos | Hugging Face (`huggingface.co`) ou espelhos/URLs remotas definidas no catálogo | Apenas sob demanda do usuário ao clicar para baixar um modelo offline | Arquivos salvos no armazenamento local do app (`context.filesDir/models/`). Excluídos se o usuário desinstalar ou apagar dados | Nenhuma conta necessária (repositórios públicos abertos) | `ModelCatalog.kt:71-73,490`, `ModelDownloader.kt:195`, `ModelDownloadService.kt:30-90` |
| **Configuração Remota e Catálogo** | Requisições HTTP GET para atualização de catálogo e parâmetros de anúncios | API da Arateki (`keyflow-api.arateki.com/v1/config`, `/v1/models`) | Ao abrir a tela de configurações (`SetupActivity`) | Nenhum dado pessoal do usuário é enviado; a resposta atualiza configurações locais de modelos e anúncios | Sem autenticação pessoal | `RemoteConfigRepository.kt:109-110` |
| **Compra Única (R$ 15 — Desbloqueio e Remoção de Anúncios)** | `purchaseToken`, `productId` ("unlock_forever"), token de integridade e hash do UUID | Google Play Billing e API própria da Arateki (`keyflow-api.arateki.com/v1/purchases/redeem`) | No momento em que o usuário confirma a compra na folha de pagamento | Google Play processa o pagamento; a API da Arateki valida o recibo e registra o status de desbloqueio para a identidade anônima. Logs técnicos de acesso e erro na API são retidos por 30 dias e apagados automaticamente. Exclusão do registro na API pelo botão 'Apagar meus dados' na aba Privacidade (apaga na hora o registro do aparelho; a compra continua no Google Play e volta ao restaurar) ou via contato@arateki.com (prazo de até 15 dias) | Conta Google do usuário no Google Play; token de acesso anônimo na API da Arateki | `BillingManager.kt:147,328`, `KeyFlowApiClient.kt:119-140`, `AnonymousIdentity.kt:30-60` |
| **Integridade e Licenciamento Anônimo** | Identificador anônimo gerado aleatoriamente (UUIDv4) e Play Integrity Token | API da Arateki (`keyflow-api.arateki.com/v1/register`, `/v1/account`) | Automaticamente após o onboarding e em consultas periódicas de direitos na `SetupActivity` | UUIDv4 gerado localmente, persistido em arquivo e Google Block Store; a API guarda o UUID e o estado de entitlements (sem vincular a dados pessoais). A compra não interrompe essa consulta. Logs mantidos por 30 dias; registro apagável pelo botão 'Apagar meus dados' no app ou via e-mail | Nenhuma credencial pessoal. Token assinado emitido pelo Google Play Integrity | `SetupActivity.kt:295,744,751`, `AnonymousIdentity.kt:30-60`, `KeyFlowApiClient.kt:104,159` |
| **Anúncios no App de Configuração** | Identificadores de publicidade (Advertising ID / IP / telemetria e localização aproximada derivada de IP) | Google AdMob (`google.com`) | Ao abrir a tela de configurações (`SetupActivity`), apenas se o usuário NÃO comprou a remoção de anúncios | Coleta e retenção regidas pela política do Google / AdMob. Interrompido imediatamente se `adsOff == true` | SDK do Google Mobile Ads. Consentimento gerenciado pelo Google UMP | `AdsController.kt:52-87`, `AndroidManifest.xml:26-27` |
| **Gerenciamento de Consentimento (UMP)** | Status de consentimento do usuário (LGPD / GDPR) | Google User Messaging Platform (UMP) | Na primeira abertura com anúncios ativos ou ao clicar em "Privacidade dos anúncios" na aba Privacidade | Armazenado localmente em SharedPreferences pelo SDK do UMP e servidores de consentimento do Google | Serviços do Google Play | `AdsController.kt:97-110`, `AdsPrivacyOptionsEntry.kt:20-35`, `PrivacyPanel.kt:63` |
| **Suporte Técnico por E-mail (Voluntário)** | Informações técnicas do dispositivo (modelo, versão do Android, versão do app e logs opcionais) | Cliente de e-mail do usuário / `contato@arateki.com` | Apenas se o usuário clicar voluntariamente no link de suporte na aba Docs | Mensagem enviada pelo próprio aplicativo de e-mail do usuário; não é telemetria automática em segundo plano | Conta de e-mail do próprio usuário | `DocsPanel.kt:121,141` |

---

## 2. O Que Fica Exclusivamente no Aparelho (Zero Saída de Rede)

1. **Digitação e Toques no Teclado (Keystrokes):**
   - O teclado Canvas (`KeyFlowKeyboardService`) processa cada tecla tocada exclusivamente na memória do processo do IME.
   - Os caracteres são entregues diretamente ao campo de texto em foco através da interface de sistema `InputConnection.commitText()` ou `setComposingText()`.
   - **Nenhum caractere digitado, palavra sugerida, correção ortográfica ou toque é enviado para a rede ou gravado em logs em builds de produção** (`KeyFlowKeyboardService.kt:582,683,722`).
   - O corretor ortográfico utiliza dicionários estáticos integrados (`Dictionaries.kt`), sem aprendizado de termos ou registro do vocabulário pessoal do usuário.
2. **Transcrição de Voz Local (Offline via sherpa-onnx):**
   - Quando o usuário seleciona a origem de voz **Local**, o áudio é processado inteiramente no dispositivo no processo isolado `:inference`.
   - Nenhum byte de áudio ou texto resultante sai do aparelho (`RoutingTranscriber.kt:23-24`, `LocalTranscriber.kt:70-130`).
3. **Raciocínio Local (Offline via LiteRT-LM):**
   - Modelos locais (Gemma 4 E2B e E4B, Qwen3 0.6B, Qwen2.5 1.5B, SmolLM2 1.7B, DeepSeek R1 1.5B) rodam 100% no dispositivo no processo isolado `:reasoning`.
   - Nenhum texto de entrada ou saída sai do aparelho (`LocalReasoningEngine.kt:34-80`).
4. **Chaves de API e Credenciais de Autenticação:**
   - Chaves de API e tokens de acesso OAuth são cifrados e protegidos pelo Android Keystore (chave mestra AES-256-GCM) no arquivo `keyflow_auth`.
   - São excluídos explicitamente das regras de backup do Android (`@xml/backup_rules`, `@xml/data_extraction_rules`), garantindo que não sejam transferidos para backups em nuvem (`CredentialStore.kt:33-145`).
5. **Importação de Pacotes de Temas (.zip):**
   - O aplicativo suporta importação local de temas em arquivo `.zip` (`SkinPacksSection.kt`). O arquivo é descompactado e validado estritamente no armazenamento local do dispositivo, sem qualquer upload para a internet.
6. **Área de Transferência (Clipboard Fallback):**
   - Caso uma transcrição seja concluída e não haja campo de texto aberto para inserção, o texto transcrito é copiado para a área de transferência local do sistema operacional como contingência (`OverlayCoordinator.kt:588-592`).

---

## 3. Mapeamento de Permissões do AndroidManifest.xml

| Permissão | Finalidade Técnica | Por que é estritamente necessária |
|---|---|---|
| `android.permission.RECORD_AUDIO` | Captura do áudio do microfone | Essencial para o funcionamento do teclado de voz (ditado sob demanda do usuário). |
| `android.permission.INTERNET` | Comunicação HTTPS segura com a internet | Usado para: (1) envio de áudio e prompts para os endpoints de nuvem configurados pelo usuário; (2) download dos arquivos de modelos de voz/texto do Hugging Face ou espelhos; (3) validação da compra e consulta de licenças na API da Arateki; (4) carregamento de anúncios AdMob no app de configuração. |
| `android.permission.FOREGROUND_SERVICE` | Execução de serviços em primeiro plano | Necessário a partir do Android 9 para manter serviços essenciais ativos com notificação ao usuário. |
| `android.permission.FOREGROUND_SERVICE_MICROPHONE` | Gravação de voz em primeiro plano/overlay | O `OverlayService` captura o áudio enquanto o usuário dita texto sobre outros aplicativos ou com o teclado aberto. Exige notificação contínua visível ao usuário. |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | Download de modelos de inteligência artificial | O `ModelDownloadService` baixa modelos em primeiro plano com notificação de progresso na barra de status, evitando que o sistema interrompa o download caso o usuário minimize o app. |
| `android.permission.SYSTEM_ALERT_WINDOW` | Desenho da orbe flutuante sobre outros apps | Permite exibir a orbe flutuante do KeyFlow sobre qualquer tela para acionamento rápido do microfone e controle de gravação. |
| `android.permission.ACCESS_NETWORK_STATE` | Consulta ao tipo de conexão (Wi-Fi vs Dados Móveis) | O downloader de modelos verifica se a conexão é medida antes de iniciar o download de modelos pesados, prevenindo consumo indesejado do plano de dados. |
| `android.permission.POST_NOTIFICATIONS` | Exibição de notificações no Android 13+ (API 33+) | Obrigatório para exibir as notificações contínuas dos serviços em primeiro plano de gravação (`OverlayService`) e de download (`ModelDownloadService`). |
| `android.permission.VIBRATE` | Resposta tátil (Haptic feedback) | Fornece retorno tátil discreto ao tocar nas teclas do teclado Canvas e em gestos na orbe. |

---

## 4. Serviços em Primeiro Plano (Foreground Services)

O aplicativo declara e utiliza dois tipos de serviços em primeiro plano, em conformidade com as políticas do Android 14+ (targetSdk 36):

### 4.1. `OverlayService` (`foregroundServiceType="microphone"`)
- **Propósito:** Captura contínua de áudio para ditado por voz enquanto o teclado opera sobre outros aplicativos, e sustentação da orbe flutuante quando o modo Sobrepor está habilitado.
- **Diferenciação por Modo de Uso:**
  - **Modo Só-Teclado (`KeyFlowKeyboardService`):** O serviço em primeiro plano é promovido sob demanda no momento do toque no botão de microfone e é **imediatamente finalizado** (`stopForeground` e `stopSelf`) assim que a transcrição termina ou o ditado é cancelado, retornando ao estado ocioso sem deixar notificação ativa.
  - **Modo Sobrepor (Orbe Flutuante ativa):** O serviço mantém a orbe desenhada na tela e exibe notificação contínua indicando o estado do serviço (com controles de pausar/parar). O microfone grava **estritamente durante o ditado ativo**, cessando a captação quando a gravação é encerrada.
- **Início:** Sempre iniciado por ação direta do usuário.
- **Notificação:** Notificação contínua ("KeyFlow — Ditado por voz ativo") no canal `keyflow_overlay`.

### 4.2. `ModelDownloadService` (`foregroundServiceType="dataSync"`)
- **Propósito:** Download em segundo plano de modelos locais de transcrição (sherpa-onnx) e raciocínio (LiteRT-LM) a partir do Hugging Face ou espelhos oficiais.
- **Início:** Iniciado exclusivamente quando o usuário solicita o download de um modelo na tela de configurações.
- **Notificação:** Notificação contínua com barra de progresso visual ("Baixando modelo... XX%").
- **Encerramento:** Finaliza-se automaticamente ao concluir a verificação do arquivo ou caso o usuário cancele.

---

## 5. Riscos de Reprovação na Google Play Store e Mitigações

| Fator de Risco | Motivo de Escrutínio do Revisor da Play Store | Mitigação Implementada no App |
|---|---|---|
| **Combinação IME + Microfone + Internet** | Sinal comum de apps espiões (spyware): um teclado que pode ler teclas, gravar áudio e acessar a internet. | 1. **Gate Estrito de Divulgação em Destaque (Prominent Disclosure):** O aceite da versão atual da divulgação é exigido no onboarding E antes de qualquer início de captura de áudio no teclado, orbe ou notificação (`ca4f946`). Se o usuário não aceitou a versão vigente (inclusive após atualização), a gravação é bloqueada e a tela de aceite é aberta.<br>2. **Isolamento de Código:** A classe `KeyFlowKeyboardService` não possui nenhuma referência a bibliotecas de rede nem envia texto digitado.<br>3. **Indicador Visual:** A orbe e o teclado exibem indicadores visuais claros de gravação em andamento com notificação persistente do sistema. |
| **Logins por Assinatura de Terceiros (ChatGPT, Claude, Google)** | O revisor pode questionar se o app usa métodos não oficiais de login ou se captura senhas do usuário. | 1. **OAuth 2.0 com PKCE Padrão:** O app abre o navegador do sistema (`AndroidBrowserLauncher.kt`) na URL oficial do provedor. O app nunca toca na senha do usuário.<br>2. **Cliente Público:** Sem segredos de cliente (`client_secret`) no APK.<br>3. **Armazenamento Seguro:** Tokens são salvos no Keystore local (`keyflow_auth`) e nunca passam pelos servidores da Arateki. |
| **Envio de Texto e Áudio para Provedores de IA** | A política da Play Store exige declarar o envio de conteúdo gerado pelo usuário para a nuvem no Data Safety. | Declarado explicitamente no Data Safety como Áudio e Mensagens, coletado sob demanda, criptografado em trânsito (HTTPS) e compartilhado apenas com os provedores que o usuário configurou. |
| **Reversibilidade de Consentimento de Anúncios** | Diretriz do Google UMP exige caminho acessível para rever opções de consentimento a qualquer momento. | Implementado botão dedicado **"Privacidade dos anúncios"** na aba Privacidade da tela de configurações (`85404da`), abrindo o formulário oficial do UMP. |
| **Exclusão de Gravações de Áudio** | Risco de acúmulo de arquivos de voz em cache do dispositivo. | Arquivo temporário de áudio é apagado imediatamente após a entrega do texto transcrito, falha ou cancelamento (`18397a7`), com rotina na inicialização do app que limpa arquivos residuais com mais de 24 horas. |
| **Uso de Permissão `SYSTEM_ALERT_WINDOW`** | Apps com janelas flutuantes sofrem análise para evitar sobreposição enganosa de telas (tapjacking/clickjacking). | A orbe é transparente, arrastável, recolhível e possui opção de desativação instantânea nas configurações e na barra de notificações. |
| **Anúncios em Teclados** | A Google Play proíbe anúncios em áreas de digitação. | Os anúncios AdMob estão **100% restritos à `SetupActivity` (tela de configurações)**. O teclado Canvas e a orbe possuem ZERO componentes de anúncio. Com a compra de R$ 15, os anúncios são desativados de forma permanente. |
| **Declaração de Serviços em Primeiro Plano (FGS)** | Desde o Android 14, o Google Play exige justificativa detalhada e roteiros de vídeo para FGS de microfone e sincronização de dados. | Roteiros de vídeo e justificativas técnicas separando modo só-teclado e modo sobrepor detalhados em `docs/loja/listing.md`. |
