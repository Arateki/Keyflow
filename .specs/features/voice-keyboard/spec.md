# Voice Keyboard — Specification

## Problem Statement

Digitar no celular para operar agentes de código é lento e o teclado virtual rouba metade da tela de leitura. O ditado nativo do Android é amarrado a um único motor e não aponta para APIs próprias. Precisamos de um teclado que não ocupe tela e de um gatilho de voz sempre acessível que transcreva via um motor plugável e insira o texto no campo atual.

## Goals

- [ ] Ditar em qualquer app e ver o texto no campo focado em ≤ 5 s após parar de falar.
- [ ] Tela 100% livre de teclado durante o uso (0 px desenhados pelo IME).
- [ ] Trocar de motor de transcrição editando apenas URL + auth, sem recompilar.

## Out of Scope

| Feature | Razão |
| ------- | ----- |
| Teclado visível com teclas | v1 é só invisível; digitar à mão usa um teclado normal via troca |
| Limpeza de texto por LLM | Whisper já pontua; evita 2ª chamada/latência no MVP |
| Parada por silêncio (VAD) | Imprevisível para ditados de raciocínio; v2 |
| Porte iOS | Plataforma proíbe overlay sobre apps e microfone em teclados de terceiros |
| Múltiplos perfis de API | 1 endpoint configurável basta no MVP |

---

## User Stories

### P1: Teclado invisível ⭐ MVP

**User Story**: Como usuário, quero um teclado que não desenhe nada na tela para ter a área inteira livre para leitura enquanto opero por voz.

**Why P1**: É o pilar "mais área de leitura" e o canal de inserção de texto.

**Acceptance Criteria**:
1. WHEN o teclado KeyFlow está ativo e um campo recebe foco THEN o sistema SHALL não exibir nenhuma superfície de teclado (0 px de altura).
2. WHEN há um campo de texto com `InputConnection` ativo e o app pede para inserir texto THEN o teclado SHALL inserir via `commitText` na posição do cursor.
3. WHEN o campo ganha ou perde foco THEN o teclado SHALL notificar o serviço de overlay sobre a disponibilidade de destino.

**Independent Test**: Selecionar o teclado, focar um campo em outro app e confirmar que nada é desenhado; via gatilho de teste, inserir um texto fixo e vê-lo aparecer.

---

### P1: Ditar e inserir ⭐ MVP

**User Story**: Como usuário, quero tocar um botão flutuante, falar e ver minha fala transcrita aparecer no campo focado.

**Why P1**: É a fatia vertical central do produto.

**Acceptance Criteria**:
1. WHEN toco o botão flutuante para iniciar THEN o sistema SHALL começar a capturar áudio e indicar visualmente que está gravando.
2. WHEN encerro a gravação THEN o sistema SHALL enviar o áudio à API configurada e indicar "enviando".
3. WHEN a API retorna a transcrição E há campo focado THEN o sistema SHALL inserir o texto via teclado e voltar ao estado ocioso.
4. WHEN ocorre erro (rede/HTTP/timeout) THEN o sistema SHALL indicar erro sem inserir texto e preservar o áudio/transcrição quando possível.

**Independent Test**: Dentro do navegador, com um campo focado, gravar uma frase e vê-la inserida.

---

### P1: Configurar o motor de transcrição ⭐ MVP

**User Story**: Como usuário, quero configurar URL, autenticação, modelo e idioma para apontar ao meu motor Whisper-compatible.

**Why P1**: Sem config válida o app não transcreve.

**Acceptance Criteria**:
1. WHEN abro a tela de configuração THEN o sistema SHALL permitir editar URL do endpoint, nome+valor do header de auth, modelo e idioma (opcional).
2. WHEN salvo a configuração THEN o sistema SHALL persisti-la e guardar a chave de auth de forma criptografada.
3. WHEN a configuração está incompleta E tento gravar THEN o sistema SHALL avisar que falta configurar o motor.

**Independent Test**: Preencher URL+key de dois motores diferentes (ex.: OpenAI e Groq) e confirmar que ambos transcrevem sem recompilar.

---

### P1: Onboarding de permissões ⭐ MVP

**User Story**: Como usuário, quero ser guiado a conceder as permissões necessárias, já que são várias e em telas do sistema.

**Why P1**: Sem teclado ativo + overlay + microfone, nada funciona; e o caminho é pouco descobrível.

**Acceptance Criteria**:
1. WHEN abro o app pela 1ª vez THEN o sistema SHALL listar o estado de cada pré-requisito (teclado ativo, overlay, microfone) e oferecer atalho para concedê-lo.
2. WHEN um pré-requisito está pendente THEN o sistema SHALL impedir/avisar a ativação do botão até resolvê-lo.
3. WHEN todos os pré-requisitos estão concedidos THEN o sistema SHALL permitir ligar o botão flutuante.

**Independent Test**: Em instalação limpa, seguir o onboarding e chegar ao estado "pronto para gravar".

---

### P2: Modo de gravação configurável

**User Story**: Como usuário, quero escolher entre tap-toggle e push-to-talk conforme a fala.

**Why P2**: Refina a UX; o MVP funciona com um default (tap-toggle).

