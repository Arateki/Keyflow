# Formulário de Segurança dos Dados (Data Safety) — Google Play Console

> Este documento contém o preenchimento exato, campo a campo, na ordem solicitada pelo formulário **Segurança dos dados (Data Safety)** do Google Play Console para o **KeyFlow** (versão 1.0.0).  
> Todas as respostas são justificadas com a citação exata de arquivo e linha no código-fonte.

---

## Seção 1: Visão Geral de Coleta e Segurança (Overview)

### 1.1. O seu app coleta ou compartilha algum dos tipos de dados de usuário obrigatórios?
- **Resposta:** **Sim**
- **Justificativa:** O aplicativo transmite arquivos de áudio gravados para transcrição por voz sob demanda (`WhisperApi.kt:20-33`), textos de prompts para provedores de raciocínio de IA configurados pelo usuário (`OpenAiReasoningEngine.kt:46-120`), dados de compra para o Google Play Billing / API do KeyFlow (`BillingManager.kt:40-160`, `KeyFlowApiClient.kt:119-140`) e integra o SDK do Google Mobile Ads (AdMob) na tela de configurações (`AdsController.kt:52-87`).

### 1.2. Todos os dados de usuário coletados pelo seu app são criptografados em trânsito?
- **Resposta:** **Sim**
- **Justificativa:** Toda a comunicação de rede do aplicativo é realizada exclusivamente por meio do protocolo HTTPS/TLS, com certificados válidos e validação estrita de segurança configurada no manifesto (`app/src/main/res/xml/network_security_config.xml`, `WhisperApi.kt:29-33`, `KeyFlowApiClient.kt:150-155`).

### 1.3. O seu app oferece aos usuários uma forma de solicitar a exclusão dos dados?
- **Resposta:** **Sim**
- **Justificativa:** 
  1. O áudio do ditado é efêmero e excluído automaticamente do aparelho após o envio ou descarte (`AudioRecorder.kt:126-127,162`, `OverlayCoordinator.kt:306`).
  2. Todos os dados locais (chaves de API no Android Keystore, modelos locais de IA, configurações) podem ser excluídos pelo próprio usuário limpando os dados do aplicativo nas Configurações do Android ou clicando em "Desconectar/Excluir" dentro do app.
  3. A solicitação de exclusão de registros anônimos na API do KeyFlow pode ser enviada para `contato@arateki.com`.

---

## Seção 2: Declaração Detalhada por Tipo de Dado

---

### Categoria A: Áudio (Audio Files)

#### Subtipo: Gravações de voz ou som (Voice or sound recordings)
- **Coletado?** **Sim**
- **Compartilhado?** **Sim** (Compartilhado diretamente com o endpoint de API configurado pelo próprio usuário: OpenAI, Groq ou servidor customizado).
- **Finalidade:** **Funcionalidade do app (App functionality)** — Conversão de fala em texto (ditado por voz).
- **Processamento efêmero (Ephemeral processing)?** **Sim** (O áudio gravado é temporariamente armazenado em cache no dispositivo e excluído imediatamente após o término da transcrição ou cancelamento pelo usuário).
- **A coleta é obrigatória ou opcional?** **Opcional** (O usuário decide se deseja utilizar a transcrição por voz ou utilizar apenas a digitação manual; se utilizar o ditado, pode optar pelo motor offline/local onde nenhum áudio sai do aparelho).
- **Criptografado em trânsito?** **Sim** (HTTPS/TLS).
- **O usuário pode solicitar a exclusão?** **Sim** (O arquivo é deletado imediatamente).
- **Citação no código:**
  - `AudioRecorder.kt:13,136-162` (gravação em cache temporário e exclusão no descarte)
  - `OverlayCoordinator.kt:295-313,565-583` (gestão do ciclo de gravação, timeout e cancelamento)
  - `WhisperApi.kt:20-33` (envio multipart/form-data via HTTPS para o endpoint configurado)
  - `RoutingTranscriber.kt:23-24` (roteamento para motor local offline sem envio de rede quando configurado)

---

### Categoria B: Mensagens (Messages)

#### Subtipo: Outras mensagens no app (Other in-app messages)
*(Abrange prompts e textos enviados pelo usuário para modelos de inteligência artificial em nuvem)*
- **Coletado?** **Sim**
- **Compartilhado?** **Sim** (Transmitido diretamente do dispositivo para os servidores da OpenAI, Anthropic ou Google, dependendo do provedor que o usuário escolheu e configurou).
- **Finalidade:** **Funcionalidade do app (App functionality)** — Geração de texto, raciocínio e assistência do Agente KeyFlow solicitada pelo usuário.
- **Processamento efêmero?** **Sim** (O texto do prompt é enviado pontualmente no momento da solicitação; na OpenAI, é passado explicitamente o parâmetro `store: false` para não retenção em histórico de API).
- **A coleta é obrigatória ou opcional?** **Opcional** (O usuário pode utilizar modelos locais offline pelo LiteRT-LM sem conexão de rede, ou optar por não utilizar funções de raciocínio).
- **Criptografado em trânsito?** **Sim** (HTTPS/TLS).
- **O usuário pode solicitar a exclusão?** **Sim** (Não há banco de dados remoto das mensagens mantido pelo KeyFlow).
- **Citação no código:**
  - `OpenAiReasoningEngine.kt:46-120` (requisição com `store: false`)
  - `AnthropicReasoningEngine.kt:40-100` (mensagens via Messages API)
  - `GoogleReasoningEngine.kt:40-100` (conteúdo via Generative Language API)
  - `AgentHost.kt:30-110` (execução do chat do agente)

