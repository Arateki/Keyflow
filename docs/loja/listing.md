# Ficha da Loja (Store Listing) e Declarações — KeyFlow (v1)

> Textos oficiais para a página do aplicativo na Google Play Store (em português e inglês) e declarações obrigatórias de Serviços em Primeiro Plano (Foreground Services).

---

## 1. Versão em Português (Brasil)

### 1.1. Título do App (≤ 30 caracteres)
```
KeyFlow: Teclado de Voz & IA
```
*(Contagem: 28 caracteres)*

### 1.2. Descrição Curta (≤ 80 caracteres)
```
Teclado ágil com ditado por voz sob demanda, IA offline ou na nuvem e temas.
```
*(Contagem: 76 caracteres)*

### 1.3. Descrição Completa (≤ 4000 caracteres)
```
O KeyFlow é um teclado completo para Android projetado para digitação ágil, fluida e ditado por voz sob demanda, com respeito absoluto à sua privacidade.

Dite mensagens com naturalidade, alterne entre digitação manual e voz com um toque, e utilize inteligência artificial no aparelho ou na nuvem usando suas próprias chaves e contas.

PRINCIPAIS RECURSOS

• Ditado por Voz Ágil e Sob Demanda
Grave sua fala diretamente pelo botão integrado do teclado ou pela orbe flutuante sobreposta. Pause, retome, cancele ou desfaça com facilidade. O microfone só grava quando você decide falar.

• Transcrição Offline ou na Nuvem
Escolha como sua voz vira texto:
- 100% Offline: modelos neurais no aparelho (sherpa-onnx Whisper e Parakeet), sem gastar dados e sem sair do celular.
- Nuvem com Chave Própria: conecte sua própria chave de API da OpenAI (Whisper), Groq ou servidor customizado compatível.

• Teclado Canvas Rápido e Preciso
Interface desenhada em Canvas de alta performance com suporte a Português, Inglês e Espanhol, acentuação dinâmica intuitiva, corretor ortográfico local com dicionários integrados, barra de atalhos personalizável e resposta tátil precisa.

• Personalização e Temas Visuais
Personalize a aparência do seu teclado:
- Temas Sistema e Ateliê inclusos gratuitamente.
- Temas exclusivos: Aero, Glitch, Rose Gold e o Mascote animado na orbe flutuante.
- Suporte à importação de pacotes de temas customizados (.zip).

• Raciocínio com Inteligência Artificial
Gere textos e consulte assistentes inteligentes diretamente pelas configurações:
- Modelos Locais (offline): Gemma e Qwen rodando no próprio dispositivo via LiteRT-LM.
- Nuvem: conecte seus provedores favoritos (OpenAI, Anthropic Claude, Google Gemini) via chave de API ou login por assinatura.
- Em breve: Agente KeyFlow para assistência inteligente e customização assistida de temas.

PRIVACIDADE COMO PRIORIDADE

• Suas teclas não saem do aparelho: o que você digita é processado estritamente na memória local e entregue direto ao app em uso. Nenhum caractere digitado é gravado ou enviado para a internet.
• Sem contas obrigatórias: use o app de imediato sem criar cadastro no KeyFlow.
• Suas chaves protegidas: credenciais de API e tokens de login são cifrados e protegidos pelo Android Keystore.

MONETIZAÇÃO TRANSPARENTE

O KeyFlow é gratuito para usar:
• A versão gratuita conta com anúncios discretos (banner e intersticial) exibidos exclusivamente na tela de configurações do aplicativo. Nenhum anúncio é exibido no teclado ou na orbe.
• Compra única opcional (R$ 15): desbloqueia permanentemente todos os temas visuais (Aero, Glitch, Rose Gold e Mascote) e remove todos os anúncios do aplicativo para sempre. Não é assinatura; pague uma vez e use vitaliciamente.
```
*(Contagem: 2.750 caracteres — bem abaixo do limite de 4000)*

---

## 2. English Version (Global)

### 2.1. App Title (≤ 30 characters)
```
KeyFlow: Voice Keyboard & AI
```
*(Character count: 28)*

### 2.2. Short Description (≤ 80 characters)
```
Fast keyboard with voice dictation, local or cloud AI, and custom themes.
```
*(Character count: 73)*

