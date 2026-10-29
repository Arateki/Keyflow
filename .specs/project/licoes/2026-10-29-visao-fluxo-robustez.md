# 2026-10-29 — Visão: falhas de janela, encerramento e primeiro quadro

A permissão de overlay pode desaparecer entre criar o diálogo e mostrá-lo. O recomeço trata a recusa da janela e encerra a etapa; uma falha de sessão não pode impedir a limpeza do cache nem a restauração das visibilidades anteriores. O epílogo usa `finally`, e a destruição sinaliza à sessão que não deve chamar `startForeground` de novo.

A espera do primeiro quadro tem prazo de cinco segundos e erro traduzido. Esse prazo não sincroniza composição: os dois callbacks de quadro anteriores à captura continuam existindo. O `ImageReader` entrega em uma thread dedicada, que monta e examina o bitmap; a resposta volta à thread principal. Cancelar invalida a resposta, recicla o bitmap tardio e fecha a fonte depois da conversão em andamento.

Dezesseis pixels pretos não provam que uma tela está protegida: texto de terminal pode ficar entre eles. A checagem percorre as linhas do recorte e só classifica como preto o quadro sem conteúdo RGB algum. Isso mantém o aviso para a imagem preta produzida por `FLAG_SECURE`; uma tela legitimamente inteira preta é indistinguível dessa saída, e continua gerando o mesmo aviso.

As regressões simulam `BadTokenException` ao mostrar a janela e `SecurityException` ao fechar a sessão, verificam restauração de cache/views, observam a destruição sem restauração do FGS e usam uma fonte sem frames para provar o prazo. Um bitmap preto com um pixel claro fora da grade anterior deve ser aceito.

A prova real no Deck encontrou o seletor ainda em retrato sobre uma tela já em paisagem: o `DisplayListener` sozinho não invalidou as coordenadas. O serviço também observa `onConfigurationChanged`, comparando uma cópia da configuração da seleção com orientação, tamanho e densidade novos. Isso cobre a ordem em que o evento de display antecede a atualização de `Resources`; barras e outras mudanças sem geometria não encerram a captura. A prova opt-in permanece exigindo o diálogo de recomeço após a rotação real.

Contrato consultado: [Service.onConfigurationChanged](https://developer.android.com/reference/android/app/Service#onConfigurationChanged(android.content.res.Configuration)) informa que serviços recebem as alterações e que os recursos já refletem a configuração nova nesse callback.
