package com.keyflow.agent

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** O registro de ações em arquivo (TLS-05): sobrevive a reabrir, não guarda segredo, tem teto e não quebra com arquivo estragado. */
class FileActionLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "agent/actions.jsonl")

    private fun log(
        maxEntries: Int = FileActionLog.DEFAULT_MAX_ENTRIES,
        maxBytes: Long = FileActionLog.DEFAULT_MAX_BYTES,
        sanitize: (String) -> String = { it },
    ) = FileActionLog(file(), maxEntries, maxBytes, sanitize, io = Dispatchers.Unconfined)

    private fun entry(n: Int, undo: UndoCall? = null, summary: String = "instala a skin $n", detail: String = "ok $n") = ActionLogEntry(
        atMs = 1_000L * n,
        toolName = "skin_install",
        risk = ToolRisk.WRITE,
        summary = summary,
        outcome = ActionOutcome.OK,
        detail = detail,
        origin = ToolOrigin.CHAT,
        undo = undo,
    )

    @Test
    fun `entries survive reopening newest first and ids keep counting`() = runBlocking {
        val first = log()
        first.append(entry(1, undo = UndoCall("skin_remove", """{"id":"neon"}""")))
        first.append(entry(2))

        val reopened = log()
        val entries = reopened.snapshot()
        assertThat(entries.map { it.summary }).containsExactly("instala a skin 2", "instala a skin 1").inOrder()
        assertThat(entries.last().undo).isEqualTo(UndoCall("skin_remove", """{"id":"neon"}"""))
        assertThat(reopened.append(entry(3)).id).isEqualTo(3)
    }

    @Test
    fun `texts and undo arguments pass through the secret filter before reaching the file`() = runBlocking {
        val sentinel = "SENTINELA-COFRE-9f2e"
        val log = log(sanitize = { it.replace(sentinel, SecretFilter.MARKER) })
        log.append(entry(1, undo = UndoCall("skin_remove", """{"k":"$sentinel"}"""), summary = "usa $sentinel", detail = "deu $sentinel"))

        val onDisk = file().readText()
        assertThat(onDisk).doesNotContain(sentinel)
        assertThat(onDisk).contains(SecretFilter.MARKER)
    }

    @Test
    fun `an undo call too long to keep is dropped instead of cut`() = runBlocking {
        val log = log()
        val stored = log.append(entry(1, undo = UndoCall("skin_remove", "{\"x\":\"" + "a".repeat(5_000) + "\"}")))
        assertThat(stored.undo).isNull()
    }

    @Test
    fun `the file is rotated when it passes the entry ceiling`() = runBlocking {
        val log = log(maxEntries = 8)
        repeat(20) { log.append(entry(it + 1)) }

        val reopened = log(maxEntries = 8).snapshot()
        assertThat(reopened.size).isAtMost(8)
        assertThat(reopened.first().summary).isEqualTo("instala a skin 20")
        assertThat(file().readLines().filter { it.isNotBlank() }.size).isAtMost(8)
    }

    @Test
    fun `a broken line is skipped and the rest still loads`() = runBlocking {
        log().append(entry(1))
        file().appendText("{isto não é json\n")
        log().append(entry(2))

        assertThat(log().snapshot().map { it.summary }).containsExactly("instala a skin 2", "instala a skin 1").inOrder()
    }

    @Test
    fun `clearing deletes the file and clearing an undo is persisted`() = runBlocking {
        val log = log()
        val stored = log.append(entry(1, undo = UndoCall("skin_remove", """{"id":"neon"}""")))
        log.clearUndo(stored.id)
        assertThat(log().snapshot().single().undo).isNull()

        log.clear()
        assertThat(file().exists()).isFalse()
        assertThat(log().snapshot()).isEmpty()
    }
}