### 2.3. Full Description (≤ 4000 characters)
```
KeyFlow is a versatile Android keyboard built for fast, responsive typing and on-demand voice dictation, with an uncompromising focus on user privacy.

Speak naturally, switch seamlessly between typing and voice with a single tap, and harness local or cloud AI using your own keys and accounts.

KEY FEATURES

• Fast, On-Demand Voice Dictation
Record speech directly from the keyboard's microphone key or via the floating overlay orb. Pause, resume, cancel, or undo with ease. The microphone only records when you explicitly tap to speak.

• Offline or Cloud Transcription
Choose how your voice turns into text:
- 100% Offline: Compact neural models on your device (sherpa-onnx Whisper and Parakeet) — zero data usage, zero audio leaving your phone.
- Cloud with Your Own Key: Connect your personal OpenAI Whisper, Groq, or compatible custom API key.

• High-Performance Canvas Keyboard
Engineered with high-speed Canvas rendering supporting English, Portuguese, and Spanish, intuitive accent popups, on-device spell check with built-in dictionaries, customizable shortcut bars, and haptic feedback.

• Themes and Visual Customization
Tailor your keyboard's look and feel:
- System and Atelier themes included for free.
- Premium themes: Aero, Glitch, Rose Gold, and the animated Mascot orb.
- Import custom theme packages (.zip).

• AI Reasoning & Smart Assistant
Generate text and test AI models directly in settings:
- Local Models (Offline): Gemma and Qwen running entirely on-device via LiteRT-LM.
- Cloud Providers: Connect OpenAI, Anthropic Claude, or Google Gemini via personal API key or subscription login.
- Coming soon: KeyFlow Agent for smart assistance and conversational theme customization.

PRIVACY BY DESIGN

• Your keystrokes stay on your device: Typed text and passwords are processed strictly in local memory and sent directly to the active app. We never log or transmit typed characters.
• No account required: Start using the keyboard immediately without creating a KeyFlow profile.
• Secure credentials: API keys and login tokens are encrypted and protected via the Android Keystore.

TRANSPARENT MONETIZATION

KeyFlow is free to use:
• The free version displays discreet banner and interstitial ads strictly within the app's settings screen. No ads ever appear inside the keyboard or floating orb.
• Optional one-time purchase: Permanently unlocks all premium themes (Aero, Glitch, Rose Gold, Mascot) and completely removes all advertisements forever. No recurring subscriptions.
```
*(Character count: 2.504 — well below the 4000 character limit)*

---

## 3. Declarações de Serviços em Primeiro Plano (Foreground Services) para o Play Console

---

### 3.1. Permissão `FOREGROUND_SERVICE_MICROPHONE`

#### Justificativa para o formulário do Play Console:
```
O KeyFlow é um teclado de entrada de texto (Input Method Editor — IME) com recurso central de ditado por voz sob demanda. Como o teclado e sua orbe flutuante operam em janelas de sistema sobrepostas a outros aplicativos (como navegadores, clientes de e-mail e mensageiros), a captura de áudio do microfone pelo serviço OverlayService precisa operar fora da Activity principal. O microfone grava exclusivamente enquanto o usuário dita texto ativamente. No modo só-teclado, o serviço em primeiro plano é iniciado sob demanda pelo toque no microfone e encerrado imediatamente após o término ou cancelamento do ditado; no modo Sobrepor (orbe flutuante ativa), o serviço mantém a orbe acessível com notificação contínua na barra de status indicando o estado do serviço e permitindo pausar ou fechar, captando o microfone estritamente durante o ditado.
```

#### Roteiro do Vídeo Demonstrativo (Microphone FGS Video Script):
- **Duração:** 30 a 45 segundos.
- **Passo 1 (00:00 - 00:08):** Abrir um aplicativo de terceiros (ex.: Bloco de Notas ou Mensagens) e tocar em um campo de texto para abrir o teclado KeyFlow.
- **Passo 2 (00:08 - 00:15):** Tocar no botão de microfone do teclado. Destacar o início imediato da gravação e o indicador visual no teclado.
- **Passo 3 (00:15 - 00:25):** Deslizar para baixo a barra de notificações do Android, mostrando a notificação contínua obrigatória do serviço em primeiro plano: *"KeyFlow — Ditado por voz ativo"*.
- **Passo 4 (00:25 - 00:35):** Falar uma frase breve, tocar no botão de concluir/parar gravação. Mostrar o texto transcrito sendo inserido no campo de texto e a notificação de primeiro plano encerrando-se no modo só-teclado (ou retornando ao status pronto da orbe no modo Sobrepor).

---

### 3.2. Permissão `FOREGROUND_SERVICE_DATA_SYNC`

#### Justificativa para o formulário do Play Console:
```
O KeyFlow oferece a funcionalidade de transcrição de voz e raciocínio de inteligência artificial 100% offline diretamente no dispositivo do usuário. Para isso, o usuário pode selecionar e baixar modelos neurais compactos (pesos de 40 MB a mais de 200 MB) a partir do Hugging Face. O serviço ModelDownloadService executa o download desses grandes arquivos em primeiro plano para garantir que o processo não seja interrompido pelo sistema operacional caso o usuário feche a tela de configurações ou desligue a tela do aparelho durante a transferência. O serviço exibe uma notificação contínua obrigatória com a porcentagem e a barra de progresso do download, encerrando-se automaticamente assim que o arquivo é verificado e salvo localmente.
```

#### Roteiro do Vídeo Demonstrativo (DataSync FGS Video Script):
- **Duração:** 30 a 45 segundos.
- **Passo 1 (00:00 - 00:10):** Abrir o app KeyFlow na tela de configurações (`SetupActivity`) e navegar até a aba de Modelos de Voz.
- **Passo 2 (00:10 - 00:18):** Escolher um modelo local offline (ex.: *Whisper Base* ou *Parakeet*) e clicar no botão "Baixar Modelo".
- **Passo 3 (00:18 - 00:28):** Minimizar o app KeyFlow (pressionar botão Home ou alternar para outro app).
- **Passo 4 (00:28 - 00:38):** Puxar a barra de notificações do Android para exibir a notificação em primeiro plano com progresso ativo: *"KeyFlow — Baixando modelo de voz (XX%)"*.
- **Passo 5 (00:38 - 00:45):** Ao atingir 100%, a notificação é finalizada e o modelo aparece como "Instalado / Pronto para uso offline".