**Acceptance Criteria**:
1. WHEN seleciono "tap-toggle" THEN um toque inicia e outro encerra a gravação.
2. WHEN seleciono "push-to-talk" THEN segurar grava e soltar encerra e envia.

**Independent Test**: Alternar o modo na config e verificar os dois comportamentos.

---

### P2: Sem campo focado (transcrição pendente)

**User Story**: Como usuário, quando gravo sem um campo focado, quero feedback claro e não perder a transcrição.

**Why P2**: Pedido explícito; evita perder ditados.

**Acceptance Criteria**:
1. WHEN encerro a gravação E não há campo com `InputConnection` THEN o sistema SHALL manter a transcrição pendente e exibir feedback "toque num campo para colar".
2. WHEN existe transcrição pendente E um campo recebe foco THEN o botão SHALL mudar para "pronto para colar".
3. WHEN toco o botão no estado "pronto para colar" THEN o sistema SHALL inserir a transcrição pendente e limpá-la.

**Independent Test**: Gravar na home (sem campo), depois focar um campo e colar com um toque.

---

### P2: Alternar para teclado normal

**User Story**: Como usuário, quero voltar rápido a um teclado normal para digitar à mão sem sair do app atual.

**Why P2**: O teclado invisível não tem teclas; preciso de uma saída.

**Acceptance Criteria**:
1. WHEN dou três toques rápidos no botão flutuante THEN o sistema SHALL abrir o seletor de teclado do sistema (`showInputMethodPicker`).
2. WHEN os três toques são detectados no modo tap-toggle THEN o sistema SHALL cancelar qualquer gravação iniciada pelo 1º toque, sem enviar áudio.

**Independent Test**: Com o teclado invisível ativo, dar três toques e ver o seletor; escolher outro teclado e digitar.

---

## Edge Cases

- WHEN não há rede THEN o sistema SHALL indicar erro de conexão e preservar a transcrição pendente (sem áudio perdido silenciosamente).
- WHEN a API responde 401/403 THEN o sistema SHALL indicar "auth inválida" e apontar para a config.
- WHEN a gravação fica abaixo de ~0,3 s ou sem fala THEN o sistema SHALL descartar e indicar "nada capturado".
- WHEN o campo perde foco enquanto a transcrição está em voo THEN o sistema SHALL cair no fluxo de transcrição pendente.
- WHEN a permissão de overlay ou microfone é revogada em runtime THEN o sistema SHALL detectar e reconduzir ao onboarding.
- WHEN o foreground service é morto pelo sistema THEN o sistema SHALL reiniciar (`START_STICKY`) e, se em background sem poder recriar o tipo `microphone`, exigir reativação ao voltar ao foreground.
- WHEN o áudio excede o limite da API (ex.: 25 MB) THEN o sistema SHALL avisar e não enviar.
- WHEN o teclado KeyFlow não é o IME ativo E o usuário grava THEN o sistema SHALL avisar que o teclado precisa estar selecionado.

---

## Requirement Traceability

| ID | Story | Fase | Status |
| --- | ----- | ---- | ------ |
| KBD-01 | P1 Teclado invisível | Design | Pending |
| KBD-02 | P1 Teclado invisível | Design | Pending |
| KBD-03 | P1 Teclado invisível | Design | Pending |
| KBD-04 | P2 Alternar teclado | Design | Pending |
| REC-01 | P1 Ditar e inserir | Design | Pending |
| REC-02 | P1 Ditar e inserir | Design | Pending |
| REC-03 | P1 Ditar e inserir | Design | Pending |
| REC-04 | P2 Modo de gravação | Design | Pending |
| REC-05 | P1 Ditar e inserir | Design | Pending |
| REC-06 | P2 Sem campo focado | Design | Pending |
| API-01 | P1 Ditar e inserir | Design | Pending |
| API-02 | P1 Ditar e inserir | Design | Pending |
| API-03 | P1 Ditar e inserir | Design | Pending |
| CFG-01 | P1 Configurar motor | Design | Pending |
| CFG-02 | P1 Configurar motor | Design | Pending |
| CFG-03 | P1 Onboarding | Design | Pending |
| ONB-01 | P1 Onboarding | Design | Pending |
| ONB-02 | P1 Onboarding | Design | Pending |
| ONB-03 | P1 Onboarding | Design | Pending |

**ID format:** `[ÁREA]-[NÚMERO]` (KBD=teclado, REC=gravação/overlay, API=transcrição, CFG=config, ONB=onboarding)

**Status values:** Pending → In Design → In Tasks → Implementing → Verified

**Coverage:** 19 requisitos · 19 mapeados a tasks (ver `tasks.md`) · 0 não-mapeados ✅

---

## Success Criteria

- [ ] Ditar dentro do navegador com um campo focado e ver o texto inserido em ≤ 5 s.
- [ ] Nenhum pixel de teclado desenhado durante o uso.
- [ ] Trocar a URL/auth para outro motor Whisper-compatible e funcionar sem recompilar.
- [ ] Gravar sem campo focado, depois focar e colar com um toque (sem perder a fala).
- [ ] Três toques abrem o seletor de teclado.
