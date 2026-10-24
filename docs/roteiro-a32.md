# Roteiro de Validação Manual no Galaxy A32 — KeyFlow (v1)

> **Dispositivo:** Samsung Galaxy A32 (`RX8T10DERKW`) — Chip MediaTek Helio G80, tela 90 Hz, 4 GB RAM.  
> **Duração total estimada:** ~50 a 60 minutos.  
> **Objetivo:** Validar em hardware físico real a fluidez, estabilidade, consumo de memória e comportamentos críticos de privacidade antes do início do teste fechado na Google Play Store.

---

## 0. Preparação e Instalação

> ⚠️ **Regra de ouro:** **NUNCA** execute `connectedAndroidTest` no aparelho do Yan! Isso desinstala o app e remove o KeyFlow como teclado padrão. Utilize sempre `installDebug`.

### 1. Compilar e Instalar o APK de Debug:
O APK a ser testado é o da branch integrada indicada pelo coordenador/Yan.

No terminal da sua máquina de desenvolvimento (o `ANDROID_SERIAL=RX8T10DERKW` garante a instalação exclusivamente no Galaxy A32, sem tocar em outros emuladores/dispositivos conectados):
```bash
# Compilar e instalar a versão de depuração exclusivamente no A32
ANDROID_SERIAL=RX8T10DERKW GRADLE_USER_HOME=/home/yan/Projects/keyflow/.gradle /home/yan/.local/gradle/current/bin/gradle --no-daemon installDebug
```

> **Pacote novo (2026-10-24):** o `applicationId` agora é `com.arateki.keyflow` (o `namespace` do código segue `com.keyflow`). O
> `installDebug` instala um app **novo**, sem dados nem teclado habilitado do anterior: o `com.keyflow` que já está no A32 continua lá
> até você desinstalá-lo (`adb -s RX8T10DERKW uninstall com.keyflow`), e o KeyFlow novo precisa ser habilitado e escolhido como teclado
> de novo. Os comandos `run-as`/`pm clear` abaixo já usam o id novo.

### 2. Acompanhar Logs em Tempo Real (opcional, deixe rodando em uma janela do terminal):
```bash
adb -s RX8T10DERKW logcat -v time -s \
  InferenceService LocalTranscriber ModelDownloadReceiver \
  ReasoningService LocalReasoningEngine AdsController OverlayCoordinator
```

---

## Bloco 1: Onboarding, Divulgação em Destaque e Permissões (~8 min)

> **Pré-requisitos:** Nenhum (primeira execução limpa).

### Passo 1.1 — Primeira Abertura e Divulgação em Destaque (Prominent Disclosure)
- **Ação:** Se o app já estiver instalado, limpe os dados para simular novo usuário (`adb shell pm clear com.arateki.keyflow`) e abra o aplicativo KeyFlow.
- **O que observar:**
  - A tela inicial exibe a **Divulgação em Destaque** antes de pedir permissão de microfone.
  - O texto informa com clareza: o microfone só grava sob demanda; o áudio é processado no celular ou enviado ao provedor configurado; nenhuma tecla é transmitida; o app exibe anúncios AdMob com consentimento UMP.
- **Resultado esperado:** A tela não avança para gravação sem uma escolha explícita do usuário.

### Passo 1.2 — Recusa e Continuação sem Ditado
- **Ação:** Toque em **"Recusar"** ou **"Continuar sem ditado"**.
- **O que observar:** O app avança para as configurações. O chip de microfone exibe aviso de que o ditado está desativado.
- **Teste de Bloqueio de Captura:** Abra qualquer app (ex.: WhatsApp ou Bloco de Notas) com o teclado KeyFlow. Toque no botão de microfone do teclado ou na orbe.
- **Resultado esperado:** O microfone **não inicia**. O app redireciona imediatamente para a tela de divulgação em destaque para solicitar o aceite.

### Passo 1.3 — Aceite da Divulgação e Concessão de Permissões
- **Ação:** Na tela de divulgação, toque em **"Aceitar e continuar"**. Em seguida, conceda a permissão de áudio do sistema Android quando solicitada.
- **Resultado esperado:** A permissão é registrada e o recurso de ditado é habilitado.

