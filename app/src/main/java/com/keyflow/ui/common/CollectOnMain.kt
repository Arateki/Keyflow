package com.keyflow.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * `collectAsState` com a coleta presa à main thread — use estas no lugar dele em toda tela.
 *
 * No app não muda nada: a composição já coleta na main thread. A diferença é no `createComposeRule` (Compose 1.7), onde a
 * composição roda num dispatcher "unconfined": um valor emitido em outra thread — `flowOn(Dispatchers.IO)`, um StateFlow
 * atualizado por uma corrotina de fundo — retoma o coletor NAQUELA thread, o estado é escrito ali e o recomposer roda o quadro
 * ali também. No CI isso apareceu como CalledFromWrongThreadException, "performMeasureAndLayout called during measure layout"
 * e SlotTable corrompida derrubando o processo (2026-10-07..10). CollectOnMainTest confere.
 *
 * Quem escreve estado de tela depois de um `withContext(Dispatchers.IO)` (produceState, LaunchedEffect) tem o mesmo problema:
 * escrever dentro de `withContext(Dispatchers.Main.immediate)`.
 */

@Composable
fun <T> StateFlow<T>.collectAsStateOnMain(): State<T> = collectAsState(Dispatchers.Main.immediate)

@Composable
fun <T : R, R> Flow<T>.collectAsStateOnMain(initial: R): State<R> = collectAsState(initial, Dispatchers.Main.immediate)
