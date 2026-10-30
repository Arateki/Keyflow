# 2026-10-30 — Toolchain, JNI e bancada do Computador

Branch `equipe/computador-a`, base `c1480992366c8dce6f0fa021ae833c0e9db0d4f8` da `origin/main`.
As seções iniciais preservam o diagnóstico que motivou a CMP-T3. Os resultados da retomada autorizada
estão nas seções finais; as medições iniciais não representam o estado final da branch.

## CMP-T0 — Toolchain

Versões conferidas nos arquivos versionados: Kotlin/compilador Compose **2.4.21**, BOM **2026.09.00**
(UI/runtime/foundation **1.12.1**, Material3 **1.4.0**), Activity Compose **1.13.0**, coroutines **1.11.0**,
AGP **9.2.0**, Gradle **9.6.1**, JDK **17**, compileSdk **37**, targetSdk **36**, minSdk **24**.
O projeto upstream usar AGP 9.4.1 não implica que consumir seu AAR exija trocar o AGP do app;
a compilação do conjunto terminal + SSH no app continua não medida nesta entrega interrompida.

Evidência anterior: [PR #80](https://github.com/Arateki/Keyflow/pull/80), integrada em 30/10/2026,
sete jobs verdes, e [lição da frente de toolchain](2026-10-30-toolchain-pr.md): 2.495 unitários e
272/272 goldens, sem elevar limiar. Os números da execução desta base serão registrados abaixo.

Validação da revisão exata da base: [Checks 41073156978](https://github.com/Arateki/Keyflow/actions/runs/41073156978),
sete jobs concluídos com sucesso, incluindo build, unitários, lint e os seis recortes de goldens.
As 272 capturas foram baixadas e conferidas contra os arquivos desta worktree: 246 PNGs bit-idênticos;
26 diferenças reais pequenas, já presentes nesse CI verde (principalmente na barra de rolagem), dentro do
limiar versionado, sem aprovação de golden nem mudança de tolerância. Não confundir gate verde com igualdade de bytes.

A validação local foi pedida pelo pool, sem destino fixo. `assembleDebug` passou. O gate local de telas
perdeu `emulator-5554` no ADB, na rota `orb-drag-pause` escura; `emulador estado` confirmou `failed parado`.
Não foi atribuída uma causa sem prova; `memory.events` do container não registrou OOM, o que não mede o host.
A conclusão da CMP-T0 usa o CI completo do mesmo SHA, não essa captura local incompleta.
**SPEC_DEVIATION:** evidência visual do CI exato substitui a tentativa local de `pool telas` que não terminou.
Logs e capturas em `.equipe/provas/computador-a/`; o gate local de unitários/lint continua registrado à parte.

## CMP-T1 — O que foi inspecionado

Artefatos do Maven Central: [termlib 0.3.14](https://repo.maven.apache.org/maven2/org/connectbot/termlib/0.3.14/)
e [sshlib 0.6.6](https://repo.maven.apache.org/maven2/org/connectbot/sshlib/sshlib/0.6.6/).
O metadata do termlib ainda aponta 0.3.14 como release mais recente.
Tag `0.3.14` do termlib: **`0bd42f06e3ae88e5fc9e2f505a77b443cea26865`**.
Hashes SHA-256 das entradas usadas na inspeção (manifesto completo também na pasta local):

| Arquivo | SHA-256 |
| --- | --- |
| `termlib-0.3.14.aar` | `5eb7af40ce270c24239960643a8b565b4a03abb2d08f2a8c0677a6ba46bb62f2` |
| `termlib-0.3.14-sources.jar` | `359c274e267f51df01f15c56ddac5ba9cb12eb845f6fa860ebf389135032a44d` |
| `sshlib-0.6.6-sources.jar` | `60e60cbfb018a3ddb70533bebef92f5fec03465481856da1a10da3d6409579ca` |


| Item da T1 | Resultado e limite da evidência |
| --- | --- |
| 1 — Compilação integrada; delta de APK/métodos/RAM em 80×24, 5.000/20.000 linhas | **Não medido**; só a base sem dependências novas foi compilada. Scrollback publicado não permite configurar esses dois tamanhos. |
| 2 — Nativo nas três ABIs | AAR contém arm64-v8a, armeabi-v7a e x86_64 (também x86). Os três PT_LOAD de cada `.so` têm `p_align=16384`, medidos por `load_alignments` de `scripts/check-16kb-alignment.py`. **Alinhamento ZIP do APK integrado não medido**: nenhum APK com termlib foi produzido. |
| 3 — Licenças | POMs das peças listadas abaixo inspecionados; **grafo completo resolvido, NOTICE e teste de transitivas não implementados**. Nenhuma dependência nova entrou no app. |
| 4 — kitty/iTerm2/Herdr e `t=f/t=t/t=s` | **Não medido em execução**. Presença de código de protocolo não foi contada como imagem funcionando. |
| 5 — Bytes de teclado × respostas DA1/DSR/kitty/cores/título | **Não medido em execução**. Inspeção do fonte prova callback adiado por Handler para saída nativa: uma flag só dentro de `writeInput` não identifica sua origem no callback. |
| 6 — KeyFlow, Gboard, teclado físico e ditado da orbe | **Não medido**; não foi instalada uma tela de spike e nenhum aparelho físico foi acessado. |
| 6b — Mouse e scrollback | **Inspeção estática de fonte e binário concluída**: zero APIs/eventos públicos de mouse, zero entradas JNI de mouse, limite privado de 1.000 linhas, sem parâmetro/setter público. Exige a CMP-T3 para expor o estado/eventos do libvterm; limite de scrollback é alteração Kotlin, isoladamente não exige C++. |
| 7 — Sixel → PNG → kitty | **Não medido**. Permanece no transcodificador do app, conforme D12; não motivou a decisão de fork. |

## Mouse, scrollback e a condição de parada

No [TerminalEmulator.kt da tag](https://github.com/connectbot/termlib/blob/0bd42f06e3ae88e5fc9e2f505a77b443cea26865/lib/src/main/java/org/connectbot/terminal/TerminalEmulator.kt),
`create` não recebe limite de scrollback; `maxScrollbackLines = 1000` é privado e `pushScrollbackLine`
remove a primeira linha quando passa dele. Guardar texto fora do emulador não amplia o histórico que o
componente desenha, incluindo células e imagens. Não foi proposto acesso por reflexão nem parser paralelo.

O `javap -p` do `classes.jar` do AAR confirma a interface e as 12 funções nativas declaradas;
`readelf --dyn-syms --wide` nas quatro ABIs confirma 12 exports JNI por ABI e nenhum de mouse.
[TerminalNative.kt](https://github.com/connectbot/termlib/blob/0bd42f06e3ae88e5fc9e2f505a77b443cea26865/lib/src/main/java/org/connectbot/terminal/TerminalNative.kt)
e [Terminal.cpp](https://github.com/connectbot/termlib/blob/0bd42f06e3ae88e5fc9e2f505a77b443cea26865/lib/src/main/cpp/Terminal.cpp)
não oferecem a ponte para `vterm_mouse_move`/`vterm_mouse_button`; `setTermProp` trata cursor e tela
alternativa, mas não publica o modo de mouse. Escrever sequências SGR manualmente não recupera esse estado
pela API existente. A integração pelos contratos públicos previstos no design precisa da ponte da CMP-T3.

**Diagnóstico inicial:** CMP-T1 atingiu a condição de parada, CMP-T3 necessária e não iniciada, CMP-T4 não iniciada.
Isso não declara todos os critérios da T1 cumpridos. O próximo executor deve concluir as medições dinâmicas
com a base escolhida e só depois marcar CMP-02/CMP-03 como verificados.
**SPEC_DEVIATION:** o spike ficou em inspeção estática na branch designada pela tarefa, sem
`TerminalSpikeActivity` nem branch descartável própria, por atingir a condição expressa de parada antes da implementação.

## Callbacks: a flag proposta não serve como prova de origem

`TerminalEmulatorImpl.onKeyboardInput` chama `postCallback`, que usa `handler.postAtTime`;
`writeInput` apenas chama a ponte nativa e retorna. `batchOutput` também posta os bytes coletados.
Assim, a flag `dentroDoWriteInput` já pode estar desligada quando o callback roda. O protocolo de imagens
possui outros caminhos diretos; não se pode assumir nem que todos os callbacks são síncronos nem que todos
são adiados. É preciso medir cada origem e desenhar a separação antes de fechar o filtro da CMP-T13.

## Licenças verificadas, sem incorporação de dependências

| Peça e versão inspecionada | Licença declarada / fonte |
| --- | --- |
| termlib 0.3.14 | Apache-2.0, POM e LICENSE da tag |
| libvterm incorporado à tag do termlib | MIT, `lib/src/main/cpp/libvterm/LICENSE` da mesma tag |
| sshlib e protocol 0.6.6 | Apache-2.0, respectivos POMs |
| kstatemachine-jvm 0.38.1 | **Boost Software License 1.0**, POM (não Apache-2.0) |
| ktor-network-jvm 3.6.0 | Apache-2.0, POM |
| Tink 1.24.0 | Apache-2.0, POM |
| kyber-jvm 2.1.0 e keccak-jvm 2.5.1 | Apache-2.0, respectivos POMs |
| jbcrypt 1.0.2 | **ISC**, POM |
| slf4j-api 2.0.20 | MIT, [licença oficial](https://www.slf4j.org/license.html); POM do módulo não declara licença própria |

Esse recorte não encontrou GPL/AGPL/LGPL nem licença não comercial; **não substitui auditoria de todas as
transitivas do grafo Gradle resolvido**. AndroidX Biometric e Apache MINA SSHD não foram adicionados nem
fixados. Não há motivo para acrescentar NOTICE de uma biblioteca que não foi incorporada.

## Evidência e reprodução

Diretório local, fora do git: `.equipe/provas/computador-a/`. Contém AAR, fontes publicados, POMs,
`Terminal{Emulator,Native,Snapshot}.javap.txt`, referência da tag, fontes JNI apenas para inspeção,
`termlib-nativo.json`, `licencas-poms.json` e hashes. Para reproduzir, baixe o AAR e o sources.jar dos links
Maven acima; extraia `classes.jar`, execute `javap -p` nas duas classes e extraia `jni/<abi>/libjni_cb_term.so`
para `readelf --dyn-syms --wide`. Importe `load_alignments` do verificador existente e examine cada ELF;
rodar o script diretamente no AAR procura `lib/`, portanto **não valida as entradas `jni/` do AAR**.
Os links imutáveis e os hashes nesta lição permitem revisão mesmo sem a pasta local de evidências.

## Retomada autorizada em 30/10

O coordenador confirmou a autorização do Yan para a CMP-T3 e pediu prosseguir, depois do PR deste diagnóstico,
na ordem CMP-T3 → retomada da CMP-T1 → CMP-T4, na mesma branch e no mesmo relatório. O resultado estático
acima registra o ponto de partida; não encerra o trabalho autorizado.

## CMP-T3 — Implementação em andamento

A base passou também no pool: `assembleDebug testDebugUnitTest :app:lintDebug assembleDebugAndroidTest`,
22min39s, **2.699/2.699 testes JVM**, 260 classes, zero falhas/erros/ignorados.
O fork parte do SHA upstream acima, preservando os arquivos originais e um manifesto de hashes anteriores
aos patches. A auditoria identificou também o wcwidth permissivo de Markus Kuhn dentro do libvterm,
além dos runtimes LLVM com suas exceções e avisos históricos preservados.

A publicação nativa precisa de um commit de bootstrap do workflow para o CI produzir o binário; o hash
real desse artefato será fixado num commit de integração posterior. **SPEC_DEVIATION:** dois commits
para CMP-T3, pois o SHA do binário do CI não existe antes de enviar o workflow, e não se reescreve a branch
publicada com `--force`. A task permanece em andamento até os gates do módulo e do artefato passarem.

## CMP-T3 — Resultado dos gates requeridos

- Release: [termlib-jni-0.3.14-keyflow.1](https://github.com/Arateki/Keyflow/releases/tag/termlib-jni-0.3.14-keyflow.1).
  ZIP SHA-256 `2f0c6f11215f4bd2e86c5f2f3f7a763e2824d78898432b2cf5817c22f7142b2e`; hashes individuais e
  hash do fonte nativo em `third_party/termlib/native-manifest.json`.
- Workflows `41111156115` e `41111164906` verdes nas três ABIs; no segundo, o download de um artefato
  falhou após cinco tentativas e passou ao repetir somente o job de empacotamento, sem alterar o código.
- Pool: `assembleDebug` e `assembleDebugAndroidTest` passaram sem NDK, unitários **2.705/2.705**,
  zero falhas/erros/ignorados, 261 classes; **6/6** em `TermlibNativoTest`. ABI removida, bit alterado,
  pacote reempacotado e cabeçalhos GNU GPL/LGPL/AGPL inseridos são recusados pelos testes.
- APK: `check-16kb-alignment.py` verde (ELF e offsets ZIP) e `verificar-termlib-apk.py` com hashes
  idênticos ao CI em arm64-v8a, armeabi-v7a e x86_64. `keepDebugSymbols` preserva os mesmos bytes
  também quando o worker tem NDK. O Checks ganhou essa verificação, mantendo os sete jobs existentes.
- Auditoria efetiva do NDK: LLVM base `386af4a5c64ab75eaee2448dc38f2e34a40bfed0`; os quatro textos
  de licença dos runtimes preservados são bit-idênticos aos dessa revisão. Detalhes e fontes em `UPSTREAM.md`.
- O AGP 9.2 gerou um accessor legado de `AndroidLibrarySourceSet` incompatível com a implementação;
  configurar `com.android.build.api.dsl.LibraryExtension` evita o cast, confirmado no JAR real do AGP.
- A primeira catraca do fonte encontrou ordens diferentes (caminho inteiro versus componentes). A ordem
  canônica por componentes foi alinhada ao empacotador, sem mudar o hash nem o binário aprovado.
- O lint adicional do módulo estava habilitando regras não triadas. Foi alinhado ao `NewApi` do app;
  a análise anterior foi interrompida depois dos gates requeridos; a repetição passou em 12min30s
  (`t3-empacotamento-lint.log`), incluindo `:app:lintDebug`.


## CMP-T1 — Bancada integrada após o fork

Versões efetivamente resolvidas: termlib 0.3.14 com os patches da CMP-T3; sshlib e protocol **0.6.6**.
O inventário reproduzível fica em `app/licenses/computador-dependencias.json` e a tabela legível em
`app/licenses/Computador-transitivas.md`: **126 componentes**, hashes dos artefatos e dos POMs, fontes
primárias e licenças Apache-2.0, MIT, ISC e BSL-1.0. Preservados também os avisos incorporados:
Bouncy Castle (MIT, dentro do cbssh) e **539 classes Protobuf sombreadas** (BSD-3-Clause, dentro do Tink Android).
`LicencasTransitivasTest` compara o grafo Gradle efetivo ao inventário, recusa componente novo/hash diferente
e testa a rejeição explícita de GPL, LGPL, AGPL e CC-BY-NC. O auditor preserva LICENSE/NOTICE dos JARs/AARs.

A primeira compilação integrada encontrou classes duplicadas: AndroidX Security trazia `tink-android:1.8.0`,
enquanto o cbssh trazia `tink:1.24.0`. Substituir **tink JVM por tink-android:1.24.0** unifica as classes e mantém
as integrações Android do cofre. Isso exige repetir `SecureCredentialStoreTest` e `SecurePrefsRecoveryTest`,
além dos testes novos. Não foi resolvido excluindo a criptografia do cofre.

Bancada somente debug, sem `FLAG_SECURE`, com `TerminalEmulatorFactory.create`, terminal 80×24, fontes 6 sp,
entrada habilitada e limites de imagens explícitos. As gravações são saídas de geradores reais sobre uma
imagem própria; README, comandos e hashes em `app/src/test/resources/computador/gravacoes/`.
**SPEC_DEVIATION:** a bancada fica nesta branch, como pediu o coordenador, em vez de outra branch descartável.
**SPEC_DEVIATION:** Herdr 0.9.5 emitiu `a=t` + `a=p`, sem placeholders Unicode nesta captura. O caso Unicode
é uma gravação separada de `kitten icat --unicode-placeholder`, sem atribuí-la ao Herdr.

As validações físicas com KeyFlow, Gboard, teclado e ditado da orbe são **não medidas**: não houve acesso ao A32.
O teste automatizado exercita o InputConnection real, inclusive commit UTF-8, composição e o caminho duplo
sendKeyEvent/dispatchKeyEvent, mas não substitui esses testes manuais. O transcodificador sixel→PNG→kitty
é **não medido** e continua na CMP-T25/T26; aceitar o DCS bruto sem crash não prova imagem sixel.
AndroidX Biometric não foi acrescentado: escolha e comportamento por aparelho seguem na CMP-T2/T8.


Gates integrados da retomada: `assembleDebug testDebugUnitTest :app:lintDebug assembleDebugAndroidTest`,
**8min31s**, **2.709/2.709 JVM**, 262 classes, zero falhas/erros/ignorados; 4/4 catracas de licenças.
Os sete jobs do Checks do commit anterior (CMP-T3), execução **41116973031**, também ficaram verdes.
O APK integrado mantém ELF/ZIP de 16 KB e os três hashes nativos aprovados.

| Métrica debug | Base c1480992 (APK do Checks 41073156978) | Bancada integrada | Delta |
| --- | ---: | ---: | ---: |
| Bytes de APK | 158.665.852 | 167.372.244 | +8.706.392 (+5,49%) |
| Referências de métodos DEX, soma de method_ids_size | 290.384 | 327.615 | +37.231 (+12,82%) |

A contagem DEX inclui referências repetidas entre arquivos, não métodos únicos. Comparação debug, sem R8;
a base é o artefato do CI do SHA exato e a bancada foi compilada pelo pool no servidor, com a configuração
local de debug. O delta inclui termlib/JNI, cbssh, atualização do Tink e Activity de bancada; não é projeção
do tamanho do release. SHA-256 da bancada: `acae7306361977becc66727193eea3dcd38390d725605ca77b4772cfc9dc2d58`.


Primeiro lote instrumentado: **18/20** passaram, incluindo **10/10 do cofre** com Tink Android 1.24.0.
A imagem kitty minúscula ficou sob o cursor sólido; a captura preservada mostra o cursor cobrindo os
quadrantes esquerdos (vermelho/azul). A repetição oculta o cursor com CSI `?25l`, sem mudar a gravação real
nem reduzir a exigência de quatro cores. O teste de entrada encontrou **`aa`** quando o mesmo evento
foi entregue por InputConnection **e** View. Isso é um diagnóstico do upstream, não aprovação da integração:
o teste de bancada passa a exigir exatamente o resultado observado, e a CMP-T13 precisa deduplicar/escolher
uma origem. KeyFlow/Gboard/orbe reais continuam não medidos. Nenhuma correção de produção foi simulada.


### Tabela final dos sete itens da CMP-T1

Lote final: **20/20 instrumentados** no emulador API 34 (10 da bancada + 10 do cofre), **2min14s**;
compilação pelo pool antes da instalação. Provas: `t1-medidas-final/medidas.json`, cinco PNGs e logs em
`.equipe/provas/computador-a/`. Kitty e Herdr também inspecionados visualmente. Nenhum golden alterado.

| Item | Resultado medido e limite |
| --- | --- |
| 1 — Integração, APK/DEX/RAM | Build/JVM/lint verdes; deltas na tabela anterior. PSS final a 5k/20k linhas: **195.063/218.355 KiB**; heap máximo 201.326.592 bytes. Deltas PSS **−1.255/+23.228 KiB**, heap **−1.270.984/+23.669.104 bytes**. Processo compartilhado com testes anteriores e GC: o delta negativo de 5k é ruído de coleta, **não** ganho de memória. Primeiro lote: +881/+25.240 KiB. Não é custo isolado do objeto nem benchmark estatístico. |
| 2 — Nativo | arm64-v8a, armeabi-v7a e x86_64 presentes, ELF/ZIP 16 KB e hashes do JNI do CI iguais. |
| 3 — Licenças | 126 componentes resolvidos, POMs/hashes e avisos incorporados; Apache-2.0/MIT/ISC/BSL-1.0, Protobuf BSD-3-Clause. Nenhuma licença proibida encontrada; quatro testes de auditoria verdes. Biometric ainda não escolhido. |
| 4 — Imagens | Quatro cores reais verificadas em kitty, iTerm2, placeholders Unicode do kitten e Herdr. Contagens mínimas por cor: **16/324/72/143 pixels**, respectivamente. `t=f/t=t/t=s` respondem **ENOTSUP:only stream transport is supported**. Herdr não usou placeholders nesta captura. |
| 5 — Callbacks | DA1 `1b5b3f313b3263`, DSR `1b5b306e`, CPR `1b5b313b3152`; kitty `ESC_Gi=50;OK ST`. Todos chegaram com `dentro_write=false`; a flag é insuficiente. Consultas OSC 10/11 e título não geraram bytes. |
| 6 — Entrada | `commitText` enviou **ação e voz** em UTF-8, sem CR/LF. Mesmo KeyEvent pelos dois caminhos produziu **aa**, não deduplicado. `setComposingText` + `finishComposingText` não enviaram bytes. EditorInfo.inputType=**524432** (classe NULL). Esses riscos ficam em CMP-T13/T14; IMEs físicos e orbe **não medidos**. |
| 6b — Mouse/histórico | Modo 1000/1006 publica 1; clique linha 2/coluna 4 gera `ESC[<0;5;3M` / `ESC[<0;5;3m`; desligar publica 0 e não envia clique. Retidas exatamente **5.000/20.000 linhas** após transbordo, sem reflexão. Fork necessário e agora funcional. |
| 7 — Sixel | Gravação real de img2sixel passa sem crash. **Qualidade do transcodificador não medida**: implementação futura CMP-T25/T26, sem alterar DA1/nativo nesta task. |

**SPEC_DEVIATION:** os testes da bancada exigem o comportamento observado de duplicação; não são aceitação
da futura entrada de produção. O gate verde confirma a medição reproduzível, e os defeitos permanecem explícitos.
