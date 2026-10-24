# Formulário de Segurança dos Dados (Data Safety) — Google Play Console

> Este documento contém o preenchimento exato, campo a campo, na ordem solicitada pelo formulário **Segurança dos dados (Data Safety)** do Google Play Console para o **KeyFlow** (versão 1.0.0).  
> Todas as respostas são justificadas com a citação exata de arquivo e linha no código-fonte.

---

## Seção 1: Visão Geral de Coleta e Segurança (Overview)

### 1.1. O seu app coleta ou compartilha algum dos tipos de dados de usuário obrigatórios?
- **Resposta:** **Sim**
- **Justificativa:** O aplicativo transmite arquivos de áudio gravados para transcrição por voz sob demanda quando configurado em nuvem (`WhisperApi.kt:20-33`), textos de prompts para provedores de raciocínio de IA configurados pelo usuário (`OpenAiReasoningEngine.kt:46-120`, `AnthropicReasoningEngine.kt:40-100`, `GoogleReasoningEngine.kt:40-100`), dados de compra e hash do identificador para o Google Play Billing / API do KeyFlow (`BillingManager.kt:147,328`, `KeyFlowApiClient.kt:104,159`), identificador anônimo (UUIDv4) e Play Integrity Token para verificação de autenticidade/licenças (`AnonymousIdentity.kt:30-60`, `KeyFlowApiClient.kt:104,159`) e integra o SDK do Google Mobile Ads (AdMob) na tela de configurações (`AdsController.kt:52-87`).

### 1.2. Todos os dados de usuário coletados pelo seu app são criptografados em trânsito?
- **Resposta:** **Sim**
- **Justificativa:** Toda a comunicação de rede do aplicativo é realizada exclusivamente por meio do protocolo HTTPS/TLS, com certificados válidos e validação estrita de segurança configurada no manifesto (`app/src/main/res/xml/network_security_config.xml`, `WhisperApi.kt:29-33`, `KeyFlowApiClient.kt:150-170`).

### 1.3. O seu app oferece aos usuários uma forma de solicitar a exclusão dos dados?
- **Resposta:** **Sim**
- **Justificativa:** 
  1. O áudio do ditado é temporário e excluído automaticamente do aparelho após o envio/entrega do texto, em caso de erro na transcrição ou ao expirar a janela de cancelamento de 3 segundos (`OverlayCoordinator.kt:555`, `AudioRecorder.kt:187-193`).
  2. Todos os dados locais (chaves de API no Android Keystore, modelos locais de IA, configurações) podem ser excluídos pelo próprio usuário limpando os dados do aplicativo nas Configurações do Android ou desinstalando o app.
  3. A exclusão dos dados mantidos na API da Arateki (`keyflow-api.arateki.com`) pode ser realizada diretamente dentro do app pelo botão **"Apagar meus dados"** na aba **Privacidade** das configurações (apaga na hora o registro do aparelho; a compra continua no Google Play e volta ao restaurar) ou por solicitação via e-mail para `contato@arateki.com` (com resposta em até 15 dias conforme a LGPD). Para dados mantidos por provedores terceiros (OpenAI, Anthropic, Google), o usuário pode solicitar a exclusão diretamente no painel de cada provedor.

---

## Seção 2: Declaração Detalhada por Tipo de Dado

---

### Categoria A: Áudio (Audio Files)

#### Subtipo: Gravações de voz ou som (Voice or sound recordings)
- **Coletado?** **Sim**
- **Compartilhado?** **Sim** (Compartilhado diretamente com o endpoint de API configurado pelo próprio usuário: OpenAI, Groq ou servidor customizado).
- **Finalidade:** **Funcionalidade do app (App functionality)** — Conversão de fala em texto (ditado por voz sob demanda).
- **Processamento efêmero (Ephemeral processing)?** **Sim no dispositivo** (O áudio gravado é temporariamente armazenado em cache no dispositivo e excluído imediatamente após o término da transcrição, erro ou expiração do cancelamento; sobras órfãs de 24 h são limpas na inicialização). **Nota sobre terceiros:** Nos servidores dos provedores de nuvem (OpenAI, Groq), a retenção rege-se pelos termos de serviço de cada provedor e configurações da conta/chave do usuário. O usuário pode optar pelo motor offline/local (`sherpa-onnx`) onde nenhum áudio sai do aparelho.
- **A coleta é obrigatória ou opcional?** **Opcional** (O usuário decide se deseja utilizar a transcrição por voz ou utilizar apenas a digitação manual; se utilizar o ditado, pode optar pelo motor offline/local sem envio de rede).
- **Criptografado em trânsito?** **Sim** (HTTPS/TLS).
- **O usuário pode solicitar a exclusão?** **Sim** (O arquivo é deletado imediatamente no aparelho; em provedores terceiros, o usuário gerencia a exclusão em suas respectivas contas).
- **Citação no código:**
  - `AudioRecorder.kt:187-193` (gravação em cache temporário e limpeza de arquivos antigos)
  - `OverlayCoordinator.kt:552-555` (exclusão imediata do áudio após transcrição)
  - `WhisperApi.kt:20-33` (envio multipart/form-data via HTTPS para o endpoint configurado)
  - `RoutingTranscriber.kt:23-24` (roteamento para motor local offline sem envio de rede quando configurado)

