# 2026-10-28 — Catálogo combinado de candidatas de raciocínio (modelos-novos)

Duas branches usaram `EMBEDDED_VERSION = 2026102702` para catálogos diferentes. A sincronização preserva os sete modelos validados da main (incluindo o Qwen3 4B) e acrescenta seis candidatas apenas ao debug; o catálogo combinado usa `2026102801`, com a impressão digital recalculada a partir dos dados de todos os arquivos.

O merge precisa combinar também as expectativas dos testes: a lista de release tem sete entradas, o debug tem treze e a maior exigência de RAM continua sendo a do Qwen3 4B. As estimativas de RAM das candidatas não viram medições de aparelho por entrarem no catálogo.

O logo Nanbeige foi redesenhado em vetor a partir do avatar oficial: a barra superior é curva, a faixa central ocupa toda a largura e os cinco blocos usam as proporções da referência, normalizadas em 24×24. A origem fica no README de licenças, sem atribuir ao avatar a licença dos pesos. A adaptação de contraste existente atende claro e escuro.

Cards abaixo da dobra recebem quadros próprios na galeria, no mesmo padrão do Potente. Isso torna revisáveis os nomes, logos, tamanhos e avisos de pensamento de todas as candidatas sem alterar o produto.

A primeira sincronização desta branch antiga usa a regra `core.attributesFile` temporária descrita em `TESTING.md`, preservando os acréscimos antigos de STATE e licenças com union. Goldens conflitantes vêm da main e são regenerados; só capturas da mudança são aprovadas.

Gate JVM/lint: 1804/1804 testes em 168 suítes, sem ignorados e sem ocorrências de lint. Instrumentados afetados: 23/23. A captura completa passou 244/244 no Deck, com os seis cards revisados nos dois temas e logo Nanbeige legível. A comparação sem fuzz identificou somente dez mudanças intencionais acima do limiar; os 12 quadros individuais são novos. As quatro diferenças pequenas fora de Raciocínio (Docs 49/49 pixels; tutorial do microfone 74/75) não foram aprovadas: os 222 goldens fora da mudança ficam bit-idênticos. O resultado do CI acompanha o PR e o relatório de entrega. As seis candidatas continuam sem validação nativa dos pesos pelo Yan.
