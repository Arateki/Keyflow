# 2026-10-30 — Orbe do teclado, Fase A: lugares, geometria, codec e resolvedor (equipe/orbe-teclado-a)

KBO-T1..T3 da feature `keyboard-orb`, em `com.keyflow.orbe` (JVM puro, conferido pelo `ArchitectureRulesTest`: sem `android.*`, `androidx.*` nem cofre).

## KBO-T1 — Lugares, geometria e hit-test

- `Interacoes.kt` (compartilhado com Fluxos e Notificação): acrescentados `Interacao` (as sete interações da orbe) e `SuporteDeInteracoes` (`TECLADO` e `FLUTUANTE` suportam as sete; `NOTIFICACAO` suporta `TOQUE_1..3`), mantendo o enum `LugarDaOrbe` existente sem duplicação.
- `GeometriaDaOrbe` e `HitDaOrbe`: círculo ocioso inscrito no miolo do encaixe (disjunto da faixa de 36 dp); círculo sobressalente com fundo no fundo do encaixe e `diâmetro = min(largura, altura + faixa)`, disjunto das teclas abaixo e dos botões vizinhos na mesma barra; `HitDaOrbe` usa a equação do disco `(x - cx)² + (y - cy)² <= r²` (rejeitando `(1, 1)` e os cantos do retângulo delimitador fora do disco) e acompanha a rolagem horizontal da barra.

## KBO-T2 — Codec e repositório

- `CodecOrbeTeclado` (`"v": 1`) codifica e decodifica `ConfigOrbeTeclado` (`copy`, `skin`, `mode` = `toggle`|`ptt`, `taps` = `1`|`2`|`3`) usando `opt(chave) as? String` em vez de `optString` (evitando `"null"` literal do `org.json` Android quando uma chave vale `JSONObject.NULL`).
- Ações `flow:<id>` (`RodarFluxo`) e desconhecidas (`Desconhecida`) são preservadas byte a byte ao regravar o JSON; ação desconhecida no 1 toque nunca é reescrita para `record` (`GravarEnviar`).
- `SettingsRepository` ganhou `KEYBOARD_ORB_CONFIG_JSON`, `configOrbeTecladoFlow()`, `configOrbeTecladoOnce()` e `atualizarOrbeTeclado` (transação `dataStore.edit` independente do auto-save de `Settings` e sem abrir `SecurePrefs` nem `CredentialStore`).
