package com.keyflow.ui

/**
 * O mesmo probe num processo só dele (`:probe`, no AndroidManifest do debug) — como um app de verdade, com o teclado e a
 * tela onde se digita em processos diferentes. O [ImeProbeActivity] roda no processo do próprio KeyFlow, e é lá que a
 * esteira de instabilidade (2026-10-10) viu a camada da animação de entrada do teclado ficar fora do lugar (126 px abaixo,
 * ou no topo da tela) no emulador lento. Este probe diz se isso é efeito de dividir o processo com o teclado.
 * Escolhido pelo argumento `-e probeProcess remoto` na KeyFlowKeyboardCanvasGridTest.
 */
class ImeProbeRemoteActivity : ImeProbeActivity()
