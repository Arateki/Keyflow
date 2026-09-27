# Q9 — As sugestões do corretor nunca funcionaram de verdade [PENDENTE]

> **Status**: registrado, não implementado. O Yan pediu explicitamente para deixar para depois
> (2026-09-07): *"apesar de nunca termos feito funcionar, inclusive podemos deixar isso de funcionar
> dela pra dps, contanto que esteja registrado"*.

## O que existe hoje

A faixa (`CandidatesView`) está pronta: renderiza os chips, rola na horizontal, aparece no retraído
enquanto houver sugestão e some quando a lista esvazia (Q8-bis, commit `93c22a6`). O que falta é
alguém **produzir** as sugestões.

## Por que ela fica vazia

1. **Só a grade do KeyFlow alimenta o corretor.** `requestSuggestions` é chamado apenas de dentro de
   `handleKey`, que trata teclas da grade Canvas. Quem digita em **teclado físico** (o caso do Yan:
   SSH/tmux com teclado bluetooth) nunca gera pedido de sugestão — `onUpdateSelection` só **limpa** a
   faixa quando percebe que o texto mudou por fora.
2. **Texto que chega por voz não vira palavra rastreada.** A transcrição é comitada direto; o
   `wordTracker` não passa a acompanhar a palavra resultante, então não há o que sugerir sobre ela.
3. **Falta confirmar o corretor do sistema no A32.** O `SystemSpellChecker` nunca foi observado
   devolvendo sugestões no device do Yan — pode ser que o serviço de correção da Samsung não responda
   ao `TextServicesManager` desse jeito, o que mudaria o desenho da solução.

## Escopo quando for feito

- [ ] Rastrear a palavra atual mesmo quando o texto entra por fora (teclado físico ou colagem):
      `onUpdateSelection` + `getTextBeforeCursor`, extraindo a palavra sob o cursor.
- [ ] Depois de comitar transcrição de voz, apontar o `wordTracker` para a última palavra, para a
      faixa poder sugerir correção do que a voz escreveu.
- [ ] Antes das duas acima: instrumentar e **confirmar no A32** que o `SystemSpellChecker` responde;
      se não responder, decidir alternativa (dicionário próprio? desistir da faixa?).
- [ ] Teste instrumentado com sugestão real chegando à faixa — hoje os testes só cobrem a faixa vazia
      e a geometria do slot.

## Por que não foi feito junto

O ajuste que originou isso era de **layout** (as barras espelhadas saírem diferentes). Fazer o
corretor funcionar mexe no rastreio de palavra do IME, que hoje assume que só o KeyFlow escreve no
campo — é outra tarefa, com risco próprio.
