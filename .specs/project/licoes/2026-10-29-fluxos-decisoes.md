# 2026-10-29 — Decisões de Fluxos e substituição de ações

Respostas do Yan às Q1–Q7 de Fluxos, registradas em D10–D16: conversa do agente separada da aba KeyFlow; Ditar acionado por toque usa Alternar mesmo com a orbe em PTT; gatilho sombreia Settings e sua remoção devolve a ação; Rodar agora no modal usa o mesmo host; gravação em andamento recusa o disparo e avisa; os padrão só sugerem atalho, sem ligar; a bancada consumirá os tipos de Fluxos quando for mexida, sem task agora.

Regra geral de 29/10 às 22:57 (D17 de Fluxos, D11 de Notificação): gravar é uma ação/fluxo entre outros, sem reservar obrigatoriamente um toque. O aviso confirma qualquer substituição de ação atribuída em toque, gesto ou botão e nomeia o que será substituído. Nenhuma/mesma ação não significa perda; cancelar a confirmação preserva os dados. O aviso não deve privilegiar gravação.

Na Notificação, isso substitui 1 toque fixo por três toques remapeáveis: Gravar/enviar continua padrão próprio, não validação obrigatória. Copiar PTT pode resultar em Nenhuma no 1 toque, já que a superfície não suporta segurar; não se inventa fallback para gravar. Dados explícitos Nenhuma/desconhecida devem sobreviver ao codec. A restrição de gatilhos de fluxo a 2/3 toques permanece.

O agente do fluxo precisa de controlador, histórico, gate de confirmação e executor próprios da sessão; só separar mensagens na tela manteria o risco de responder/cancelar o chat da aba. Reusar política, catálogo, motores e registro sanitizado não exige compartilhar conversa.

Escopo: PR #74 consultado antes das edições, ainda aberto; usados apenas os contratos de Fluxos/Notificação da main 182d3ed1. Não se importou a spec da orbe do teclado nem mudanças da branch do #74. As tasks de implementação e seus requisitos continuam Pending; esta entrega registra decisões e critérios de prova, sem declarar implementação ou testes Android concluídos.
