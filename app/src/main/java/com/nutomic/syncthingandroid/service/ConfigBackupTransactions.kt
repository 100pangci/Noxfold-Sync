package com.nutomic.syncthingandroid.service

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Small rollback-capable filesystem transactions used by config import/export. */
internal object ConfigBackupTransactions {

    data class Replacement(val staged: File?, val target: File)

    /**
     * Writes and validates a sibling temporary file before atomically installing it. The
     * previous target remains available if writing, validation, or replacement fails.
     */
    fun writeValidatedAtomically(
        target: File,
        write: (File) -> Unit,
        validate: (File) -> Unit,
        replace: (File, File) -> Boolean = ::replaceSingle,
    ): Boolean {
        val parent = target.parentFile ?: return false
        if (target.isDirectory) return false
        if ((!parent.isDirectory && !parent.mkdirs()) || !parent.isDirectory) return false
        val staged = try {
            File.createTempFile(".${target.name}-", ".part", parent)
        } catch (_: IOException) {
            return false
        }
        return try {
            staged.delete()
            write(staged)
            if (!staged.isFile || staged.length() == 0L) return false
            validate(staged)
            replace(staged, target)
        } catch (_: Exception) {
            false
        } finally {
            staged.deleteRecursively()
        }
    }

    /**
     * Replaces a set of live files/directories as one rollback-capable operation. A null or
     * missing staged path means that the target should be removed after successful commit.
     * [checkpoint] is an internal failure-injection seam for regression tests.
     */
    fun replaceAll(
        replacements: List<Replacement>,
        backupDir: File,
        commit: () -> Boolean = { true },
        checkpoint: (stage: String, index: Int) -> Unit = { _, _ -> },
        onRollbackFailure: () -> Unit = {},
    ): Boolean {
        if ((!backupDir.isDirectory && !backupDir.mkdirs()) || !backupDir.isDirectory) return false
        data class Applied(val target: File, val backup: File?, var installed: Boolean = false)
        val applied = ArrayList<Applied>()
        try {
            replacements.forEachIndexed { index, replacement ->
                checkpoint("before", index)
                val target = replacement.target
                val backup = if (target.exists()) {
                    File(backupDir, "${index}-${UUID.randomUUID()}").also {
                        if (!target.renameTo(it)) throw IOException("Could not stage old ${target.name}")
                    }
                } else {
                    null
                }
                val entry = Applied(target, backup)
                applied.add(entry)
                val staged = replacement.staged
                if (staged != null) {
                    if (!staged.exists()) throw IOException("Staged import entry is missing: ${staged.name}")
                    if (!staged.renameTo(target)) throw IOException("Could not install ${target.name}")
                    entry.installed = true
                }
                checkpoint("installed", index)
            }
            if (!commit()) throw IOException("Config import commit was rejected")
            // Backups are no longer needed after the data and preferences committed. Cleanup is
            // best-effort: a leftover stays outside live config paths and is safer than rollback.
            applied.forEach { it.backup?.deleteRecursively() }
            backupDir.delete()
            return true
        } catch (_: Exception) {
            var rollbackSucceeded = true
            for (entry in applied.asReversed()) {
                if (entry.installed && entry.target.exists()) {
                    if (!entry.target.deleteRecursively()) rollbackSucceeded = false
                }
                val backup = entry.backup
                if (backup != null) {
                    if (!backup.exists() || !backup.renameTo(entry.target)) {
                        // Keep the backup for manual recovery rather than deleting the only copy.
                        rollbackSucceeded = false
                    }
                }
            }
            if (!rollbackSucceeded) onRollbackFailure()
            return false
        }
    }

    private fun replaceSingle(staged: File, target: File): Boolean {
        if (target.isDirectory) return false
        if (!target.exists()) return staged.renameTo(target)
        try {
            Files.move(
                staged.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return true
        } catch (_: Exception) {
            // Rollback-capable same-filesystem fallback.
        }
        try {
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return true
        } catch (_: Exception) {
            // Only use a backup rename if ordinary replacement is unsupported too.
        }
        val backup = File(target.parentFile, ".${target.name}-backup-${UUID.randomUUID()}")
        if (!target.renameTo(backup)) return false
        if (!staged.renameTo(target)) {
            backup.renameTo(target)
            return false
        }
        backup.deleteRecursively()
        return true
    }
}
