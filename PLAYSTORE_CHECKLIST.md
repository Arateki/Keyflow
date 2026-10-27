# KeyFlow — Checklist de publicação na Play Store

Análise feita com base nas políticas verificadas em junho de 2026; **requisito de target API reconferido
na fonte oficial em 2026-09-08** (developer.android.com/google/play/requirements/target-sdk).
O app é **arquiteturalmente aprovável**, mas exige os itens abaixo antes de submeter.

---

## Por que pode haver escrutínio extra

O app combina três sinais que a revisão automática e humana da Play Store monitoram de perto:

- IME (pode ler tudo que o usuário digita)
- `RECORD_AUDIO` (captura áudio do microfone)
- Transmissão de dados para servidor externo

Nenhum desses é proibido isoladamente — há centenas de teclados e apps de STT publicados. O risco é ser marcado como **spyware falso-positivo** se a divulgação for insuficiente. A política cita explicitamente como spyware: *"recording audio and transmitting data off device in a manner unexpected to the user."* A defesa é mostrar que o usuário solicita ativamente cada gravação e que há consentimento informado.

---

## Checklist obrigatório (bloqueante para publicação)

### 0. targetSdk 36 — prazo JÁ VENCIDO

Desde **31 de agosto de 2026**, o Google Play exige `targetSdk >= 36` (Android 16) para **novos apps e
para atualizações** de apps existentes (phones/tablets/foldables). O KeyFlow está em **34**: neste estado
não dá para publicar nem atualizar. Extensão pode ser solicitada pelo Play Console até **1º de novembro
de 2026**.

Efeito colateral que costuma passar batido: app com target 34 ou inferior só fica disponível para novos
usuários em aparelhos com Android **igual ou anterior ao target**. Ou seja, ficar em 34 é que ENCOLHE o
alcance — some dos aparelhos novos, enquanto os antigos seguem atendidos.

**Não confundir com suporte a aparelho antigo**: quem define isso é o `minSdk` (24 = Android 7.0), que
NÃO muda ao subir o target. O custo de subir é adaptar o app às restrições novas de plataforma — como a
leitura de `ENABLED_INPUT_METHODS`, bloqueada no Android 14+ para `targetSdk >= 34`, que fazia o app
dizer "teclado não habilitado" em todo aparelho 14+ (corrigido em 2026-09-08, commit `9c1622e`). O gate
do emulador Android 17 (`.specs/quick/010-suite-no-aparelho/`) existe para achar essas antes da subida.

### 1. Política de Privacidade pública

- Criar uma URL pública e permanente (GitHub Pages, Notion público, ou domínio próprio)
- A URL deve ser informada no Play Console e dentro do app (tela de configuração)
- Conteúdo mínimo obrigatório:
  - O que é coletado: **áudio gravado pelo usuário sob demanda**
  - Com quem é compartilhado: **endpoint configurado pelo próprio usuário** (Whisper-compatible API)
  - Como é armazenado: arquivo temporário local, descartado após envio
  - Não são coletados: keystrokes, texto digitado, dados de localização
  - Contato do desenvolvedor

#### 1b. Aba Raciocínio — o que a política e o Data Safety também precisam cobrir (RPRV-01)

- **Modelos de texto locais** (Qwen3 0.6B, SmolLM2 1.7B, DeepSeek R1 1.5B e Gemma 4 E2B, pelo LiteRT-LM): rodam **100% no aparelho**, em processo próprio
  (`:reasoning`); o texto não sai do dispositivo. Os arquivos são baixados sob demanda do Hugging Face (o app só faz o pedido
  de download) e somem se o app for desinstalado. Licenças: Apache 2.0 e MIT (DeepSeek R1; `app/licenses/`, aba Docs → Licenças).
- **OpenAI (nuvem — só quando o usuário escolhe essa origem)**: o texto que o usuário manda gerar (hoje, a frase do botão
  Testar) é enviado à OpenAI em `api.openai.com/v1/responses`, com `store: false` (a resposta não fica guardada para consulta
  posterior pelo app — **isso não é "a OpenAI não guarda"**: ela ainda pode reter o conteúdo pelo prazo da política dela, e no
  login do plano o texto passa a valer nas regras de dados da conta ChatGPT do usuário; a política de privacidade deve dizer isso
  e apontar para a da OpenAI).
  Há dois jeitos de autenticar, ambos com as credenciais **só no aparelho** — cifradas no Keystore, fora do backup:
  - a **chave de API** que o próprio usuário cola;
  - o login **"Continue with ChatGPT"** (OAuth 2.0 + OpenID Connect com PKCE e registro dinâmico do cliente): o app é
    cliente **público** — **não existe `client_secret` no APK** — e guarda access/refresh token e o e-mail da conta (vem do ID
    token) **apenas no aparelho**, nunca em servidor do KeyFlow.
- **Nada disso passa por servidor do KeyFlow** (texto enviado, respostas, credenciais, e-mail) — mas, **para o Play Console, dado
  que sai do aparelho conta como coletado/compartilhado**, mesmo sem servidor do desenvolvedor no meio.
- **Data Safety (rascunho)**: acrescentar "User content → texto enviado à OpenAI a pedido do usuário" declarado como **coletado e
  compartilhado** (com o provedor que o usuário escolheu, opcional, criptografado em trânsito), do mesmo jeito que o áudio na
  seção 3. **Não marcar "não coletado"** sem antes conferir no formulário se alguma exceção se aplica (ex.: processamento
  efêmero) — decisão do Yan no Play Console. "Personal info → e-mail": o e-mail vem do ID token e fica só no aparelho (o login em
  si é entre o usuário e a OpenAI); conferir o mesmo critério.

