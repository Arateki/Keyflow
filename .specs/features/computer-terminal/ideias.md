# Aba Computador — ideias antes da spec

> Semente da spec. **A spec de verdade agora existe**: `spec.md`, `design.md` e `tasks.md` desta pasta (rascunho de 2026-10-29, fora da v1; `DEVELOPMENT-ORDER.md` › Bloco 16).
> Este arquivo fica como histórico do que o Yan pediu em 22/10 e **marca o que ele decidiu em 29/10** (D1..D11 do `spec.md`). Onde houver divergência, vale a spec.

## Onde estamos

A aba existe (`app/src/main/java/com/keyflow/ui/config/ComputadorPanel.kt`, do trabalho do Gemini) com as opções
**Local** e **SSH**, mas sem motor: o "Testar conexão" é simulado e, na v1, a aba mostra só o cartão "Em breve".
*(Decidido em 29/10: o painel é reescrito; a opção "Local" sai da interface; o "Testar conexão" passa a ser real — CMP-08.)*

## O que o Yan quer (2026-10-22)

1. **Terminais dentro do app.** A aba libera um ou vários terminais — local ou por SSH — que o **agente** e o
   **usuário** usam dentro do KeyFlow.
   - **Decidido (29/10, D1): agora só SSH.** O terminal **local** fica para depois e está registrado como fora de escopo. Ideia futura do Yan: algo como o **Termux**, idealmente reaproveitando-o — **anotado que a licença do
     `terminal-emulator`/`terminal-view` do Termux é ambígua (repositório "GPLv3 only"; `LICENSE.md` declara Apache-2.0 para os dois módulos, mas sem arquivo de licença nem cabeçalho neles) e exige parecer antes de qualquer reaproveitamento** (Q12 do `spec.md`).
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
   - **Decidido (29/10, D2): kitty, iTerm2 E sixel já na primeira entrega de imagens** (Fase 2; CMP-37..45). O termlib traz kitty e iTerm2; o sixel é nosso (decodificador + anúncio no DA1).
   - **Decidido (29/10, D6): Herdr só precisa ser compatível e exibir dentro do terminal** (a TUI por SSH, placeholders kitty funcionando). Nada de cliente nativo do daemon.
3. **Partir do open source.** Antes de desenhar, levantar o que existe desse gênero para estudar ou reaproveitar
   e construir do nosso jeito. Candidatos já vistos (licenças a conferir — o app terá partes pagas):
   - ConnectBot / `termlib` — emulador para Android com kitty e iTerm2; **escolhido (D2)**: Apache-2.0, libvterm MIT;
   - Termux `terminal-emulator` / `terminal-view` — o mais usado no Android; sixel e iTerm2 no `master`; **licença ambígua, tratar como NÃO** até haver parecer;
   - libghostty — núcleo do Ghostty (o Termius de iOS passou a usar); **descartada (D4)**: exigiria Android 10+ (ELF TLS do Zig) e o `minSdk 24` continua;
   - codificadores: libsixel (MIT) e rasterm servem de guia para o decodificador de sixel; chafa é LGPL-3.0 e **não entra** (o `chafa` continua útil como *ferramenta de teste* no servidor).

   ~~Terminator (F-Droid) — declara kitty, sixel e iTerm2.~~ **Retirado (29/10, D11): entrou por confusão** (não existe app Android com esse nome; o parente mais próximo, ReTerminal, é MIT mas roda sobre o `TerminalView` do Termux e herda a ambiguidade de licença).

   SSH: **cbssh** (`org.connectbot.sshlib`, Apache-2.0), do mesmo autor do termlib (D2).

## Pontos para a spec decidir

- **Agente com terminal × TLS-03.** A spec do agente (`agent-harness`) proíbe ferramenta que toque em segredo,
  inclusive chave SSH. O desenho provável: a chave fica no cofre e só o app abre a sessão; o agente recebe a
  sessão aberta, nunca a chave. Precisa virar requisito explícito.
  - **Resolvido (29/10, D5):** emenda aprovada, escrita no `agent-harness/spec.md` (AGT-E1..E6) e especificada em CMP-46..58. Cada host tem "pode ser usado pelo agente" (padrão **não**); só o app abre a sessão; senha/sudo só o usuário digita; **sem "permitir sempre" para `send`**; nada via MCP externo; registro de **30 dias** (D8).
- **O próprio teclado no terminal.** O terminal do app é um campo `TYPE_NULL`; o KeyFlow já comita letra a letra
  nesses campos (Q3, bug do Termius). O terminal novo precisa funcionar bem com o KeyFlow e com teclados físicos.
  - **Em spec:** CMP-26..28 (teclas extras, teclado físico, KeyFlow IME e ditado sem duplicar nem dar Enter); provado na CMP-T1/T14.
- **Vários terminais.** Abas ou divisões, quem vê o quê (usuário e agente no mesmo terminal ou em separados) e o
  limite de sessões abertas (memória e bateria).
  - **Resolvido (29/10, D5):** abas; usuário e agente em **sessões separadas**, com opção de **compartilhar**; no máximo 8 sessões (3 do agente) — CMP-29, CMP-47, CMP-48.

## Também decidido em 29/10

- **D3 — Toolchain:** o Yan prefere **atualizar** Kotlin/Compose/coroutines a manter um fork preso; é uma frente separada (spike de atualização). A CMP-T0 depende dela; o fork preso é só contingência.
- **D4 — `minSdk 24`** continua.
- **D7 — gratuita** como o resto do app (sem anúncio na aba nem no terminal — A11).
- **D9 — Serviço em primeiro plano `specialUse`** para manter as conexões (justificativa da Play redigida na spec).
- **D10 — Chave SSH moderna:** ed25519 gerada no aparelho (Keystore quando possível; envelope cifrado quando não), importação, biometria opcional, `known_hosts` com confirmação da impressão digital.

## Fica para depois (não está na spec)

Terminal local (Termux/PRoot), SFTP, port forwarding, agent forwarding, jump hosts, importar `~/.ssh/config`, snippets, Mosh (GPL-3.0), cliente do Herdr, captura do terminal como imagem para o modelo, etapa Computador nos Fluxos.