### Passo 1.4 — Gate de Atualização de Versão (Upgrade)
- **Ação:** Simule uma versão desatualizada de consentimento via adb:
  ```bash
  adb -s RX8T10DERKW shell "run-as com.arateki.keyflow sed -i 's/\"disclosure_accepted_version\":[0-9]*/\"disclosure_accepted_version\":0/' shared_prefs/com.arateki.keyflow_preferences.xml"
  adb -s RX8T10DERKW shell am force-stop com.arateki.keyflow
  ```
  Abra o teclado e toque no microfone.
- **Resultado esperado:** A gravação é **bloqueada** e o app abre a tela de divulgação exigindo novo aceite da versão vigente antes de liberar o microfone.

---

## Bloco 2: Gestos e Tours Interativos — GT-T8 (~5 min)

> **Pré-requisitos:** Tela do A32 em taxa de atualização padrão (90 Hz ou adaptável).

### Passo 2.1 — Fluidez dos Tours de Gestos
- **Ação:** Abra o app KeyFlow → aba **Modo**. Observe os cards interativos dos tours de gestos:
  1. Tour da Orbe (básico e atalhos).
  2. Tour do Teclado.
- **O que observar:**
  - Role a tela para cima e para baixo enquanto as animações dos tours estão em execução.
  - Avalie se há engasgos visuais (*jank*) ou perda de quadros perceptível no display de 90 Hz do A32.
- **Resultado esperado:** Animação fluida e suave, sem travamentos na rolagem da tela de configurações.

### Passo 2.2 — Acessibilidade com TalkBack
- **Ação:** Ative o leitor de tela TalkBack nas Configurações do Android. Navegue pelos cards de gestos da aba Modo.
- **Resultado esperado:** O TalkBack lê com clareza o estado dos gestos e os botões dos tours possuem descrição acessível adequada.

---

## Bloco 3: Teclado Canvas, Temas e Skins Importadas (~8 min)

> **Pré-requisitos:** Armazenamento local disponível.

### Passo 3.1 — Digitação no Teclado Canvas
- **Ação:** Abra um aplicativo de mensagens ou anotações e digite frases com acentos, pontuação e símbolos:
  *"Ação rápida, pontuação ágil: você já testou o KeyFlow hoje?"*
- **O que observar:**
  - Resposta tátil (vibração háptica) ao tocar nas teclas.
  - Popups de acentos ao segurar vogais (ex.: `a` → `á`, `à`, `ã`, `â`).
  - Alternância rápida para a camada numérica e de símbolos (`?123`).
  - Corretor ortográfico local com sugestões na barra superior (verifique se palavras comuns aparecem sugeridas).
- **Resultado esperado:** Digitação ágil, sem atraso perceptível de renderização (*input lag*).

### Passo 3.2 — Troca de Temas Embutidos
- **Ação:** No app KeyFlow → aba **Teclado**, alterne entre os temas disponíveis:
  - Sistema (claro e escuro).
  - Ateliê.
  - Temas especiais: Aero, Glitch, Rose Gold e Mascote animado na orbe.
- **O que observar:** Digite algumas frases com o tema **Glitch** e com o tema **Mascote**.
- **Resultado esperado:** A troca de temas é instantânea; a animação do mascote na orbe flutuante roda suavemente sem esquentar excessivamente o aparelho ou travar a digitação.

### Passo 3.3 — Importação de Pacote de Tema Customizado (.zip)
- **Ação:** Na seção de Temas, toque em **"Importar pacote (.zip)"** e selecione um arquivo de tema customizado (se disponível no armazenamento).
- **Resultado esperado:** O pacote é descompactado localmente, os vetores SVG são validados e o novo tema fica selecionável de imediato.

---

## Bloco 4: Ditado por Voz e Transcrição Parcial — NM-T13 / NM-T18 (~12 min)

> **Pré-requisitos:**  
> - 🌐 Conexão Wi-Fi e Dados Móveis disponíveis para os testes de download.  
> - ✈️ Modo Avião para o teste de transcrição local offline.

