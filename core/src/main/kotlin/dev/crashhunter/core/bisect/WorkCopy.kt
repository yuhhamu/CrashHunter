package dev.crashhunter.core.bisect

import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.launcher.Formats
import dev.crashhunter.core.prism.InstanceScanner
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object WorkCopy {
    const val MARKER = ".crashhunter-workcopy"
    const val STAGING_SUFFIX = ".crashhunter-tmp"
    private val linked = setOf("mods", "resourcepacks", "shaderpacks")
    private val skipped = setOf("logs", "crash-reports", "screenshots", "backups", ".cache", "debug", "modloomsupport_out", "dummy_out")
    private const val COPY_LIMIT = 128L * 1024 * 1024

    fun dirFor(instance: GameInstance, slot: Int = 0, variant: String = ""): File =
        File(instance.dir.parentFile, instance.id + (if (variant.isNotEmpty()) "-$variant" else "") + (if (slot > 0) "-${slot + 1}" else "") + InstanceScanner.WORK_COPY_SUFFIX)

    fun isWorkCopy(dir: File) = File(dir, MARKER).isFile

    fun create(instance: GameInstance, includeSaves: Boolean, slot: Int = 0, variant: String = "", progress: (String) -> Unit = {}): GameInstance {
        val target = dirFor(instance, slot, variant)
        if (target.exists()) {
            require(isWorkCopy(target) || target.list().isNullOrEmpty()) { "${target.name} exists and was not made by CrashHunter" }
            if (isWorkCopy(target)) delete(target) else target.delete()
        }
        val staging = File(target.parentFile, target.name + STAGING_SUFFIX)
        if (staging.exists()) staging.walkBottomUp().forEach { it.delete() }
        staging.mkdirs()
        File(staging, MARKER).writeText(instance.dir.absolutePath)

        val format = Formats.of(instance)
        for (f in instance.dir.listFiles() ?: emptyArray()) {
            if (f.absolutePath == instance.gameDir.absolutePath || format.ownsCopyFile(f.name)) continue
            copyTree(f, File(staging, f.name))
        }

        val gameTarget = File(staging, instance.gameDir.name)
        gameTarget.mkdirs()
        for (f in instance.gameDir.listFiles() ?: emptyArray()) {
            val dst = File(gameTarget, f.name)
            when {
                f.isFile -> if (!f.name.startsWith("hs_err_pid") && !f.name.endsWith(".log")) copyFile(f, dst)
                f.name in skipped -> {}
                f.name == "saves" -> if (includeSaves) { progress("saves"); copyTree(f, dst) }
                f.name in linked -> { progress(f.name); linkTree(f, dst) }
                else -> if (sizeOf(f, COPY_LIMIT) < COPY_LIMIT) copyTree(f, dst) else linkTree(f, dst)
            }
        }
        format.writeCopyFiles(instance, staging, "CrashHunter${if (variant.isNotEmpty()) " $variant" else ""}${if (slot > 0) " ${slot + 1}" else ""}")
        var moved = false
        for (i in 0 until 20) { if (staging.renameTo(target)) { moved = true; break }; Thread.sleep(300) }
        check(moved) { "could not move ${staging.name} to ${target.name}" }
        return format.read(target)
    }

    fun delete(dir: File) {
        if (!isWorkCopy(dir)) return
        dir.walkBottomUp().forEach { it.delete() }
        for (i in 0 until 10) { if (!dir.exists() || dir.delete()) break; Thread.sleep(300) }
    }

    private fun sizeOf(dir: File, cap: Long): Long {
        var total = 0L
        for (f in dir.walkTopDown()) { if (f.isFile) total += f.length(); if (total >= cap) return total }
        return total
    }

    private fun copyFile(src: File, dst: File) {
        dst.parentFile.mkdirs()
        Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
    }

    private fun copyTree(src: File, dst: File) {
        if (src.isFile) { copyFile(src, dst); return }
        src.walkTopDown().forEach { f ->
            val rel = f.relativeTo(src).path
            val out = File(dst, rel)
            if (f.isDirectory) out.mkdirs() else copyFile(f, out)
        }
    }

    private fun linkTree(src: File, dst: File) {
        src.walkTopDown().forEach { f ->
            val out = File(dst, f.relativeTo(src).path)
            if (f.isDirectory) { out.mkdirs(); return@forEach }
            out.parentFile.mkdirs()
            val ok = runCatching { Files.createLink(out.toPath(), f.toPath()) }.isSuccess
            if (!ok) copyFile(f, out)
        }
    }
}
