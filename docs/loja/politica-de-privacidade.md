# Política de Privacidade — KeyFlow

**Última atualização:** 23 de outubro de 2026  
**Vigência a partir de:** 23 de outubro de 2026  
**URL permanente:** [YAN: Definir URL oficial de hospedagem, ex.: `https://arateki.com/keyflow/privacidade`]

---

A **Arateki** [YAN: Inserir razão social completa e CNPJ, se aplicável] desenvolveu o **KeyFlow** com um compromisso fundamental: **sua privacidade é a prioridade**. O KeyFlow é um teclado para Android focado em produtividade e ditado por voz sob demanda.

Esta política explica, de maneira simples e transparente, quais dados são tratados, como são protegidos e quais são os seus direitos.

---

## 1. O Princípio Fundamental: O Que NÃO Coletamos

Para garantir sua total tranquilidade ao utilizar o KeyFlow como seu teclado padrão no Android:

- **NÃO coletamos, NÃO gravamos e NÃO transmitimos o que você digita:** Suas teclas tocadas, textos digitados em campos de senha, mensagens, documentos ou termos de busca são processados estritamente na memória local do seu aparelho e entregues diretamente ao aplicativo em que você está digitando.
- **NÃO temos acesso contínuo ao seu microfone:** O microfone do seu celular só é ativado quando você clica deliberadamente no botão de gravar (no teclado ou na orbe flutuante) e é desligado imediatamente após a conclusão do ditado.
- **NÃO coletamos dados sensíveis:** Não coletamos sua localização GPS, agenda de contatos, histórico de navegação ou fotos.

---

## 2. Quais Dados Tratamos e Por Quê

### a) Gravações de Áudio para Ditado por Voz
- **O que é:** O áudio capturado enquanto você mantém a gravação ativada.
- **Finalidade:** Transcrever sua fala em texto.
- **Como funciona:**
  - Se você escolher a **Transcrição Local (Offline):** O áudio é processado inteiramente dentro do seu aparelho usando modelos neurais compactos (`sherpa-onnx`). **Nenhum byte de áudio sai do seu celular.**
  - Se você escolher a **Transcrição na Nuvem (OpenAI, Groq ou Servidor Customizado):** O arquivo de áudio temporário é transmitido diretamente do seu aparelho via conexão segura criptografada (HTTPS) para o endpoint escolhido por você, utilizando a sua própria chave de API.
- **Armazenamento:** O arquivo de áudio gravado fica em cache temporário no seu aparelho e é **descartado imediatamente** assim que a transcrição é concluída ou se você cancelar a gravação.

### b) Prompts de Texto para Modelos de Raciocínio (IA)
- **O que é:** O texto que você digita para testes de raciocínio ou no chat com o Agente KeyFlow nas configurações do app.
- **Finalidade:** Obter respostas inteligentes ou customizar a interface do teclado.
- **Como funciona:**
  - Se você usar **Modelos Locais (LiteRT-LM):** O processamento é 100% offline no seu aparelho.
  - Se você usar **Provedores de Nuvem (OpenAI, Anthropic ou Google):** O texto é enviado diretamente do seu aparelho para os servidores do provedor configurado, usando sua chave de API ou sua assinatura conectada (OAuth). Na OpenAI, as requisições utilizam o parâmetro `store: false`.

### c) Chaves de API e Credenciais de Acesso
- **O que é:** Suas chaves de API pessoais e tokens de autenticação das contas conectadas.
- **Como são guardadas:** Armazenadas **exclusivamente no seu aparelho**, criptografadas pelo cofre seguro do Android (**Android Keystore** com AES-256). Elas **nunca** são enviadas para servidores da Arateki e são excluídas dos backups em nuvem do Android.

### d) Identificador Anônimo do Aparelho e Compras
- **O que é:** Um identificador aleatório gerado no seu aparelho (UUIDv4) e o recibo de compra fornecido pelo Google Play (`purchaseToken`).
- **Finalidade:** Validar a compra única de **R$ 15** (desbloqueio vitalício de temas e remoção de anúncios) e manter seu status de desbloqueio mesmo se reinstalar o app.
- **Privacidade:** Esse identificador é completamente anônimo e não é associado ao seu nome, e-mail ou número de telefone.

