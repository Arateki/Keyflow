# Política de Privacidade — KeyFlow

**Última atualização:** 23 de outubro de 2026  
**Vigência a partir de:** 23 de outubro de 2026  
**URL permanente:** `https://keyflow.arateki.com/privacidade`

---

A **Arateki LTDA** (CNPJ 45.642.122/0001-10, com sede em Fortaleza/CE) desenvolveu o **KeyFlow** com um compromisso fundamental: **sua privacidade é a prioridade**. O KeyFlow é um teclado para Android focado em produtividade, digitação ágil e ditado por voz sob demanda.

Esta política explica, de maneira simples, transparente e em conformidade com a LGPD e o GDPR, quais dados são tratados, como são protegidos e quais são os seus direitos.

---

## 1. O Princípio Fundamental: O Que NÃO Coletamos

Para garantir sua total tranquilidade ao utilizar o KeyFlow como seu teclado padrão no Android:

- **NÃO coletamos, NÃO gravamos e NÃO transmitimos o que você digita:** Suas teclas tocadas, textos digitados em campos de senha, mensagens, documentos ou termos de busca são processados estritamente na memória local do seu aparelho e entregues diretamente ao aplicativo em que você está digitando via `InputConnection`. Nenhum caractere digitado é gravado em arquivos de log ou enviado para a internet.
- **NÃO temos acesso contínuo ao seu microfone:** O microfone do seu celular só é ativado quando você clica deliberadamente no botão de gravar (no teclado ou na orbe flutuante) e é desligado imediatamente após a conclusão do ditado.
- **NÃO coletamos dados sensíveis:** O aplicativo não requer nem utiliza permissões de localização geográfica precisa ou aproximada por GPS (`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`), agenda de contatos, fotos ou histórico de navegação.

---

## 2. Quais Dados Tratamos e Por Quê

### a) Gravações de Áudio para Ditado por Voz
- **O que é:** O áudio capturado enquanto você mantém a gravação ativada.
- **Finalidade:** Transcrever sua fala em texto sob demanda do usuário.
- **Como funciona:**
  - Se você escolher a **Transcrição Local (Offline):** O áudio é processado inteiramente dentro do seu aparelho usando modelos neurais compactos (`sherpa-onnx`). **Nenhum byte de áudio sai do seu celular.**
  - Se você escolher a **Transcrição na Nuvem (OpenAI, Groq ou Servidor Customizado):** O arquivo de áudio temporário é transmitido diretamente do seu aparelho via conexão segura criptografada (HTTPS) para o endpoint escolhido por você, utilizando a sua própria chave de API.
- **Armazenamento e Exclusão no Aparelho:** O arquivo de áudio gravado é mantido em cache temporário (`cacheDir/keyflow-recordings/`) durante a transcrição e durante a janela de cancelamento de 3 segundos (que permite desfazer o envio). Ele é **apagado imediatamente** assim que a transcrição é concluída (com sucesso ou erro) ou ao expirar o cancelamento. Arquivos órfãos com mais de 24 horas são limpos automaticamente na inicialização do aplicativo.
- **Retenção nos Provedores de Terceiros:** Nos provedores de nuvem (OpenAI, Groq ou endpoint compatível), o tratamento e a retenção do áudio transmitido regem-se exclusivamente pelas políticas de privacidade de cada provedor e pelas configurações da sua chave/conta. Não podemos garantir retenção zero em servidores de terceiros. Se você deseja garantia absoluta de não retenção externa, utilize a transcrição 100% offline no aparelho.

### b) Prompts de Texto e Dados para Modelos de Raciocínio (IA)
- **O que é:** O texto que você digita para testes de raciocínio nas configurações do aplicativo.
- **Finalidade:** Obter respostas inteligentes ou customizar a interface do teclado.
- **Como funciona:**
  - Se você usar **Modelos Locais (LiteRT-LM):** O processamento é 100% offline no seu aparelho.
  - Se você usar **Provedores de Nuvem (OpenAI, Anthropic ou Google):** O texto é enviado diretamente do seu aparelho para os servidores do provedor configurado, usando sua chave de API ou sua assinatura conectada (OAuth). Na OpenAI, as requisições utilizam o parâmetro `store: false` para solicitar a não retenção em histórico de API, mas a retenção remota (ex.: até 30 dias para monitoramento de abusos conforme a política da plataforma) segue as regras da OpenAI. Na Anthropic e no Google, a retenção segue as políticas de cada empresa. No caso de ferramentas de customização visual de temas via Anthropic por chave de API, metadados de skins e imagens de prévia geradas podem ser enviados como parâmetros de ferramentas.
