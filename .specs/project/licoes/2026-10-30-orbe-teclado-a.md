# 2026-10-30 — Orbe do teclado, Fase A: lugares, geometria, codec e resolvedor (equipe/orbe-teclado-a)

KBO-T1..T3 da feature `keyboard-orb`, em `com.keyflow.orbe` (JVM puro, conferido pelo `ArchitectureRulesTest`: sem `android.*`, `androidx.*` nem cofre).

## KBO-T1 — Lugares, geometria e hit-test

- `Interacoes.kt` (compartilhado com Fluxos e Notificação): acrescentados `Interacao` (as sete interações da orbe) e `SuporteDeInteracoes` (`TECLADO` e `FLUTUANTE` suportam as sete; `NOTIFICACAO` suporta `TOQUE_1..3`), mantendo o enum `LugarDaOrbe` existente sem duplicação.
- `GeometriaDaOrbe` e `HitDaOrbe`: círculo ocioso inscrito no miolo do encaixe (disjunto da faixa de 36 dp); círculo sobressalente com fundo no fundo do encaixe e `diâmetro = min(largura, altura + faixa)`, disjunto das teclas abaixo e dos botões vizinhos na mesma barra; `HitDaOrbe` usa a equação do disco `(x - cx)² + (y - cy)² <= r²` (rejeitando `(1, 1)` e os cantos do retângulo delimitador fora do disco) e acompanha a rolagem horizontal da barra.

## KBO-T2 — Codec e repositório

- `CodecOrbeTeclado` (`"v": 1`) codifica e decodifica `ConfigOrbeTeclado` (`copy`, `skin`, `mode` = `toggle`|`ptt`, `taps` = `1`|`2`|`3`) usando `opt(chave) as? String` em vez de `optString` (evitando `"null"` literal do `org.json` Android quando uma chave vale `JSONObject.NULL`).
- Ações `flow:<id>` (`RodarFluxo`) e desconhecidas (`Desconhecida`) são preservadas byte a byte ao regravar o JSON; ação desconhecida no 1 toque nunca é reescrita para `record` (`GravarEnviar`).
- `SettingsRepository` ganhou `KEYBOARD_ORB_CONFIG_JSON`, `configOrbeTecladoFlow()`, `configOrbeTecladoOnce()` e `atualizarOrbeTeclado` (transação `dataStore.edit` independente do auto-save de `Settings` e sem abrir `SecurePrefs` nem `CredentialStore`).

## KBO-T3 — Rajada, classificador e resolvedor

- `RajadaDeToques` e `ClassificadorDeGesto` recebem relógio injetável (`() -> Long`) e aplicam os limiares da KBO-13 (segurar = `janela + 150 ms`, início de arrasto = `10 px`, ativação de zona = `0,7 × diâmetroAtualPx`, sentido lateral para o centro da tela = sinal de `centroDaTelaX - centroDaOrbeX`).
- `ResolvedorDaOrbeDoTeclado` deriva `efetiva(config, settings)` em memória sem escrever no DataStore (com copiar ligado no Alternar, 1 toque é `GravarEnviar` ignorando `shortcut1Tap`; no segurar-para-falar, 1 toque mapeia `shortcut1Tap` e segurar grava), aceita zero `GravarEnviar` nos dois modos, emite `AvisoDeSubstituicao` ao trocar ação já atribuída em toque/gesto/botão sem recolocar gravar, encerra a Visão ativa apenas em `GravarEnviar` (ignorando as demais interações) e expõe `GanchoDeFluxo` nulo por padrão (consultado só em 2 e 3 toques, lendo `FLUTUANTE` quando copia e `TECLADO` quando própria, sem importar `com.keyflow.fluxos`).