#### 1c. Login ChatGPT — elegibilidade (RPRV-04) — decisão registrada em 2026-10-23

A OpenAI permite uso do plano do usuário em apps open-source e hospedados localmente e direciona apps pagos ou hospedados
remotamente ao formulário de interesse. Em 2026-10-23, Yan decidiu incluir no release os três logins por assinatura
(ChatGPT, Claude e Google). O login ChatGPT está habilitado no release; não há `client_secret` no APK. A pendência de
publicação é verificar no Console as credenciais OAuth e a elegibilidade aplicável ao app, sem alterar a decisão de produto.

### 2. Tela de consentimento (Prominent Disclosure)

A política exige divulgação **dentro do app**, visível no uso normal — não basta estar só na privacy policy.

Implementar uma tela de onboarding exibida na **primeira execução** (ou na primeira vez que cada permissão é necessária) com texto do tipo:

> "O KeyFlow grava seu áudio quando você toca no botão de microfone e envia a gravação para o endpoint configurado por você para transcrição. O áudio não é armazenado permanentemente. Nenhum texto digitado é transmitido."

Seguida de botão de aceite explícito (não basta o usuário sair da tela).

### 3. Data Safety Form no Play Console

Ao criar o app listing, preencher a seção **"Data safety"** declarando:

| Campo | Valor |
|---|---|
| Tipo de dado coletado | Audio files — Voice or sound recordings |
| Finalidade | App functionality |
| Compartilhado com terceiros | Sim (endpoint de API configurado pelo usuário) |
| Dados criptografados em trânsito | Sim (HTTPS) |
| Usuário pode solicitar exclusão | Sim (não há dados persistidos) |
| Keystrokes / dados digitados | **Não coletados** — declarar explicitamente |

---

## Pontos sem problema (não precisa de ação)

- **SYSTEM_ALERT_WINDOW (overlay)**: classificado como "special permission" — o Android já direciona o usuário às configurações do sistema para aprovação. Nenhuma declaração adicional na Play Store é necessária.
- **Foreground service com microfone**: permitido com `FOREGROUND_SERVICE_TYPE_MICROPHONE` no manifest (já implementado).
- **IME lendo o campo de texto ativo**: comportamento normal de todo teclado. Proibido é *transmitir* keystroke data — o app não faz isso.
- **API externa para STT**: permitido desde que declarado na Data Safety Form.

---

## Recomendações adicionais (não bloqueantes)

- **Categoria sugerida no listing**: "Productivity" ou "Tools"
- **Screenshots**: incluir o overlay flutuante e o teclado em uso — ajuda o revisor a entender o propósito do app antes de investigar as permissões
- **Descrição do listing**: mencionar explicitamente "teclado de voz" e "transcrição de áudio sob demanda" para que o contexto das permissões seja imediatamente claro
- ~~**targetSdk**: atualizar para 35~~ — **desatualizado, e não é "recomendação": virou BLOQUEANTE.** Ver abaixo.

---

## Estado atual dos bloqueantes (conferido em 2026-09-09)

| item | estado |
|---|---|
| 0. `targetSdk >= 36` | ✅ subido em `9bc8a96` (`compileSdk` 36 junto); validado no A32, no API 34 e no A17 |
| 1. Política de privacidade pública + link no app | ❌ ausente; existe só um parágrafo na aba Docs ("O áudio só é enviado à origem configurada em Voz. Tokens ficam apenas no aparelho.") |
| 2. Prominent disclosure com aceite explícito | ❌ ausente; o onboarding só diz "Sua voz vira texto em qualquer campo." |
| 3. Data Safety Form | ⏳ depende do Play Console, não do código |
| 4. Política e Data Safety cobrem a aba Raciocínio (seção 1b) | ⏳ texto pronto (2026-09-30); entra junto com PS-2/PS-4 |
| 5. Login ChatGPT (seção 1c) | ⏸ desligado no release (`enabled = BuildConfig.DEBUG`) — **[YAN] decidir a elegibilidade; NÃO publicar ligado** |
| 6. Build de release (PS-5, V1-3) | ✅ 2026-10-24: R8 + redução de recursos ligados, `bundleRelease` gera o AAB assinado com a chave de upload (lida de fora do repositório), `versionCode` 1 / `versionName` 1.0.0, `applicationId` `com.arateki.keyflow`; smoke do APK de release no emulador (`scripts/smoke-release.sh`). Falta **[YAN]**: criar o app no Console com esse id, cadastrar a chave de upload (SHA-256 `AB:17:29:3A:…:A8:DC`), o client OAuth Google com o pacote novo e subir o AAB na trilha interna. Detalhes: `.specs/codebase/ENVIRONMENT.md` › "Release". |

## Estado atual do código relevante

- Manifest: `app/src/main/AndroidManifest.xml` — permissões `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE_TYPE_MICROPHONE` já declaradas
- IME: `KeyFlowKeyboardService` — `registerInserter` em `onStartInputView` (não lê campos invisíveis)
- Transmissão: `OverlayCoordinator` + `OverlayTranscriber` — apenas o arquivo de áudio é enviado, nunca keystroke data
- Clipboard fallback: quando não há campo ativo, o texto transcrito vai para a área de transferência local — não é transmitido a lugar algum além do endpoint configurado pelo usuário
