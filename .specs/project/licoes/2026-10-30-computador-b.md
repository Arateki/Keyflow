# 2026-10-30 — Computador B: hosts, known_hosts e chaves (CMP-T5..T8)

Branch `equipe/computador-b` (base: `equipe/computador-a`, depois `origin/main` com o #87).

## O que entrou
- **T5** `computador/{Modelo,ValidadorHost,CodecHosts,RegraAgente,MigracaoHostUnico}.kt`: codec que nunca lança (fuzz de 10 mil mutações),
  `RegraAgente` também aplicada ao decodificar (arquivo editado à mão não liga o agente), migração pura e determinística (ids fixos); um legado
  que o validador recusaria **não** é migrado nem apagado (senão o codec o descartaria e o dado se perderia).
- **T6** impressão `SHA256:` e arte iguais ao `ssh-keygen -lvf` (vetores), `known_hosts` no formato OpenSSH (porta 22 sem colchetes),
  linhas que o app não entende voltam byte a byte. Mesmo tipo + chave diferente = `Mudou`; tipo ainda desconhecido = `Nova` com `outrosTipos`
  (a tela pode alertar). Rotação só acrescenta tipos novos e só se a chave autenticada já era conhecida.
- **T7** importador sem estado (`CharArray` zerados), pré-inspeção do cabeçalho para distinguir senha ausente/errada e recusar DSA antes de decifrar;
  RSA < 2048 recusada; material = semente Ed25519 ou OpenSSH sem senha. Públicas conferidas contra `ssh-keygen -y` com chaves falsas versionadas.
- **T8** `computador/app/{ProvedorDeChavesAndroid,EnvelopeDeChave,SondaEd25519,BiometriaAndroid,CofreDoApp}.kt`; `androidx.biometric:1.1.0`
  (Apache-2.0; +15 componentes Apache-2.0 no inventário, 141 no total).

## SPEC_DEVIATION
- `org.connectbot.sshlib` também é importado por `chaves/ImportadorDeChave.kt` e `chaves/AssinadorSoftware.kt` (o design previa só o `ConexaoCbssh`);
  a regra do `ArchitectureRulesTest` lista os três arquivos. `Assinador` usa `ChavePublicaSsh` própria, não o `AuthPublicKey` do cbssh.
- `Assinador`/provedor recebem o `ChaveSsh` (`assinador(chave)`), não só o id: os metadados vivem na T11.
- Tink entra como dependência direta (`tink-android:1.24.0`, a mesma variante do grafo) para o `Ed25519Sign`.
- O commit da T7 sozinho não compila (a dependência direta do Tink está no `build.gradle.kts`, commitado com a T8).
- **T2 (spike) não foi feita**: a T8 foi escrita com o veredito da sonda de produção, medido só no emulador API 34; a tabela por aparelho segue pendente.
- Protecao: `HARDWARE` = Keystore em TEE/StrongBox, `SOFTWARE_COFRE` = Keystore por software, `KEYSTORE_ENVELOPE` = envelope.

## Lições
- KDoc de Kotlin aninha comentários: `app/**` dentro de `/** … */` abre outro e quebra o arquivo inteiro.
- Biometria não dá para exercitar no emulador sem cadastro: `aplicarBiometriaNoKeystore=false` (só teste) deixa a lógica da janela
  testável; a escolha de parâmetros por API é função pura (`PlanoDeAutenticacao`). Cifrar com chave AES que exige autenticação também pede
  a janela aberta: criar chave em envelope com biometria pede o prompt antes.
- `pool compilar` sincroniza a árvore no momento em que o nó é obtido: editar arquivos durante a espera entra na execução.
