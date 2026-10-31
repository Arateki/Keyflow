# 2026-10-31 — Contraste com alfa na notificação

`notification.ink` aceita `#AARRGGBB`, mas `Contraste.razao` trabalha com RGB. Comparar a tinta transparente
como se fosse opaca deixava passar `#001B1B1B` sobre `#FFF1E7C9`: tempo miúdo invisível e botão com a cor
original do vetor (contrastes medidos na revisão: 1,00:1 e 1,04:1).

`Contraste.legivel` agora compõe os canais da tinta sobre o fundo opaco da linha antes de comparar a razão.
Devolve a composição **opaca**, quando chega a 3:1; caso contrário, branco ou quase preto opaco, conforme o
maior contraste. Botões e tempo miúdo recebem a mesma cor; a tinta opaca das skins nativas preserva os canais.

As duas contraprovas transparentes da revisão viraram casos permanentes de `ContrasteTest`, junto de
semitransparência legível, baixa opacidade e 12.544 combinações de alfa/cor/fundo. `NotificacaoDaOrbeTest`
mede ambos os botões desenhados e o tempo miúdo com cinco tintas transparentes/semitransparentes nos dois temas.

Validação JVM: `ContrasteTest` passou 8 de 8; `testDebugUnitTest` e `lintDebug` verdes. Os quatro goldens `notification-*` têm hashes idênticos aos anteriores.
`assembleDebugAndroidTest` passou. A execução Android foi interrompida antes de começar: o emulador já mostrava “System UI isn't responding”.
Nenhuma intervenção no SystemUI/keyguard ou desligamento; regressões Android ficam pendentes de execução.
