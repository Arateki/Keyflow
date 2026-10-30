# 2026-10-30 — Fluxos: atalhos da orbe flutuante (equipe/fluxos-fase-c2, FLX-T15)

O teste inicial dos dois toques ficou vermelho (`_ezypxt_`): não executava o
fluxo; o outro caso da mesma rodada confirmou o botão real do IME.

`handleOrbShortcut` consulta o roteador puro com `FLUTUANTE`, configuração
atual e política do lugar. `Rodar` e `Incompleto` retornam antes do caminho das
ações antigas. A ação devolvida por `AcaoDoSettings` usa o mesmo código de
expandir teclado, trocar IME ou Visão; não se resolve o overflow uma segunda vez.
Não se escreve Settings nem gatilhos durante o disparo.

O serviço passou 14/14 (nove da T14 e cinco da T15), e `OrbButtonViewTest` 14/14
(`pe8n0zjx`). Os cinco novos casos provam 2/3 toques, a devolução da ação após
desligar o gatilho com Settings intactas, gesto sem gatilho, overflow 4→3 e fluxo
incompleto sem executar a ação sombreada. Unitários afetados: 52/52
(`SettingsShortcutTest`, `RoteadorGatilhosTest`, arquitetura e traduções).

Mutação: depois de rodar o fluxo, deixei executar também a ação do Settings.
O contador do SharedFlow de expansão esperava 0 e recebeu 1 (`iej0rnvd`).
A fonte foi restaurada em `finally`; a confirmação reinstala o APK pelo pool.

A regressão final passou **48/48** (`nkk9rvo4`): as duas classes da T15,
Visão/cancelamento do serviço, barras do IME e SetupActivity. O teste do glifo confere também sua descrição
acessível e seu desaparecimento após cancelar; o teste do banner espera a
Activity de configuração ser destruída antes de restaurar Settings.