---

### Categoria B: Mensagens (Messages)

#### Subtipo: Outras mensagens no app (Other in-app messages)
*(Abrange prompts e textos enviados pelo usuário para modelos de inteligência artificial em nuvem)*
- **Coletado?** **Sim**
- **Compartilhado?** **Sim** (Transmitido diretamente do dispositivo para os servidores da OpenAI, Anthropic ou Google, dependendo do provedor que o usuário escolheu e configurou).
- **Finalidade:** **Funcionalidade do app (App functionality)** — Geração de texto e raciocínio de IA solicitados pelo usuário.
- **Processamento efêmero?** O aplicativo KeyFlow não mantém banco de dados de histórico de mensagens em servidores próprios. Na OpenAI, é passado o parâmetro `store: false` na API; contudo, a retenção remota (ex.: monitoramento de abusos por até 30 dias na OpenAI) e as políticas da Anthropic e do Google dependem dos termos de cada provedor. Em chamadas com ferramentas na Anthropic, metadados de skins e imagens de prévia geradas podem ser transmitidos como parâmetros técnicos.
- **A coleta é obrigatória ou opcional?** **Opcional** (O usuário pode utilizar modelos locais offline pelo LiteRT-LM sem conexão de rede, ou optar por não utilizar funções de raciocínio).
- **Criptografado em trânsito?** **Sim** (HTTPS/TLS).
- **O usuário pode solicitar a exclusão?** **Sim** (No KeyFlow não há armazenamento de mensagens. O usuário pode gerenciar a exclusão no painel do respectivo provedor).
- **Citação no código:**
  - `OpenAiReasoningEngine.kt:46-120` (requisição com `store: false`)
  - `AnthropicReasoningEngine.kt:40-100` (mensagens via Messages API)
  - `GoogleReasoningEngine.kt:40-100` (conteúdo via Generative Language API)
  - `SkinTools.kt:169,205` (ferramentas de prévia e metadados de skins)

---

### Categoria C: Informações Pessoais (Personal Info)

#### Subtipo: Informações de login / E-mail / Identificadores pessoais
- **Coletado pela Arateki?** **Não**
- **Compartilhado com terceiros?** **Não enviado à Arateki; transmitido aos provedores de autenticação correspondentes**
- **Esclarecimento técnico sobre Login com ChatGPT / Claude / Google:**
  - O aplicativo implementa fluxo padrão OAuth 2.0 com PKCE via navegador do sistema (`AndroidBrowserLauncher.kt:15`, `OpenAiChatGptFlow.kt:31`, `AnthropicSsoFlow.kt:26`, `GoogleSsoFlow.kt:30`).
  - As credenciais e tokens de acesso são armazenados **estritamente dentro do dispositivo** no cofre seguro do Android Keystore (`keyflow_auth`).
  - **Nenhum token ou credencial é enviado para servidores da Arateki.** Ao executar requisições para a OpenAI, Anthropic ou Google, os tokens de acesso ou chaves são enviados nos cabeçalhos HTTP HTTPS diretamente e exclusivamente aos servidores de cada provedor para autenticar a chamada.
- **Citação no código:**
  - `CredentialStore.kt:33-145` (persistência criptografada em Keystore local)
  - `app/src/main/res/xml/backup_rules.xml:4-8` (exclusão explícita de credenciais do backup em nuvem)

---

### Categoria D: Informações Financeiras (Financial Info)

#### Subtipo: Histórico de compras do usuário (Purchase history)
- **Coletado?** **Sim**
- **Compartilhado?** **Sim** (Processado pelo Google Play Billing e token validado pela API da Arateki).
- **Finalidade:** **Funcionalidade do app (App functionality)** e **Prevenção contra fraudes / segurança**.
- **A coleta é obrigatória ou opcional?** **Opcional** (Aplicável apenas aos usuários que adquirem o desbloqueio permanente de temas de R$ 15).
- **Criptografado em trânsito?** **Sim** (HTTPS/TLS).
- **O usuário pode solicitar a exclusão?** **Sim**.
- **Citação no código:**
  - `BillingManager.kt:147,328` (comunicação com a biblioteca Google Play Billing 7+ e envio de hash do UUID)
  - `KeyFlowApiClient.kt:119-140` (envio do `purchaseToken` para validação em `keyflow-api.arateki.com/v1/purchases/redeem`)

