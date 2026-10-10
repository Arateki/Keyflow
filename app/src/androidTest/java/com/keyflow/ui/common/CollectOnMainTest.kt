package com.keyflow.ui.common

import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * No `createComposeRule` a composição coleta num dispatcher "unconfined": sem [collectAsStateOnMain], um valor vindo de outra
 * thread era escrito no estado NAQUELA thread, e o recomposer rodava o quadro ali (CalledFromWrongThreadException no
 * AnthropicKeyFlowTest, CI de 2026-10-10). Aqui se confere onde o estado coletado avisa a mudança: só na main thread.
 */
@RunWith(AndroidJUnit4::class)
class CollectOnMainTest {

    @get:Rule
    val rule = createComposeRule()

    private val offMain = CopyOnWriteArrayList<String>()
    private var observer: (() -> Unit)? = null

    @After
    fun tearDown() {
        observer?.invoke()
    }

    /** Anota a thread de cada aviso de mudança de [state] que não chega pela main thread. */
    private fun watch(state: State<*>) {
        val handle = Snapshot.registerApplyObserver { changed, _ ->
            if (state in changed && Looper.myLooper() != Looper.getMainLooper()) offMain += Thread.currentThread().name
        }
        observer = handle::dispose
    }

    @Test
    fun aStateFlowUpdatedFromAnotherThreadIsWrittenOnTheMainThread() {
        val source = MutableStateFlow(0)
        lateinit var state: State<Int>
        rule.setContent { state = source.collectAsStateOnMain() }
        rule.runOnIdle { watch(state) }

        for (value in 1..20) {
            thread(name = "fora-da-main-$value") { source.value = value }.join()
            rule.waitUntil(5_000) { state.value == value }
        }

        assertEquals("o estado mudou fora da main thread", emptyList<String>(), offMain.toList())
    }

    @Test
    fun aFlowOnIoIsWrittenOnTheMainThread() {
        val source = MutableStateFlow(0)
        lateinit var state: State<Int>
        rule.setContent { state = remember { source.map { it }.flowOn(Dispatchers.IO) }.collectAsStateOnMain(-1) }
        rule.runOnIdle { watch(state) }

        for (value in 1..20) {
            source.value = value
            rule.waitUntil(5_000) { state.value == value }
        }

        assertEquals("o estado mudou fora da main thread", emptyList<String>(), offMain.toList())
    }
}