### Passo 4.1 — Gerenciador de Download de Modelos de Voz
- **Ação:** Abra o app KeyFlow → aba **Voz** → Origem **Local**.
- **Wi-Fi:** Toque em "Baixar" no modelo **Rápido** (sherpa-onnx Whisper/Parakeet).
  - *Observar:* Barra de progresso visual com porcentagem no card e notificação contínua na barra de status.
  - *Teste de resiliência:* No meio do download, saia do app e feche-o da lista de recentes. Espere 20 segundos e reabra.
  - *Resultado:* O download prossegue sem se perder e, ao concluir, o modelo fica selecionado automaticamente.
- **Dados Móveis:** Desative o Wi-Fi e ative os dados móveis. Tente baixar o modelo **Equilibrado**.
  - *Resultado esperado:* O aplicativo exibe diálogo de confirmação avisando sobre o uso de dados móveis antes de iniciar. Cancelar não gasta dados.

### Passo 4.2 — Ditado 100% Offline em Modo Avião (NAT, PRV)
- **Ação:** Ative o **Modo Avião** do celular (Wi-Fi e dados desligados). Certifique-se de que o modelo **Rápido** está selecionado.
- **Ditado:** Abra o bloco de notas. Toque no microfone da orbe ou do teclado e dite claramente:
  *"O endpoint da API retorna JSON com status duzentos."*
- **O que observar:**
  - O áudio é capturado e transcrito sem qualquer conexão com a internet.
  - O texto é inserido perfeitamente no campo.
  - No logcat, observe o tempo de transcrição (ex.: `Rápido: X ms de áudio → decodificar Y ms + transcrever Z ms`).
- **Exclusão do Arquivo de Áudio:**
  - Após a transcrição, execute no computador:
    ```bash
    adb -s RX8T10DERKW shell run-as com.arateki.keyflow ls -la cache/keyflow-recordings
    ```
  - *Resultado esperado:* O diretório de cache de gravações está **vazio** (o arquivo é apagado imediatamente após a entrega).

### Passo 4.3 — Transcrição Parcial ao Pausar (NM-T18)
- **Ação:** Na aba Voz, ative **"Transcrever parcialmente ao pausar"** e **"Visualizar e editar"**.
- **Passo a passo:**
  1. Inicie o ditado pela orbe e fale uma frase longa.
  2. Toque em **Pausar** na orbe → surge o indicador *"parcial · N palavras"*.
  3. Toque no aviso para expandir o texto parcial. Toque no texto para abrir o campo de edição.
  4. Corrija manualmente uma palavra utilizando o teclado KeyFlow.
  5. Sem fechar o campo, toque em **Retomar** na orbe, fale mais algumas palavras e pause novamente.
  6. Toque em **Concluir/Enviar**.
- **Resultado esperado:** O texto novo é anexado ao final da sua correção sem sobrescrevê-la, e o resultado final consolidado é inserido no aplicativo de destino.

### Passo 4.4 — Cancelamento e Desfazer
- **Ação:** Inicie um ditado, pause e arraste para o botão de cancelar (alvo X).
- **O que observar:** O aviso de cancelamento exibe a opção temporária de **"Desfazer"** (janela de 3 segundos). Ao tocar em desfazer, o texto volta; ao ignorar, o áudio é completamente excluído.

---

## Bloco 5: Raciocínio Local e Nuvem — RSN-T19 (~12 min)

> **Pré-requisitos:**  
> - 💾 Armazenamento disponível para modelos LLM locais (~600 MB para o Leve).  
> - 🔑 Chave de API pessoal (OpenAI) e/ou conta ChatGPT com assinatura (para teste de nuvem).

### Passo 5.1 — Modelo Local Leve (Qwen3 0.6B)
- **Ação:** No app KeyFlow → aba **Raciocínio** → Origem **Local**.
- Baixe o modelo **Leve** (~586 MB via Wi-Fi).
- Após baixar, toque no botão **"Testar"**.
- **O que observar:**
  - **Carga a frio:** A primeira execução carrega os pesos na memória (repare no tempo decorrido até o primeiro trecho).
  - **Fluxo do texto:** A resposta é gerada progressivamente em português, sem tags internas vazadas como `<think>`.
  - **Carga a quente:** Toque em "Testar" novamente logo em seguida — a resposta deve iniciar de forma muito mais rápida.
