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

