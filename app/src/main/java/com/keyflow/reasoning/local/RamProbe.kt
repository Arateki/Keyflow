package com.keyflow.reasoning.local

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Pico do processo atual; quando o kernel não permite zerar VmHWM, amostra VmRSS. */
internal class RamProbe {
    private val running = AtomicBoolean(false)
    private val peakKb = AtomicLong(0)
    private var sampler: Thread? = null
    private var hwmReset = false

    fun start() {
        hwmReset = runCatching { File("/proc/self/clear_refs").writeText("5") }.isSuccess
        if (!hwmReset) {
            running.set(true)
            sampler = Thread({
                while (running.get()) {
                    sample()
                    try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                }
                sample()
            }, "reasoning-ram").also { it.start() }
        }
    }

    fun stop(): Int? {
        running.set(false)
        sampler?.interrupt()
        sampler?.join()
        val kb = if (hwmReset) read("VmHWM") else peakKb.get().takeIf { it > 0 }
        return kb?.let { ((it + 1023) / 1024).toInt() }
    }

    private fun sample() {
        read("VmRSS")?.let { kb -> peakKb.updateAndGet { maxOf(it, kb) } }
    }

    private fun read(key: String): Long? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
        }
    }.getOrNull()
}
