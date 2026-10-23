# Aba Computador — ideias antes da spec

> Semente da spec, não é a spec. Junta o que o Yan já decidiu sobre a aba **Computador** para quando essa frente
> entrar na fila (hoje: "depois", sem spec — ver `DEVELOPMENT-ORDER.md`). A spec de verdade começa pelo
> levantamento de open source do item 3.

## Onde estamos

A aba existe (`app/src/main/java/com/keyflow/ui/config/ComputadorPanel.kt`, do trabalho do Gemini) com as opções
**Local** e **SSH**, mas sem motor: o "Testar conexão" é simulado e, na v1, a aba mostra só o cartão "Em breve".

## O que o Yan quer (2026-10-22)

1. **Terminais dentro do app.** A aba libera um ou vários terminais — local ou por SSH — que o **agente** e o
   **usuário** usam dentro do KeyFlow.
2. **Imagens no terminal, em todos os protocolos.** O terminal do app desenha imagens nos três protocolos em uso:
   - **kitty graphics protocol**, completo: envio em pedaços, PNG e RGB/RGBA, posicionamento, apagar, animação e
     os *unicode placeholders* (o Herdr usa esse formato para repassar imagens);
   - **sixel**;
   - **imagens inline do iTerm2** (OSC 1337).

   Motivo: ver as imagens de agentes de terminal (Claude Code e outros) rodando no servidor, por SSH e pelo
   Herdr. Em out/2026 nenhum app Android cobre os três: o Termius não desenha nenhum; o Termux tem sixel e iTerm2
   só em build de desenvolvimento (PR termux-app#2973); o ConnectBot (biblioteca termlib 0.2.0) tem kitty e
   iTerm2, desligados por padrão. Fontes (2026-10-22): https://docs.termius.com/changelog/android,
   https://github.com/termux/termux-app/pull/2973, https://github.com/connectbot/termlib/releases/tag/0.2.0.
3. **Partir do open source.** Antes de desenhar, levantar o que existe desse gênero para estudar ou reaproveitar
   e construir do nosso jeito. Candidatos já vistos (licenças a conferir — o app terá partes pagas):
   - ConnectBot / `termlib` — emulador para Android com kitty e iTerm2;
   - Termux `terminal-emulator` / `terminal-view` — o mais usado no Android; sixel e iTerm2 no `master`;
   - libghostty — núcleo do Ghostty (o Termius de iOS passou a usar);
   - Terminator (F-Droid) — declara kitty, sixel e iTerm2;
   - codificadores: libsixel, chafa, rasterm.

## Pontos para a spec decidir

- **Agente com terminal × TLS-03.** A spec do agente (`agent-harness`) proíbe ferramenta que toque em segredo,
  inclusive chave SSH. O desenho provável: a chave fica no cofre e só o app abre a sessão; o agente recebe a
  sessão aberta, nunca a chave. Precisa virar requisito explícito.
- **O próprio teclado no terminal.** O terminal do app é um campo `TYPE_NULL`; o KeyFlow já comita letra a letra
  nesses campos (Q3, bug do Termius). O terminal novo precisa funcionar bem com o KeyFlow e com teclados físicos.
- **Vários terminais.** Abas ou divisões, quem vê o quê (usuário e agente no mesmo terminal ou em separados) e o
  limite de sessões abertas (memória e bateria).
