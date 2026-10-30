# 2026-10-30 — Fluxos, Fase A: modelo, codec, restaurar, roteador e persistência (equipe/fluxos-fase-a)

FLX-T1..T5, tudo JVM puro exceto a persistência (T4). Nenhuma tela. O pacote `com.keyflow.fluxos` (fora de `fluxos/app`) é conferido pelo `ArchitectureRulesTest`: sem `android.*`, Compose, downloader nem cofre.

## O que entrou

- Modelo (`Modelo.kt`), `ValidadorFluxo` com o motivo por etapa, `LugarDaOrbe` em `com.keyflow.orbe` (compartilhado com a notificação).
- `CodecFluxos` (`flows_json`, `flow_triggers_json`, esquema `v:1`), `PadroesFluxo` + `RestauradorFluxos`, `RoteadorGatilhos` + `PoliticaGatilhos`.
- No `SettingsRepository`: `configFluxosOnce/Flow`, `atualizarFluxos`, `atualizarFluxosEBarras`.
- Ordem real: T1, T2, T3, **T5, T4**. A T4 precisa do `PoliticaGatilhos.limpar`, que é da T5.

## Decisões que a spec não cobria

- **Restaurar com 20 fluxos**: o padrão apagado não volta se a lista já está no limite. Recriar além dele faria o codec cortar, na leitura seguinte, fluxo da pessoa. A `PreviaRestaurar.semEspaco` lista o que ficou de fora; a T17 mostra isso na confirmação.
- **Aviso de substituição** (D17): `avisoDeSubstituicao` devolve `String?` e nomeia sempre a superfície ("neste gesto da orbe flutuante", "… do teclado", "… da notificação", "neste botão do teclado"), inclusive quando o ocupante é outro fluxo ("Fluxo: X"). A spec trazia "neste atalho" para o caso do fluxo; ficou a frase da superfície. Gravar não tem texto próprio.
- **Gatilho de barra órfão**: `PoliticaGatilhos.limpar(config, botoesDaBarra: Set<String>?)`; `null` deixa os gatilhos de barra como estão. O repositório passa `null` até a T16 criar o `RunFlow` (ver o registro da T16 no `tasks.md`).
- O sistema do Perguntar de "Ouvir e responder" fica em português: é dado do fluxo, comparado pelo Restaurar, não texto de tela.

## Lições

- **`optString` diverge entre o `org.json` do Android e o do Maven**: um `null` do JSON vira "null" no Android e "" na JVM dos testes. O codec só aceita campo de texto que seja de fato `String` (`opt(...) as? String`). Quem escrever outro codec com `org.json` faz o mesmo, ou o teste de unidade passa e o aparelho lê "null".
- **`pool compilar` no servidor compila a worktree no lugar**: editar arquivo enquanto ele roda vaza para a compilação (o teste novo compilou sem o `main` novo: "Unresolved reference"). No Deck a árvore é copiada antes. Não mexa na worktree até o pool terminar.
- **O `private companion object` do `SettingsRepository` esconde as chaves**: teste instrumentado define a própria `stringPreferencesKey("…")` e lê o DataStore por `rawPreferencesOnce`/`rawPreferencesFlow` (este último, sem cofre, é de propósito para provar que duas chaves saíram numa só transação).
