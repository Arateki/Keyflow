# 2026-10-30 — Orbe do teclado, Fase A: lugares, geometria, codec e resolvedor (equipe/orbe-teclado-a)

KBO-T1..T3 da feature `keyboard-orb`, em `com.keyflow.orbe` (JVM puro, conferido pelo `ArchitectureRulesTest`: sem `android.*`, `androidx.*` nem cofre).

## KBO-T1 — Lugares, geometria e hit-test

- `Interacoes.kt` (compartilhado com Fluxos e Notificação): acrescentados `Interacao` (as sete interações da orbe) e `SuporteDeInteracoes` (`TECLADO` e `FLUTUANTE` suportam as sete; `NOTIFICACAO` suporta `TOQUE_1..3`), mantendo o enum `LugarDaOrbe` existente sem duplicação.
- `GeometriaDaOrbe` e `HitDaOrbe`: círculo ocioso inscrito no miolo do encaixe (disjunto da faixa de 36 dp); círculo sobressalente com fundo no fundo do encaixe e `diâmetro = min(largura, altura + faixa)`, disjunto das teclas abaixo e dos botões vizinhos na mesma barra; `HitDaOrbe` usa a equação do disco `(x - cx)² + (y - cy)² <= r²` (rejeitando `(1, 1)` e os cantos do retângulo delimitador fora do disco) e acompanha a rolagem horizontal da barra.