### e) Anúncios e Consentimento (Google AdMob / UMP)
- O KeyFlow exibe anúncios discretos (banner e intersticial) **exclusivamente na tela de configurações do aplicativo** (`SetupActivity`).
- **Nenhum anúncio é exibido no teclado ou na orbe flutuante.**
- O aplicativo utiliza a plataforma **Google User Messaging Platform (UMP)** para solicitar seu consentimento para exibição de anúncios personalizados de acordo com a LGPD e o GDPR.
- A compra única de **R$ 15** remove todos os anúncios do aplicativo em definitivo.

---

## 3. Com Quem Compartilhamos Seus Dados

A Arateki **não vende, não aluga e não comercializa** seus dados pessoais. O compartilhamento ocorre estritamente para viabilizar as funcionalidades que você mesmo optou por utilizar:

1. **Provedores de Inteligência Artificial escolhidos por você:**
   - **OpenAI:** [Política de Privacidade da OpenAI](https://openai.com/policies/privacy-policy/)
   - **Groq:** [Política de Privacidade da Groq](https://groq.com/privacy-policy/)
   - **Anthropic:** [Política de Privacidade da Anthropic](https://www.anthropic.com/privacy)
   - **Google Generative AI:** [Política de Privacidade do Google](https://policies.google.com/privacy)
2. **Plataformas de Infraestrutura e Pagamento:**
   - **Google Play:** Para processamento de compras no aplicativo e verificação de integridade do app ([Privacidade do Google](https://policies.google.com/privacy)).
   - **Google AdMob:** Para veiculação de anúncios na tela de configurações para usuários da versão gratuita ([Tecnologias de Anúncios do Google](https://policies.google.com/technologies/ads)).
   - **Hugging Face:** Apenas para download dos arquivos públicos de pesos de modelos locais de voz e raciocínio ([Privacidade do Hugging Face](https://huggingface.co/privacy)).
   - **API da Arateki (`keyflow-api.arateki.com`):** Recebe apenas o token de validação de compra do Google Play e o UUID anônimo do aparelho para registrar o desbloqueio. [YAN: Definir política de retenção de logs da API — sugestão: 30 dias para logs técnicos de erro].

---

## 4. O Que Fica 100% no Seu Celular

- Todas as teclas que você digita no teclado Canvas.
- Todo o processamento de ditado por voz quando configurado em modo Local (sherpa-onnx).
- Todo o raciocínio de texto em modo Local (LiteRT-LM).
- Suas chaves de API e tokens de autenticação (cifrados no Android Keystore).
- O dicionário pessoal de palavras e termos aprendidos pelo teclado.

---

## 5. Retenção e Exclusão de Dados

- **Áudios de voz:** Descartados do aparelho imediatamente após a entrega do texto transcrito ou no cancelamento da gravação.
- **No seu aparelho:** Você pode excluir todas as suas chaves, modelos locais baixados e configurações a qualquer momento limpando os dados do aplicativo nas Configurações do Android ou desinstalando o KeyFlow.
- **Na API da Arateki:** Como não coletamos dados pessoais vinculados a cadastros, a exclusão dos dados locais do seu aparelho remove o vínculo com o identificador anônimo.

---

## 6. Segurança das Informações

Implementamos medidas técnicas rigorosas para proteger seus dados:
- Todas as transmissões externas utilizam criptografia **TLS/HTTPS**.
- As credenciais de API salvas no aparelho são cifradas com chaves mantidas no hardware de segurança do dispositivo (**Android Keystore**).
- O código do aplicativo isola tarefas pesadas de inferência em processos separados (`:inference` e `:reasoning`) para prevenir vazamento de memória.

---

## 7. Direitos do Titular (LGPD e GDPR)

Em conformidade com a Lei Geral de Proteção de Dados Pessoais (LGPD — Lei nº 13.709/2018) e o Regulamento Geral sobre a Proteção de Dados (GDPR):
- Você tem o direito de saber quais dados são tratados, revogar consentimentos de anúncios a qualquer momento pelas configurações do app e solicitar esclarecimentos sobre o tratamento de informações.

---

## 8. Crianças e Menores de Idade

O KeyFlow não é direcionado a crianças menores de 13 anos e não coleta intencionalmente dados de menores. Se tomarmos conhecimento de que dados de uma criança foram coletados inadvertidamente, eles serão prontamente descartados.

---

## 9. Contato e Dúvidas

Para qualquer dúvida, comentário ou solicitação a respeito desta Política de Privacidade ou sobre as práticas de privacidade do KeyFlow, entre em contato com nosso responsável por privacidade:

- **E-mail de Contato:** `contato@arateki.com`
- **Desenvolvedor:** Arateki [YAN: Inserir endereço físico comercial se exigido pelo perfil de desenvolvedor da Play Store]