---

### Categoria E: Dispositivo ou Outros Identificadores (Device or Other IDs)

#### Subtipo: ID do dispositivo ou outros identificadores
- **Coletado?** **Sim**
- **Compartilhado?** **Sim**
- **Finalidades:**
  1. **Funcionalidade do app (App functionality):** Identificador anônimo gerado aleatoriamente (UUIDv4) no dispositivo e token de integridade (Google Play Integrity) enviados à API da Arateki (`keyflow-api.arateki.com/v1/register`, `v1/account`) automaticamente após a conclusão do onboarding para verificar autenticidade e status de desbloqueio de temas e anúncios (`AnonymousIdentity.kt:30-60`, `KeyFlowApiClient.kt:104,159`). A compra de R$ 15 não encerra a verificação periódica de direitos.
  2. **Publicidade ou marketing (Advertising or marketing):** O SDK do Google Mobile Ads (AdMob) coleta o ID de publicidade do Google (Advertising ID) e telemetria na versão gratuita para exibição de anúncios na tela de configurações (`SetupActivity`).
- **A coleta é obrigatória ou opcional?** O UUID anônimo é gerado para gestão de integridade/licenças. A publicidade é opcional e removida definitivamente com a compra de R$ 15; preferências de consentimento gerenciadas pelo Google UMP com opção de revisão a qualquer momento ("Privacidade dos anúncios" na aba Privacidade).
- **Criptografado em trânsito?** **Sim** (HTTPS/TLS).
- **O usuário pode solicitar a exclusão?** **Sim** (Disponível no próprio app pelo botão "Apagar meus dados" na aba Privacidade — exclusão imediata do registro do aparelho nos servidores —, e por e-mail para `contato@arateki.com` com resposta em até 15 dias. A limpeza de dados do app apaga o armazenamento local; compras no Google Play são preservadas e reativadas via restauração. Logs técnicos da API são retidos por 30 dias e apagados automaticamente).
- **Citação no código:**
  - `AnonymousIdentity.kt:30-60` (UUIDv4 persistido localmente e via Block Store)
  - `KeyFlowApiClient.kt:104,159` (registro do UUID e consulta de conta na API)
  - `BillingManager.kt:147,328` (envio do hash do UUID para o Google Play Billing)
  - `AdsController.kt:52-87` (inicialização de anúncios condicionada a `adsOff == false`)
  - `AdsPrivacyOptionsEntry.kt:20-35` (revisão de consentimento UMP na aba Privacidade)

---

### Categoria F: Dados NUNCA Coletados e Esclarecimentos Adicionais

| Categoria do Play Console | Coletado? | Justificativa Técnica no Código |
|---|---|---|
| **Localização (Location)** | **Não coletado pelo app** | O app não requer nem declara permissões de GPS (`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`). O SDK do Google AdMob pode derivar localização geográfica aproximada a partir do IP na versão gratuita (gerenciado via UMP e desligado com a compra de R$ 15). |
| **Fotos e Vídeos (Photos & Videos)** | **Não** | O app não lê a galeria nem captura fotos/vídeos (`CAMERA` e `READ_MEDIA_IMAGES` ausentes). |
| **Arquivos e Documentos (Files & Docs)** | **Não** | O app suporta importação de temas em `.zip` (`SkinPacksSection.kt`), processados estritamente na memória local sem upload. Manipula apenas cache temporário e modelos baixados. |
| **Contatos (Contacts)** | **Não** | Nenhuma leitura de agenda (`READ_CONTACTS` ausente). |
| **Agenda (Calendar)** | **Não** | Nenhuma leitura de calendário. |
| **Navegação na Web (Web Browsing)** | **Não** | O app não monitora histórico de navegação. |
| **Saúde e Fitness (Health & Fitness)** | **Não** | Nenhuma coleta de dados de saúde. |
| **Digitação / Teclas (Keystrokes)** | **NÃO COLETADO** | **Ponto crítico:** O teclado Canvas (`KeyFlowKeyboardService`) entrega teclas diretamente ao sistema operacional via `InputConnection`. Nenhuma tecla ou texto digitado é registrado em arquivo ou transmitido via rede (`KeyFlowKeyboardService.kt:582,683,722`). Correção ortográfica local usa dicionários estáticos sem salvar vocabulário pessoal. |
