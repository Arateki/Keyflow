# 2026-10-31 — Prontidão do serviço nos testes de notificação

O `setUp` de `OverlayServiceNotificacaoTest` esperava apenas o `KeyFlowController.buttonState == RECORDING`.
Esse valor é global e sobrevive ao serviço anterior, enquanto `startForegroundService` entrega a criação de
forma assíncrona. A preparação podia terminar antes da nova instância. O construtor do coordenador então
publica legitimamente `IDLE_NO_FIELD`, que é o valor encontrado na antiga asserção da linha 154.

A regressão de `OverlayCoordinatorTest` usa o coordenador de produção e um gravador falso. Medição na JVM:
`RECORDING` herdado e zero inícios → construção publica `IDLE_NO_FIELD`, ainda zero inícios → toque novo
publica `RECORDING` e um início. Passaram a contraprova e os demais casos: 36 de 36 no coordenador;
o gate completo passou 2.861 de 2.861 unitários e `lintDebug`. Esse mecanismo independe do SystemUI.

A preparação agora espera a destruição da instância anterior e a remoção do registro do serviço, inicia
na main e só aceita `RECORDING` com a nova instância publicada. A limpeza também espera a destruição antes
de restaurar consentimento e configuração. A contraprova Android mantém a main ocupada durante o pedido
de início, comprovando que a condição antiga aceitaria o estado herdado com a instância ainda nula.

Compilação dos instrumentados: `assembleDebugAndroidTest` verde. Execução: zero casos, porque a inspeção anterior ao
pedido já encontrou “System UI isn't responding”. Não houve intervenção no SystemUI/keyguard nem
reinício ou desligamento do emulador. A corrida do teste foi demonstrada na JVM; a ocorrência Android
histórica não foi isolada novamente e a validação integrada da correção fica pendente por esse ANR.
