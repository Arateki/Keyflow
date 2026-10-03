package com.keyflow.agent

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test

/** O único caminho até `run` (D2): a ordem das portas e o que o modelo recebe de cada uma. */
class ToolExecutorTest {

    private fun call(name: String, args: String = "{}", id: String = "c1") = AgentToolCall(id, name, args)

    private fun blocking(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun `an unknown tool is an error that lists what exists, and nothing runs`() = blocking {
        val read = FakeTool("read_a")
        val h = Harness(listOf(read))
        val out = h.executor.execute(call("rm_rf"))
        assertThat(out.status).isEqualTo(ToolStatus.ERROR)
        assertThat(out.content).contains("Ferramenta desconhecida")
        assertThat(out.content).contains("read_a")
        assertThat(read.runs).isEmpty()
    }

    @Test
    fun `a very long tool name is cut in the error`() = blocking {
        val out = Harness(emptyList()).executor.execute(call("x".repeat(5_000)))
        assertThat(out.content.length).isLessThan(300)
        assertThat(out.content).contains("Nenhuma ferramenta está ativa")
    }

    @Test
    fun `a disabled tool is blocked before anything else, with no question asked`() = blocking {
        val write = FakeTool("w", ToolRisk.WRITE)
        val h = Harness(listOf(write), ToolPolicyConfig(disabledTools = setOf("w")))
        val out = h.executor.execute(call("w"))
        assertThat(out.status).isEqualTo(ToolStatus.BLOCKED)
        assertThat(out.content).contains("desligada")
        assertThat(write.runs).isEmpty()
        assertThat(h.confirmation.requests).isEmpty()
    }

    @Test
    fun `the master switch off blocks every tool`() = blocking {
        val read = FakeTool("r")
        val h = Harness(listOf(read), ToolPolicyConfig(toolsEnabled = false))
        assertThat(h.executor.execute(call("r")).status).isEqualTo(ToolStatus.BLOCKED)
        assertThat(h.executor.activeTools()).isEmpty()
        assertThat(read.runs).isEmpty()
    }

    @Test
    fun `invalid arguments are an error and the tool does not run`() = blocking {
        val read = FakeTool("r")
        val h = Harness(listOf(read))
        for (bad in listOf("""{"text":5}""", """{"text":"${"x".repeat(101)}"}""", """{"extra":1}""", "not json", "[1]")) {
            val out = h.executor.execute(call("r", bad))
            assertThat(out.status).isEqualTo(ToolStatus.ERROR)
            assertThat(out.content).contains("Argumentos inválidos para r")
        }
        assertThat(read.runs).isEmpty()
    }

    @Test
    fun `a secret in the arguments is refused and never echoed`() = blocking {
        val write = FakeTool("w", ToolRisk.WRITE)
        val h = Harness(listOf(write))
        val out = h.executor.execute(call("w", """{"text":"${Sentinels.ANTHROPIC}"}"""))
        assertThat(out.status).isEqualTo(ToolStatus.ERROR)
        assertThat(out.content).contains("segredo")
        assertThat(out.content).doesNotContain(Sentinels.ANTHROPIC)
        assertThat(write.runs).isEmpty()
        assertThat(h.confirmation.requests).isEmpty()
        assertThat(h.log.snapshot()).isEmpty()
    }

    @Test
    fun `a read tool runs directly, with no question and no log`() = blocking {
        val read = FakeTool("r", onRun = { ToolOutput.ok("conteúdo") })
        val h = Harness(listOf(read))
        val phases = ArrayList<ToolPhase>()
        val out = h.executor.execute(call("r", """{"text":"oi"}""")) { phases += it }
        assertThat(out.status).isEqualTo(ToolStatus.OK)
        assertThat(out.content).isEqualTo("conteúdo")
        assertThat(read.runs.single().getString("text")).isEqualTo("oi")
        assertThat(h.confirmation.requests).isEmpty()
        assertThat(h.log.snapshot()).isEmpty()
        assertThat(phases).containsExactly(ToolPhase.RUNNING)
    }

    @Test
    fun `a write asks with the summary the app wrote, then runs and is logged`() = blocking {
        val preview = ToolPreview("image/png", byteArrayOf(1, 2, 3), "folha de contato")
        val write = FakeTool("skin_install", ToolRisk.WRITE, summary = { "Instalar a skin Neon Rain" }, preview = preview, onRun = { ToolOutput.ok("instalada", undo = UndoCall("skin_remove", """{"id":"neon"}""")) })
        val h = Harness(listOf(write))
        val phases = ArrayList<ToolPhase>()
        val out = h.executor.execute(call("skin_install", """{"text":"o modelo diz: é inofensivo"}""")) { phases += it }

        val request = h.confirmation.requests.single()
        assertThat(request.summary).isEqualTo("Instalar a skin Neon Rain")
        assertThat(request.summary).doesNotContain("inofensivo")
        assertThat(request.preview).isSameInstanceAs(preview)
        assertThat(request.canAlwaysAllow).isTrue()
        assertThat(request.risk).isEqualTo(ToolRisk.WRITE)
        assertThat(request.callId).isEqualTo("c1")
        assertThat(request.origin).isEqualTo(ToolOrigin.CHAT)

        assertThat(out.status).isEqualTo(ToolStatus.OK)
        assertThat(out.summary).isEqualTo("Instalar a skin Neon Rain")
        assertThat(out.undo).isEqualTo(UndoCall("skin_remove", """{"id":"neon"}"""))
        assertThat(phases).containsExactly(ToolPhase.AWAITING_CONFIRMATION, ToolPhase.RUNNING).inOrder()

        val entry = h.log.snapshot().single()
        assertThat(entry.toolName).isEqualTo("skin_install")
        assertThat(entry.outcome).isEqualTo(ActionOutcome.OK)
        assertThat(entry.summary).isEqualTo("Instalar a skin Neon Rain")
        assertThat(entry.detail).isEqualTo("instalada")
        assertThat(entry.undo).isEqualTo(out.undo)
        assertThat(entry.atMs).isEqualTo(1_000L)
        assertThat(entry.origin).isEqualTo(ToolOrigin.CHAT)
    }

    @Test
    fun `the default summary lists the validated arguments, cut`() = blocking {
        val write = FakeTool("w", ToolRisk.WRITE)
        val h = Harness(listOf(write))
        h.executor.execute(call("w", """{"text":"${"y".repeat(90)}"}"""))
        val summary = h.confirmation.requests.single().summary
        assertThat(summary).startsWith("w(text=yyy")
        assertThat(summary.length).isLessThan(100)
    }

    @Test
    fun `when the user denies, the tool does not run and the model is told`() = blocking {
        val write = FakeTool("w", ToolRisk.WRITE)
        val h = Harness(listOf(write), confirmation = FakeConfirmation { ConfirmationDecision.DENY })
        val out = h.executor.execute(call("w"))
        assertThat(out.status).isEqualTo(ToolStatus.DENIED)
        assertThat(out.content).contains("O usuário negou")
        assertThat(write.runs).isEmpty()
        assertThat(h.log.snapshot().single().outcome).isEqualTo(ActionOutcome.DENIED)
    }

    @Test
    fun `a confirmation that throws counts as a denial`() = blocking {
        val write = FakeTool("w", ToolRisk.WRITE)
        val h = Harness(listOf(write), confirmation = FakeConfirmation { throw IllegalStateException("painel quebrou") })
        val out = h.executor.execute(call("w"))
        assertThat(out.status).isEqualTo(ToolStatus.DENIED)
        assertThat(write.runs).isEmpty()
    }

    @Test
    fun `allow always on a write remembers it, and the next call runs without asking`() = blocking {
        val write = FakeTool("w", ToolRisk.WRITE)
        val h = Harness(listOf(write), confirmation = FakeConfirmation { ConfirmationDecision.ALLOW_ALWAYS })
        h.executor.execute(call("w", id = "a"))
        h.executor.execute(call("w", id = "b"))
        assertThat(h.alwaysAllowedGrants).containsExactly("w")
        assertThat(h.confirmation.requests).hasSize(1)
        assertThat(write.runs).hasSize(2)
        // E o registro de ações continua tendo as duas, com resumo do app.
        assertThat(h.log.snapshot()).hasSize(2)
    }

    @Test
    fun `allow always on a destructive tool runs once and grants nothing`() = blocking {
        val destroy = FakeTool("d", ToolRisk.DESTRUCTIVE)
        val h = Harness(listOf(destroy), confirmation = FakeConfirmation { ConfirmationDecision.ALLOW_ALWAYS })
        h.executor.execute(call("d", id = "a"))
        h.executor.execute(call("d", id = "b"))
        assertThat(h.alwaysAllowedGrants).isEmpty()
        assertThat(h.confirmation.requests).hasSize(2)
        assertThat(h.confirmation.requests.all { !it.canAlwaysAllow }).isTrue()
        assertThat(destroy.runs).hasSize(2)
    }

    @Test
    fun `allow always is ignored when the panel did not offer it`() = blocking {
        val write = FakeTool("w", ToolRisk.WRITE)
        val h = Harness(listOf(write), ToolPolicyConfig(mode = ConfirmationMode.ASK_FOR_ALL), FakeConfirmation { ConfirmationDecision.ALLOW_ALWAYS })
        h.executor.execute(call("w"))
        assertThat(h.alwaysAllowedGrants).isEmpty()
        assertThat(write.runs).hasSize(1)
    }

    @Test
    fun `an external call still asks even for a tool allowed always in the chat`() = blocking {
        val write = FakeTool("w", ToolRisk.WRITE)
        val h = Harness(listOf(write), ToolPolicyConfig(alwaysAllowed = setOf("w")))
        h.executor.execute(call("w"), ToolOrigin.CHAT)
        assertThat(h.confirmation.requests).isEmpty()
        h.executor.execute(call("w", id = "b"), ToolOrigin.EXTERNAL)
        assertThat(h.confirmation.requests.single().origin).isEqualTo(ToolOrigin.EXTERNAL)
        assertThat(h.log.snapshot().first().origin).isEqualTo(ToolOrigin.EXTERNAL)
    }

    @Test
    fun `the strict mode asks even for a read`() = blocking {
        val read = FakeTool("r")
        val h = Harness(listOf(read), ToolPolicyConfig(mode = ConfirmationMode.ASK_FOR_ALL))
        h.executor.execute(call("r"))
        assertThat(h.confirmation.requests.single().canAlwaysAllow).isFalse()
        assertThat(read.runs).hasSize(1)
    }

    @Test
    fun `a tool turned off while the panel was open does not run`() = blocking {
        val write = FakeTool("w", ToolRisk.WRITE)
        val h = Harness(listOf(write))
        h.confirmation.onRequest = { h.config = h.config.copy(disabledTools = setOf("w")) }
        val out = h.executor.execute(call("w"))
        assertThat(out.status).isEqualTo(ToolStatus.BLOCKED)
        assertThat(write.runs).isEmpty()
    }

    @Test
    fun `a tool that refuses while describing is an error to the model, and the user is not bothered`() = blocking {
        val write = FakeTool("w", ToolRisk.WRITE, onDescribe = { throw ToolFailure("a skin tem 3 erros") })
        val h = Harness(listOf(write))
        val out = h.executor.execute(call("w"))
        assertThat(out.status).isEqualTo(ToolStatus.ERROR)
        assertThat(out.content).isEqualTo("a skin tem 3 erros")
        assertThat(h.confirmation.requests).isEmpty()
        assertThat(write.runs).isEmpty()
    }

    @Test
    fun `an unexpected failure while describing is an error with the class name only`() = blocking {
        val write = FakeTool("w", ToolRisk.WRITE, onDescribe = { error("segredo ${Sentinels.OPENAI}") })
        val out = Harness(listOf(write)).executor.execute(call("w"))
        assertThat(out.status).isEqualTo(ToolStatus.ERROR)
        assertThat(out.content).contains("IllegalStateException")
        assertThat(out.content).doesNotContain(Sentinels.OPENAI)
    }

    @Test
    fun `a ToolFailure message reaches the model but any other exception shows only its class`() = blocking {
        val failing = FakeTool("f", onRun = { throw ToolFailure("o arquivo não existe") })
        val crashing = FakeTool("c", onRun = { throw IllegalArgumentException("caminho /data/user/0/x e senha ${Sentinels.VAULT}") })
        val h = Harness(listOf(failing, crashing), secrets = { listOf(Sentinels.VAULT) })
        val a = h.executor.execute(call("f"))
        assertThat(a.status).isEqualTo(ToolStatus.ERROR)
        assertThat(a.content).isEqualTo("o arquivo não existe")
        val b = h.executor.execute(call("c"))
        assertThat(b.status).isEqualTo(ToolStatus.ERROR)
        assertThat(b.content).contains("IllegalArgumentException")
        assertThat(b.content).doesNotContain("/data/user")
        assertThat(b.content).doesNotContain(Sentinels.VAULT)
    }

    @Test
    fun `an error output keeps its flag and is logged as an error`() = blocking {
        val write = FakeTool("w", ToolRisk.WRITE, onRun = { ToolOutput.error("não coube") })
        val h = Harness(listOf(write))
        val out = h.executor.execute(call("w"))
        assertThat(out.status).isEqualTo(ToolStatus.ERROR)
        assertThat(h.log.snapshot().single().outcome).isEqualTo(ActionOutcome.ERROR)
    }

    @Test
    fun `a tool that takes too long is interrupted with an error`() = blocking {
        val slow = FakeTool("s", timeoutMs = 50, onRun = { delay(5_000); ToolOutput.ok("tarde demais") })
        val out = Harness(listOf(slow)).executor.execute(call("s"))
        assertThat(out.status).isEqualTo(ToolStatus.ERROR)
        assertThat(out.content).contains("tempo limite")
    }

    @Test
    fun `a describe that takes too long is an error and the user is not asked`() = blocking {
        val write = FakeTool("w", ToolRisk.WRITE, timeoutMs = 50, onDescribe = { delay(5_000); ToolDescription("tarde") })
        val h = Harness(listOf(write))
        val out = h.executor.execute(call("w"))
        assertThat(out.status).isEqualTo(ToolStatus.ERROR)
        assertThat(out.content).contains("tempo limite")
        assertThat(h.confirmation.requests).isEmpty()
        assertThat(write.runs).isEmpty()
    }

    @Test
    fun `describe and run both happen off the caller thread`() = runBlocking {
        val threads = CopyOnWriteArrayList<String>()
        val write = FakeTool("w", ToolRisk.WRITE, onDescribe = { threads += Thread.currentThread().name; ToolDescription("x") }, onRun = { threads += Thread.currentThread().name; ToolOutput.ok("x") })
        val executor = ToolExecutor(ToolRegistry(listOf(write)), ToolPolicy({ ToolPolicyConfig() }), FakeConfirmation(), SecretFilter(), InMemoryActionLog(), dispatcher = Dispatchers.Default)
        val caller = Thread.currentThread().name
        withTimeout(5_000) { executor.execute(call("w")) }
        assertThat(threads).hasSize(2)
        assertThat(threads.none { it == caller }).isTrue()
    }

    @Test
    fun `the time spent waiting for the user does not count against the tool timeout`() = blocking {
        val write = FakeTool("w", ToolRisk.WRITE, timeoutMs = 400, onRun = { ToolOutput.ok("rápida") })
        val h = Harness(listOf(write))
        h.confirmation.onRequest = { delay(900) } // bem mais que o tempo limite da ferramenta
        val out = h.executor.execute(call("w"))
        assertThat(out.status).isEqualTo(ToolStatus.OK)
    }

    @Test
    fun `a result with a secret is filtered before it leaves`() = blocking {
        val read = FakeTool("r", onRun = { ToolOutput.ok("chave ${Sentinels.ANTHROPIC} e cofre ${Sentinels.VAULT}") })
        val h = Harness(listOf(read), secrets = { listOf(Sentinels.VAULT) })
        val out = h.executor.execute(call("r"))
        assertThat(out.content).doesNotContain(Sentinels.ANTHROPIC)
        assertThat(out.content).doesNotContain(Sentinels.VAULT)
        assertThat(out.content).contains(SecretFilter.MARKER)
    }

    @Test
    fun `the log never holds a secret, even from the detail or the undo`() = blocking {
        val write = FakeTool(
            "w", ToolRisk.WRITE,
            summary = { "Mudar ${Sentinels.GOOGLE}" },
            onRun = { ToolOutput.ok("feito com ${Sentinels.OPENAI}", undo = UndoCall("w", """{"text":"${Sentinels.ANTHROPIC}"}""")) },
        )
        val h = Harness(listOf(write))
        h.executor.execute(call("w"))
        val entry = h.log.snapshot().single()
        val everything = "${entry.summary}|${entry.detail}|${entry.undo}"
        for (s in listOf(Sentinels.GOOGLE, Sentinels.OPENAI, Sentinels.ANTHROPIC)) assertThat(everything).doesNotContain(s)
        assertThat(h.confirmation.requests.single().summary).doesNotContain(Sentinels.GOOGLE)
    }

    @Test
    fun `a huge result is cut with a notice, after filtering`() = blocking {
        val big = "linha longa ".repeat(5_000) + Sentinels.ANTHROPIC
        val read = FakeTool("r", maxResultChars = 1_000, onRun = { ToolOutput.ok(big) })
        val out = Harness(listOf(read)).executor.execute(call("r"))
        assertThat(out.content.length).isLessThan(1_200)
        assertThat(out.content).contains("resultado cortado")
        assertThat(out.content).doesNotContain("sk-ant")
    }

    @Test
    fun `a cut never splits a surrogate pair`() = blocking {
        val emoji = "😀" // 😀 = dois chars
        val read = FakeTool("r", maxResultChars = 5, onRun = { ToolOutput.ok("abcd$emoji$emoji") })
        val out = Harness(listOf(read)).executor.execute(call("r"))
        val kept = out.content.substringBefore("\n[")
        assertThat(kept).isEqualTo("abcd")
    }

    @Test
    fun `cancelling during the run cancels the tool and logs the write as cancelled`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val write = FakeTool("w", ToolRisk.WRITE, onRun = {
            started.complete(Unit)
            try {
                delay(60_000)
                ToolOutput.ok("nunca")
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        })
        val h = Harness(listOf(write))
        val job = launch { h.executor.execute(call("w")) }
        withTimeout(5_000) { started.await() }
        job.cancel()
        job.join()
        assertThat(cancelled).isTrue()
        assertThat(h.log.snapshot().single().outcome).isEqualTo(ActionOutcome.CANCELLED)
    }

    @Test
    fun `cancelling while the user decides cancels the wait and logs it`() = runBlocking {
        val asked = CompletableDeferred<Unit>()
        val write = FakeTool("w", ToolRisk.WRITE)
        val h = Harness(listOf(write))
        h.confirmation.onRequest = {
            asked.complete(Unit)
            delay(60_000)
        }
        val job = launch { h.executor.execute(call("w")) }
        withTimeout(5_000) { asked.await() }
        job.cancel()
        job.join()
        assertThat(write.runs).isEmpty()
        assertThat(h.log.snapshot().single().outcome).isEqualTo(ActionOutcome.CANCELLED)
    }

    @Test
    fun `a failing action log does not hide the result`() = blocking {
        val failingLog = object : ActionLog {
            override suspend fun append(entry: ActionLogEntry): ActionLogEntry = throw IllegalStateException("disco cheio")
            override fun observe() = kotlinx.coroutines.flow.flowOf(emptyList<ActionLogEntry>())
            override suspend fun clear() = Unit
        }
        val write = FakeTool("w", ToolRisk.WRITE, onRun = { ToolOutput.ok("feito") })
        val executor = ToolExecutor(ToolRegistry(listOf(write)), ToolPolicy({ ToolPolicyConfig() }), FakeConfirmation(), SecretFilter(), failingLog, dispatcher = Dispatchers.Unconfined)
        val out = executor.execute(call("w"))
        assertThat(out.status).isEqualTo(ToolStatus.OK)
        assertThat(write.runs).hasSize(1)
    }

    @Test
    fun `only one question at a time is open, even with two calls at once`() = runBlocking {
        val write = FakeTool("w", ToolRisk.WRITE)
        val h = Harness(listOf(write))
        h.confirmation.onRequest = { delay(50) }
        withTimeout(10_000) {
            listOf(
                async { h.executor.execute(call("w", id = "a")) },
                async { h.executor.execute(call("w", id = "b"), ToolOrigin.EXTERNAL) },
                async { h.executor.execute(call("w", id = "c")) },
            ).awaitAll()
        }
        assertThat(h.confirmation.requests).hasSize(3)
        assertThat(h.confirmation.maxConcurrent).isEqualTo(1)
    }

    @Test
    fun `the tool runs on the executor dispatcher, not on the caller`() = runBlocking {
        val threads = CopyOnWriteArrayList<String>()
        val read = FakeTool("r", onRun = { threads += Thread.currentThread().name; ToolOutput.ok("x") })
        val registry = ToolRegistry(listOf(read))
        val executor = ToolExecutor(registry, ToolPolicy({ ToolPolicyConfig() }), FakeConfirmation(), SecretFilter(), InMemoryActionLog(), dispatcher = Dispatchers.Default)
        val caller = Thread.currentThread().name
        withTimeout(5_000) { executor.execute(call("r")) }
        assertThat(threads.single()).isNotEqualTo(caller)
    }

    @Test
    fun `arguments are handed to the tool as the parsed object`() = blocking {
        var seen: JSONObject? = null
        val read = FakeTool("r", onRun = { seen = it; ToolOutput.ok("ok") })
        Harness(listOf(read)).executor.execute(call("r", """{"text":"olá"}"""))
        assertThat(seen!!.getString("text")).isEqualTo("olá")
    }

    // --- imagem no resultado e legenda da prévia (A4) ---

    private val png = ToolImage("image/png", byteArrayOf(1, 2, 3))

    @Test
    fun `an image returned by a tool reaches the outcome, whether it succeeded or failed`() = blocking {
        val ok = FakeTool("r_ok", onRun = { ToolOutput.ok("pronto", image = png) })
        val bad = FakeTool("r_bad", onRun = { ToolOutput.error("inválida", image = png) })
        val h = Harness(listOf(ok, bad))
        assertThat(h.executor.execute(call("r_ok")).image).isEqualTo(png)
        assertThat(h.executor.execute(call("r_bad", id = "c2")).image).isEqualTo(png)
    }

    @Test
    fun `an image that passes the size limit is dropped and the text still goes`() = blocking {
        val big = ToolImage("image/png", ByteArray(ToolImage.MAX_BYTES + 1))
        val tool = FakeTool("r", onRun = { ToolOutput.ok("texto", image = big) })
        val out = Harness(listOf(tool)).executor.execute(call("r"))
        assertThat(out.image).isNull()
        assertThat(out.content).isEqualTo("texto")
    }

    @Test
    fun `a tool without an image gives an outcome without one`() = blocking {
        assertThat(Harness(listOf(FakeTool("r"))).executor.execute(call("r")).image).isNull()
    }

    @Test
    fun `the caption of the preview is filtered like the summary, and the image bytes are untouched`() = blocking {
        val preview = ToolPreview("image/png", byteArrayOf(9, 8, 7), "orbe com a chave ${Sentinels.ANTHROPIC} no nome")
        val write = FakeTool("w", ToolRisk.WRITE, summary = { "Instalar" }, preview = preview)
        val h = Harness(listOf(write))
        h.executor.execute(call("w"))
        val shown = h.confirmation.requests.single().preview!!
        assertThat(shown.caption).doesNotContain(Sentinels.ANTHROPIC)
        assertThat(shown.caption).contains(SecretFilter.MARKER)
        assertThat(shown.bytes).isEqualTo(byteArrayOf(9, 8, 7))
        assertThat(shown.mediaType).isEqualTo("image/png")
    }
}