- **Agente KeyFlow:** Recurso de assistência interativa planejado como "Em breve" para versões futuras da v1.

### c) Chaves de API e Credenciais de Acesso
- **O que é:** Suas chaves de API pessoais e tokens de autenticação das contas conectadas.
- **Como são tratadas:** Armazenadas no seu aparelho e protegidas por criptografia no **Android Keystore** (AES-256-GCM). Elas **nunca** são enviadas para servidores da Arateki e são excluídas dos backups em nuvem do Android. Para a execução das requisições solicitadas por você, os tokens ou chaves são transmitidos diretamente e com segurança (HTTPS) aos respectivos provedores de terceiros (OpenAI, Anthropic ou Google) exclusivamente para fins de autenticação.

### d) Identificador Anônimo do Aparelho, Integridade e Compras
- **O que é:** Um identificador anônimo gerado aleatoriamente no seu aparelho (UUIDv4), o token de verificação de integridade do Google Play Integrity e o recibo de compra fornecido pelo Google Play (`purchaseToken`).
- **Finalidade:** Verificar a autenticidade do aplicativo, gerenciar e validar o status de direitos (entitlements) — incluindo a compra única de **R$ 15** (desbloqueio vitalício de temas e remoção de anúncios) — e permitir a restauração da licença em caso de reinstalação.
- **Como é transmitido:** Ao concluir o onboarding nas configurações, o app registra o UUID anônimo e o token de integridade junto à API da Arateki (`keyflow-api.arateki.com/v1/register`, `v1/account`). Na compra ou restauração, o recibo é validado em `/v1/purchases/redeem`. Um resumo criptográfico (hash) do UUID também é enviado ao Google Play Billing (`obfuscatedAccountId`). A compra única não encerra a verificação periódica de licença.
- **Privacidade:** O identificador anônimo não é associado ao seu nome, e-mail ou número de telefone.

### e) Anúncios e Consentimento (Google AdMob / UMP)
- O KeyFlow exibe anúncios discretos (banner e intersticial) **exclusivamente na tela de configurações do aplicativo** (`SetupActivity`).
- **Nenhum anúncio é exibido no teclado ou na orbe flutuante.**
- O aplicativo utiliza a plataforma **Google User Messaging Platform (UMP)** para solicitar seu consentimento para exibição de anúncios de acordo com a LGPD e o GDPR.
- **Revisão a qualquer momento:** O usuário pode rever ou revogar suas preferências de consentimento de anúncios a qualquer momento através da opção **"Privacidade dos anúncios"** localizada na aba **Docs** das configurações do aplicativo.
- A compra única de **R$ 15** remove todos os anúncios do aplicativo em definitivo.

---

## 3. Com Quem Compartilhamos Seus Dados

A Arateki **não vende, não aluga e não comercializa** seus dados pessoais. O compartilhamento ocorre estritamente para viabilizar as funcionalidades que você mesmo optou por utilizar:

