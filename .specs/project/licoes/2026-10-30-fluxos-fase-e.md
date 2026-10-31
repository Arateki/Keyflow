# 2026-10-30 — Fluxos: atalhos, excluir e a aba ligada (equipe/fluxos-fase-e, FLX-T20..T22)

- A seção Atalhos é uma lista de linhas puras (`FluxosAtalhosSecao`) que recebe um `AtalhosDoModal` com as operações; quem grava é o `FluxosModal`, por `atualizarFluxos`/`atualizarFluxosEBarras` (botão e gatilho na mesma transação; recusado = nem o botão nasce).
- Confirmação (ligar com substituição, excluir) é uma tela do mesmo `ConfigModal`, não um segundo popup: a regra do `ConfigModal` de não empilhar overlay vale também aqui.
- O gatilho sombreia: nenhum caminho desta fase escreve `Settings`; o teste confere que só `flows_json`, `flow_triggers_json` (e as barras) são gravados.
- `acaoEfetivaDoGesto` passou a devolver o nome já traduzido (`PadroesFluxo.nomeExibido`), para Sobrepor e tour não mostrarem a chave em português num fluxo padrão.
- `lugaresDisponiveis()` devolve só `FLUTUANTE` até as T28/T29; a modal não desenha os grupos de notificação e teclado.
- A aba Fluxos só sai do "Em breve" quando o `ConfigScreen` recebe `fluxosRepository`; sem ele (galeria, testes sem fiação) segue o cartão. A fonte do seletor de modelo (`FonteDaTela`) usa os modelos locais instalados e as conexões que a tela já observa; não há catálogo de nuvem, então provedor conectado oferece só a linha do provedor.
- O gate do pool pode ficar na fila por muito tempo com a máquina cheia: o `timeout` do comando conta a fila.
