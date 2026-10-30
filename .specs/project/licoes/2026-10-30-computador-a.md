# 2026-10-30 — Portão do toolchain e interrupção do spike do Computador

Branch `equipe/computador-a`, base `c1480992366c8dce6f0fa021ae833c0e9db0d4f8` da `origin/main`.
Neste registro inicial não há dependência nova de terminal, fork nem servidor. O diagnóstico e a retomada
autorizada da CMP-T3 serão registrados separadamente.

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
