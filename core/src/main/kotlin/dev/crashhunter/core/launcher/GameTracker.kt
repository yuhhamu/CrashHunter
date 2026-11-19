package dev.crashhunter.core.launcher

import java.io.File

class GameTracker(private val file: File) {
    @Synchronized
    fun add(instanceId: String, pids: List<Long>) {
        val known = read().toMutableList()
        for (pid in pids) {
            if (known.any { it.pid == pid }) continue
            val start = ProcessHandle.of(pid).flatMap { it.info().startInstant() }.map { it.toEpochMilli() }.orElse(0L)
            known += Entry(pid, start, instanceId)
        }
        write(known)
    }

    @Synchronized
    fun clear(instanceId: String) = write(read().filter { it.instanceId != instanceId })

    @Synchronized
    fun killLeftovers(): Int {
        var n = 0
        for (e in read()) {
            val h = ProcessHandle.of(e.pid).orElse(null) ?: continue
            val start = h.info().startInstant().map { it.toEpochMilli() }.orElse(0L)
            if (h.isAlive && (e.start == 0L || start == e.start)) { h.destroyForcibly(); n++ }
        }
        write(emptyList())
        return n
    }

    private data class Entry(val pid: Long, val start: Long, val instanceId: String)

    private fun read(): List<Entry> = if (!file.isFile) emptyList() else file.readLines(Charsets.UTF_8).mapNotNull { line ->
        val p = line.split('|', limit = 3)
        if (p.size < 3) null else Entry(p[0].toLongOrNull() ?: return@mapNotNull null, p[1].toLongOrNull() ?: 0L, p[2])
    }

    private fun write(list: List<Entry>) {
        file.parentFile?.mkdirs()
        if (list.isEmpty()) file.delete() else file.writeText(list.joinToString("\n") { "${it.pid}|${it.start}|${it.instanceId}" }, Charsets.UTF_8)
    }
}