1. **Provedores de Inteligência Artificial escolhidos por você:**
   - **OpenAI:** [Política de Privacidade da OpenAI](https://openai.com/policies/privacy-policy/)
   - **Groq:** [Política de Privacidade da Groq](https://groq.com/privacy-policy/)
   - **Anthropic:** [Política de Privacidade da Anthropic](https://www.anthropic.com/privacy)
   - **Google Generative AI:** [Política de Privacidade do Google](https://policies.google.com/privacy)
2. **Plataformas de Infraestrutura, Pagamento e Anúncios:**
   - **Google Play:** Para processamento de compras no aplicativo e verificação de integridade do app ([Privacidade do Google](https://policies.google.com/privacy)).
   - **Google AdMob:** Para veiculação de anúncios na tela de configurações para usuários da versão gratuita ([Tecnologias de Anúncios do Google](https://policies.google.com/technologies/ads)). O SDK pode coletar identificadores de publicidade, endereço IP e localização aproximada derivada de IP.
   - **Hugging Face e Espelhos Autorizados:** Para download dos arquivos públicos de pesos de modelos locais de voz e raciocínio ([Privacidade do Hugging Face](https://huggingface.co/privacy)), ou outros espelhos de distribuição indicados no catálogo remoto.
   - **API da Arateki (`keyflow-api.arateki.com`):** Recebe o UUID anônimo, token do Play Integrity e tokens de compra para validação de licenças, e fornece arquivos de configuração e catálogo (`/v1/config`, `/v1/models`). Os logs técnicos da API (registros de acesso, endereço IP e erros) são mantidos por 30 dias para fins de segurança e integridade, sendo apagados automaticamente após esse período.
3. **Suporte por E-mail Voluntário:**
   - Caso o usuário escolha utilizar o recurso de contato na aba Docs, um rascunho de e-mail é gerado com dados técnicos básicos de diagnóstico do aparelho (modelo, versão do Android e do app). O envio é estritamente manual e voluntário pelo próprio cliente de e-mail do usuário.

---

## 4. O Que Fica 100% no Seu Celular

- Todas as teclas que você digita no teclado Canvas (sem telemetria e sem registro).
- O corretor ortográfico local e sugestões de palavras baseadas em dicionários embutidos estáticos (o app não aprende hábitos de digitação nem salva seu vocabulário pessoal).
- Todo o processamento de ditado por voz quando configurado em modo Local (sherpa-onnx).
- Todo o raciocínio de texto em modo Local (LiteRT-LM).
- O armazenamento local das suas chaves de API e credenciais de login, protegidas pelo Android Keystore.
- Arquivos de temas personalizados importados em formato `.zip`, processados e armazenados estritamente na memória local.

---

## 5. Retenção e Exclusão de Dados

- **Áudios de voz:** Apagados do armazenamento temporário imediatamente após a conclusão ou falha da transcrição, ou ao expirar o cancelamento (3 segundos). Limpeza periódica automática remove arquivos residuais com mais de 24 horas.
- **No seu aparelho:** Você pode excluir configurações locais, chaves de API e modelos baixados limpando os dados do aplicativo nas Configurações do Android ou desinstalando o app.
- **Restauração de Identidade (Google Block Store):** Para garantir que compras legítimas não sejam perdidas ao reinstalar o aplicativo ou trocar de aparelho, o identificador anônimo (UUID) pode ser restaurado via Google Block Store.
- **Na API da Arateki:** Como não mantemos contas nominais ou cadastros com e-mail, os registros vinculados ao UUID anônimo destinam-se exclusivamente à validação de integridade e licenças. Você pode excluir os dados do seu aparelho guardados na API a qualquer momento diretamente pelo botão **"Apagar meus dados"** na aba **Docs** das configurações do aplicativo (o que apaga na hora o registro do dispositivo em nossos servidores; a compra única permanece válida no Google Play e volta ao restaurar compras) ou mediante solicitação por e-mail para `contato@arateki.com`, que será atendida no prazo de até 15 dias corridos em conformidade com a LGPD.
- **Em Provedores Externos:** A solicitação de exclusão de dados enviados a provedores de nuvem (OpenAI, Anthropic, Google) deve ser realizada pelo usuário junto à respectiva plataforma.

---

## 6. Segurança das Informações

Implementamos medidas técnicas rigorosas para proteger seus dados:
- Todas as transmissões externas utilizam criptografia **TLS/HTTPS**.
- As credenciais de API e tokens salvos no aparelho são cifrados e protegidos pelo **Android Keystore** (AES-256-GCM).
- O código do aplicativo isola tarefas pesadas de inferência e raciocínio em processos separados (`:inference` e `:reasoning`) para prevenir vazamentos de memória.

---

## 7. Direitos do Titular (LGPD e GDPR)

Em conformidade com a Lei Geral de Proteção de Dados Pessoais (LGPD — Lei nº 13.709/2018) e o Regulamento Geral sobre a Proteção de Dados (GDPR):
- Você tem o direito de obter informações claras sobre o tratamento de seus dados.
- Você pode rever e revogar consentimentos de anúncios a qualquer momento pelas configurações do app ("Privacidade dos anúncios").
- Você tem o direito de solicitar a exclusão de seus dados e registros a qualquer momento pelo botão **"Apagar meus dados"** na aba **Docs** do aplicativo ou enviando uma mensagem para `contato@arateki.com` (atendimento em até 15 dias corridos nos termos da LGPD).

---

## 8. Crianças e Menores de Idade

O KeyFlow não é direcionado a crianças menores de 13 anos e não coleta intencionalmente dados de menores. Se tomarmos conhecimento de que dados de uma criança foram coletados inadvertidamente, eles serão prontamente descartados.

---

## 9. Contato e Dúvidas

Para qualquer dúvida, comentário ou solicitação a respeito desta Política de Privacidade ou sobre as práticas de proteção de dados do KeyFlow, entre em contato:

- **E-mail de Contato:** `contato@arateki.com`
- **Controlador dos Dados:** Arateki LTDA (CNPJ 45.642.122/0001-10)  
  Fortaleza, Ceará, Brasil