---

### Categoria C: Informações Pessoais (Personal Info)

#### Subtipo: Informações de login / E-mail / Identificadores pessoais
- **Coletado pela Arateki?** **Não**
- **Compartilhado com terceiros?** **Não**
- **Esclarecimento técnico sobre Login com ChatGPT / Claude / Google:**
  - O aplicativo implementa fluxo padrão OAuth 2.0 com PKCE via navegador do sistema (`AndroidBrowserLauncher.kt:15`, `OpenAiChatGptFlow.kt:31`, `AnthropicSsoFlow.kt:26`, `GoogleSsoFlow.kt:30`).
  - O e-mail retornado no ID token do provedor e os tokens de acesso são armazenados **estritamente dentro do dispositivo** no cofre seguro do Android Keystore (`keyflow_auth`).
  - **Nenhum token ou e-mail de usuário é enviado para servidores da Arateki ou terceiros.** De acordo com as diretrizes do Play Console, dados processados e armazenados exclusivamente de forma local no dispositivo não são classificados como "coletados".
- **Citação no código:**
  - `CredentialStore.kt:33-145` (persistência criptografada em Keystore local)
  - `app/src/main/res/xml/backup_rules.xml:4-8` (exclusão explícita de credenciais do backup em nuvem)

---

### Categoria D: Informações Financeiras (Financial Info)

#### Subtipo: Histórico de compras do usuário (Purchase history)
- **Coletado?** **Sim**
- **Compartilhado?** **Sim** (Processado pelo Google Play Billing e token validado pela API do KeyFlow).
- **Finalidade:** **Funcionalidade do app (App functionality)** e **Prevenção contra fraudes / segurança**.
- **A coleta é obrigatória ou opcional?** **Opcional** (Aplicável apenas aos usuários que adquirem o desbloqueio permanente de temas de R$ 15).
- **Criptografado em trânsito?** **Sim** (HTTPS/TLS).
- **O usuário pode solicitar a exclusão?** **Sim**.
- **Citação no código:**
  - `BillingManager.kt:40-160` (comunicação com a biblioteca Google Play Billing 7+)
  - `KeyFlowApiClient.kt:119-140` (envio do `purchaseToken` para validação em `keyflow-api.arateki.com/v1/purchases/redeem`)

---

### Categoria E: Dispositivo ou Outros Identificadores (Device or Other IDs)

#### Subtipo: ID do dispositivo ou outros identificadores
- **Coletado?** **Sim**
- **Compartilhado?** **Sim**
- **Finalidades:**
  1. **Funcionalidade do app (App functionality):** Identificador anônimo gerado aleatoriamente (UUIDv4) no dispositivo para vincular a licença de desbloqueio vitalício de temas à instalação (`AnonymousIdentity.kt:30-60`).
  2. **Publicidade ou marketing (Advertising or marketing):** O SDK do Google Mobile Ads (AdMob) coleta o ID de publicidade do Google (Advertising ID) na versão gratuita para exibição de anúncios na tela de configurações (`SetupActivity`).
- **A coleta é obrigatória ou opcional?** **Opcional** (A compra única de R$ 15 desativa 100% dos anúncios e o SDK do AdMob não é inicializado; o consentimento de anúncios é gerenciado pelo Google UMP).
- **Criptografado em trânsito?** **Sim** (HTTPS/TLS).
- **O usuário pode solicitar a exclusão?** **Sim** (Ao limpar os dados do app, o UUID anônimo é destruído).
- **Citação no código:**
  - `AnonymousIdentity.kt:31-60` (UUIDv4 gerado localmente)
  - `AdsController.kt:52-87` (inicialização de anúncios condicionada a `adsOff == false`)
  - `AdsController.kt:97-110` (requisição de consentimento via UMP)

---

### Categoria F: Dados NUNCA Coletados (Declarar Explicitamente Não Coletado)

| Categoria do Play Console | Coletado? | Justificativa Técnica no Código |
|---|---|---|
| **Localização (Location)** | **Não** | O app não declara permissões de GPS/localização no manifesto (`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` ausentes). |
| **Fotos e Vídeos (Photos & Videos)** | **Não** | O app não lê a galeria nem captura fotos/vídeos (`CAMERA` e `READ_MEDIA_IMAGES` ausentes). |
| **Arquivos e Documentos (Files & Docs)** | **Não** | O app manipula apenas seus próprios arquivos internos de cache e modelos baixados. |
| **Contatos (Contacts)** | **Não** | Nenhuma leitura de agenda (`READ_CONTACTS` ausente). |
| **Agenda (Calendar)** | **Não** | Nenhuma leitura de calendário. |
| **Navegação na Web (Web Browsing)** | **Não** | O app não monitora histórico de navegação. |
| **Saúde e Fitness (Health & Fitness)** | **Não** | Nenhuma coleta de dados biométricos ou de saúde. |
| **Digitação / Teclas (Keystrokes)** | **NÃO COLETADO** | **Ponto crítico:** O teclado Canvas (`KeyFlowKeyboardService`) entrega teclas diretamente ao sistema operacional via `InputConnection`. Nenhuma tecla ou texto digitado é registrado em arquivo ou transmitido via rede (`KeyFlowKeyboardService.kt:582,683,722`). |