- **Medição de RAM (Helio G80):**
  - Durante a resposta, execute no computador:
    ```bash
    adb -s RX8T10DERKW shell dumpsys meminfo com.arateki.keyflow:reasoning
    ```
  - Anote o valor de **TOTAL PSS** (em MB).
- **Descarga por Ociosidade:** Deixe o aparelho parado por 2 minutos e 30 segundos sem usar o raciocínio e verifique o `dumpsys meminfo` novamente.
  - *Resultado esperado:* O PSS cai significativamente ou o processo é finalizado para economizar memória do sistema.

### Passo 5.2 — Isolamento de Falhas do Processo de Raciocínio
- **Ação:** Inicie um teste do modelo local e, enquanto o texto estiver sendo gerado, force a finalização do processo no terminal:
  ```bash
  adb -s RX8T10DERKW shell run-as com.arateki.keyflow kill -9 $(adb -s RX8T10DERKW shell pidof com.arateki.keyflow:reasoning)
  ```
- **Resultado esperado:** A tela de configurações exibe aviso amigável de que o motor foi interrompido. **O teclado e a orbe continuam funcionando normalmente sem fechar**. Um novo toque em "Testar" reinicia o motor sem problemas.

### Passo 5.3 — OpenAI com Chave de API Própria (BYOK)
- **Ação:** Mude a origem para **OpenAI**. Insira uma chave de API válida (`sk-...`).
- **O que observar:**
  - O campo de texto mascara a chave imediatamente.
  - A lista de modelos compatíveis da conta é carregada no seletor.
  - Ao tocar em "Testar", a resposta é recebida via streaming com sucesso.
- **Segurança das Credenciais (Android Keystore):**
  - Execute no computador:
    ```bash
    adb -s RX8T10DERKW shell run-as com.arateki.keyflow cat shared_prefs/keyflow_auth.xml
    ```
  - *Resultado esperado:* O arquivo existe e seu conteúdo é **completamente cifrado e ilegível**. A chave `sk-...` **NÃO** aparece em texto puro em nenhum arquivo de preferências ou banco de dados.

### Passo 5.4 — Login "Continue with ChatGPT" (Conta de Assinatura)
> *Nota: Execute este passo caso possua conta de assinatura ChatGPT configurada.*
- **Ação:** Toque em **"Continue with ChatGPT"**.
- **O que observar:**
  - O navegador padrão do sistema é aberto na página oficial de consentimento da OpenAI.
  - Aguarde ~30 segundos na página do navegador antes de aprovar (simulando pausa do usuário).
  - Toque em autorizar e retorne ao KeyFlow.
  - O status atualiza para *"Conectado como <seu e-mail>"*.
  - O link *"Gerenciar uso"* abre a página oficial da OpenAI sem passar senhas para o KeyFlow.

---

## Bloco 6: Anúncios, Privacidade e Compra Única de R$ 15 (~10 min)

> **Pré-requisitos:** 🌐 Conexão com a internet ativa.

### Passo 6.1 — Banners de Anúncios na Tela de Configurações
- **Ação:** Abra a tela de configurações (`SetupActivity`) e navegue entre as abas.
- **O que observar:**
  - O banner de anúncio (AdMob) aparece **exclusivamente na parte inferior das telas de configuração**.
  - Abra o teclado em outro app e abra a orbe flutuante sobre a tela inicial.
  - **Garantia crítica de loja:** **NENHUM anúncio aparece no teclado ou dentro da orbe flutuante**.

### Passo 6.2 — Revisão de Consentimento UMP ("Privacidade dos anúncios")
- **Ação:** Vá até a aba **Docs** das configurações.
- **O que observar:**
  - Localize a opção **"Privacidade dos anúncios"**.
  - Toque nela.
