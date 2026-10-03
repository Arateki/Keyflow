package com.keyflow.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** O histórico neutro: o conserto da invariante chamada↔resultado (AGT-04) e a poda por tamanho (AGT-01, D10). */
class AgentHistoryTest {

    private fun assistant(text: String = "", vararg ids: String) = AgentMessage.Assistant(text, ids.map { AgentToolCall(it, "t", "{}") })

    private fun result(id: String, content: String = "r$id", error: Boolean = false) = AgentMessage.ToolResult(id, content, error)

    // --- repair ---

    @Test
    fun `a well-formed history is left alone`() {
        val history = listOf(user("a"), assistant("ok", "1", "2"), result("1"), result("2"), assistant("fim"), user("b"))
        val repaired = AgentHistory.repair(history)
        assertThat(repaired.messages).isEqualTo(history)
        assertThat(repaired.added).isEmpty()
    }

    @Test
    fun `a call with no result gets an error result right after the others`() {
        val history = listOf(user("a"), assistant("", "1", "2", "3"), result("2"), user("b"))
        val repaired = AgentHistory.repair(history)
        assertThat(repaired.messages.map { (it as? AgentMessage.ToolResult)?.callId ?: it::class.simpleName })
            .containsExactly("User", "Assistant", "2", "1", "3", "User").inOrder()
        assertThat(repaired.added.map { it.callId }).containsExactly("1", "3").inOrder()
        assertThat(repaired.added.all { it.isError && it.content == AgentHistory.INTERRUPTED }).isTrue()
    }

    @Test
    fun `a turn cut right after the call is closed too`() {
        val repaired = AgentHistory.repair(listOf(user("a"), assistant("vou", "1")))
        assertThat(repaired.messages.last()).isEqualTo(AgentMessage.ToolResult("1", AgentHistory.INTERRUPTED, true))
    }

    @Test
    fun `orphan and duplicate results are dropped`() {
        val history = listOf(result("x"), user("a"), assistant("", "1"), result("1"), result("1", "dup"), result("zzz"), user("b"))
        val repaired = AgentHistory.repair(history)
        assertThat(repaired.messages).containsExactly(user("a"), assistant("", "1"), result("1"), user("b")).inOrder()
        assertThat(repaired.added).isEmpty()
    }

    @Test
    fun `repairing twice changes nothing more`() {
        val once = AgentHistory.repair(listOf(user("a"), assistant("", "1", "2"), user("b")))
        val twice = AgentHistory.repair(once.messages)
        assertThat(twice.messages).isEqualTo(once.messages)
        assertThat(twice.added).isEmpty()
    }

    // --- prune ---

    @Test
    fun `a small history is not pruned`() {
        val history = listOf(user("a"), assistant("b"), user("c"))
        assertThat(AgentHistory.prune(history, 10_000)).isSameInstanceAs(history)
    }

    @Test
    fun `big old tool results are summarised before any turn is dropped`() {
        val big = "z".repeat(5_000)
        val history = listOf(
            user("1"), assistant("", "a"), result("a", big), assistant("fim 1"),
            user("2"), assistant("", "b"), result("b", big), assistant("fim 2"),
        )
        val pruned = AgentHistory.prune(history, 6_000)
        val results = pruned.filterIsInstance<AgentMessage.ToolResult>()
        assertThat(results[0].content).contains("resultado antigo resumido")
        assertThat(results[0].content.length).isLessThan(600)
        assertThat(results[1].content).isEqualTo(big) // a volta atual não é tocada
        assertThat(pruned.size).isEqualTo(history.size)
    }

    @Test
    fun `old turns go whole, the start and the latest turns stay, and a note marks the gap`() {
        val history = (1..10).flatMap { listOf(user("p$it " + "x".repeat(1_000)), AgentMessage.Assistant("r$it " + "y".repeat(1_000))) }
        val pruned = AgentHistory.prune(history, 5_000)
        assertThat(pruned.first()).isEqualTo(history.first()) // o início
        assertThat(pruned[1]).isEqualTo(AgentMessage.User(AgentHistory.OMITTED_NOTE))
        assertThat(pruned.takeLast(2)).isEqualTo(history.takeLast(2)) // a volta mais recente
        assertThat(pruned.size).isLessThan(history.size)
        assertThat(pruned.sumOf { (it as? AgentMessage.User)?.text?.length ?: (it as AgentMessage.Assistant).text.length }).isLessThan(8_000)
    }

    @Test
    fun `pruning keeps every call with its result`() {
        val history = (1..8).flatMap { listOf(user("p$it " + "x".repeat(800)), assistant("", "c$it"), result("c$it", "y".repeat(800)), AgentMessage.Assistant("r$it")) }
        val pruned = AgentHistory.prune(history, 3_000)
        assertThat(pruned.size).isLessThan(history.size)
        assertThat(AgentHistory.repair(pruned).added).isEmpty() // nenhuma chamada ficou sem resultado
        assertThat(AgentHistory.repair(pruned).messages).hasSize(pruned.size) // e nenhum resultado ficou órfão
    }

    @Test
    fun `the current turn is never dropped, even when it alone is over the budget`() {
        val history = listOf(user("antiga " + "x".repeat(3_000)), assistant("ok"), user("atual " + "y".repeat(9_000)))
        val pruned = AgentHistory.prune(history, 1_000)
        assertThat(pruned.last()).isEqualTo(history.last())
    }

    @Test
    fun `pruning takes the image off the results of old turns, and keeps the one of the current turn`() {
        val png = ToolImage("image/png", byteArrayOf(1))
        val old = AgentMessage.ToolResult("1", "curto", false, png)
        val current = AgentMessage.ToolResult("2", "atual", false, png)
        val history = listOf(user("a"), assistant("", "1"), old, user("b" + "x".repeat(6_000)), assistant("", "2"), current)
        val pruned = AgentHistory.prune(history, 6_000)
        val results = pruned.filterIsInstance<AgentMessage.ToolResult>()
        assertThat(results.first { it.callId == "2" }.image).isEqualTo(png)
        // A volta antiga ou saiu inteira ou perdeu a imagem; nunca fica com ela.
        results.filter { it.callId == "1" }.forEach { assertThat(it.image).isNull() }
    }
}
