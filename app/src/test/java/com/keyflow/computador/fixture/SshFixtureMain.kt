package com.keyflow.computador.fixture

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch

/** Entrada da mesma fixture para adb reverse; o arquivo de prontidão contém só dados públicos. */
fun main(args: Array<String>) {
    require(args.size == 2 && args[0] == "--pronto") { "Uso: --pronto <arquivo>" }
    val pronto = Path.of(args[1])
    SshFixtureServer().use { servidor ->
        val encerramento = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread { encerramento.countDown() })
        val temporario = pronto.resolveSibling(pronto.fileName.toString() + ".tmp")
        Files.writeString(temporario, "{\"porta\":${servidor.porta},\"host_sha256\":\"${servidor.hostSha256}\",\"pid\":${ProcessHandle.current().pid()}}\n")
        Files.move(temporario, pronto, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        encerramento.await()
    }
}