- **Resultado esperado:** O formulário oficial de opções de privacidade do Google UMP é aberto em tela cheia, permitindo ao usuário rever ou revogar suas escolhas de consentimento publicitário (LGPD/GDPR) a qualquer momento.

### Passo 6.3 — Links Oficiais da Política de Privacidade e Termos de Uso
- **Ação:** Na aba **Docs**, toque nos links:
  - *"Política de privacidade"* → Deve abrir `https://keyflow.arateki.com/privacidade` no navegador.
  - *"Termos de uso"* → Deve abrir `https://keyflow.arateki.com/termos` no navegador.
- **O que observar:** As páginas abrem formatadas, legíveis no celular, com tema adaptado (claro ou escuro) e sem erros 404.

### Passo 6.4 — Compra Única de R$ 15 e Remoção Permanente de Anúncios
- **Ação:** Na aba Loja / Temas, toque no botão para adquirir o desbloqueio permanente de **R$ 15**.
- **O que observar:**
  - Na versão de depuração / teste interno do Google Play, a folha de pagamento oficial do Google Play Billing é exibida com o produto `unlock_all_skins`.
  - Conclua a compra de teste (cartão de teste da Play Store).
- **Resultado esperado:**
  - O status atualiza para *"Temas desbloqueados e sem anúncios"*.
  - Todos os temas exclusivos (Aero, Glitch, Rose Gold e Mascote) ficam disponíveis de imediato.
  - Os banners e intersticiais somem completamente da tela de configurações.
  - Ao fechar o app e abrir novamente, o status de desbloqueio permanece ativo.

---

## Tabela de Resultados (Preencher pelo Yan)

| Item | Passo do Teste | Resultado (Passou / Falhou) | Medições e Observações do Yan |
|:---:|---|:---:|---|
| **1** | 1.1 / 1.2 — Divulgação em destaque bloqueia mic até o aceite | | |
| **2** | 1.4 — Atualização de versão exige novo aceite | | |
| **3** | 2.1 — Fluidez dos tours na aba Modo a 90 Hz (sem jank) | | |
| **4** | 2.2 — Leitura dos cards com TalkBack ativo | | |
| **5** | 3.1 — Digitação fluida no Canvas com resposta tátil | | |
| **6** | 3.2 — Temas Ateliê, Glitch e Mascote animado na orbe | | |
| **7** | 3.3 — Importação de pacote de skins .zip | | |
| **8** | 4.1 — Download do modelo de voz (Wi-Fi e Dados Móveis) | | |
| **9** | 4.2 — Ditado 100% offline em Modo Avião (tempo: ___ ms) | | |
| **10** | 4.2 — Cache de áudio apagado imediatamente | | |
| **11** | 4.3 — Transcrição parcial ao pausar e edição no campo | | |
| **12** | 4.4 — Cancelamento e botão Desfazer (3 s) | | |
| **13** | 5.1 — LLM Leve: Carga a frio (___ s) / Quente (___ s) | | |
| **14** | 5.1 — LLM Leve: Consumo de RAM (TOTAL PSS: ___ MB) | | |
| **15** | 5.1 — Descarga do modelo por ociosidade (~120 s) | | |
| **16** | 5.2 — Matar processo `:reasoning`: teclado continua ok | | |
| **17** | 5.3 — OpenAI com chave de API (BYOK) e cofre cifrado | | |
| **18** | 5.4 — Continue with ChatGPT (redirecionamento do browser) | | |
| **19** | 6.1 — Banners AdMob apenas na tela de configurações | | |
| **20** | 6.2 — Opção "Privacidade dos anúncios" abre UMP | | |
| **21** | 6.3 — Links da política e termos abrem no navegador | | |
| **22** | 6.4 — Compra única de R$ 15 remove anúncios e libera temas | | |

---

### Conclusão e Próximos Passos
- **Se todos os passos críticos passarem:** O build está pronto para submissão na faixa de teste fechado da Google Play Store (V1-9).
- **Em caso de falha:** Anote o número do item e anexe o trecho relevante do logcat para abertura imediata de correção pela equipe.
