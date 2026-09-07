# KeyFlow — Checklist de publicação na Play Store

Análise feita com base nas políticas verificadas em junho de 2026.
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

### 1. Política de Privacidade pública

- Criar uma URL pública e permanente (GitHub Pages, Notion público, ou domínio próprio)
- A URL deve ser informada no Play Console e dentro do app (tela de configuração)
- Conteúdo mínimo obrigatório:
  - O que é coletado: **áudio gravado pelo usuário sob demanda**
  - Com quem é compartilhado: **endpoint configurado pelo próprio usuário** (Whisper-compatible API)
  - Como é armazenado: arquivo temporário local, descartado após envio
  - Não são coletados: keystrokes, texto digitado, dados de localização
  - Contato do desenvolvedor

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
- **targetSdk**: atualizar para 35 antes de submeter (a Play Store passou a exigir targetSdk ≥ 35 para novos apps a partir de agosto de 2024)

---

## Estado atual do código relevante

- Manifest: `app/src/main/AndroidManifest.xml` — permissões `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE_TYPE_MICROPHONE` já declaradas
- IME: `KeyFlowKeyboardService` — `registerInserter` em `onStartInputView` (não lê campos invisíveis)
- Transmissão: `OverlayCoordinator` + `OverlayTranscriber` — apenas o arquivo de áudio é enviado, nunca keystroke data
- Clipboard fallback: quando não há campo ativo, o texto transcrito vai para a área de transferência local — não é transmitido a lugar algum além do endpoint configurado pelo usuário
