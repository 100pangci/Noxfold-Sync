package com.nutomic.syncthingandroid.service

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Stages a provider stream completely before replacing a working-tree file. */
internal object AtomicFileCopy {

    /**
     * Copies [openInput] to a sibling temporary file, verifies it, and only then replaces
     * [target]. A failed open/copy/verification/replacement leaves the old target untouched.
     * [expectedTargetKnown] distinguishes an expected-absent path from a caller without a scan.
     */
    fun copy(
        openInput: () -> InputStream?,
        target: File,
        expectedSize: Long,
        expectedHash: String? = null,
        expectedMtime: Long = 0,
        expectedTargetKnown: Boolean = false,
        expectedTarget: SafBridge.NodeInfo? = null,
        backupDir: File,
        beforeReplace: () -> Unit = {},
        replace: (File, File, File) -> Boolean = ::replaceAtomically,
    ): Boolean {
        val parent = target.parentFile ?: return false
        if ((!parent.isDirectory && !parent.mkdirs()) || !parent.isDirectory) {
            return false
        }
        val input = try {
            openInput()
        } catch (_: Exception) {
            return false
        } ?: return false

        var staged: File? = null
        try {
            // Syncthing's scanner and the bridge's snapshot both ignore .syncthing.* names,
            // so a partially written sibling is never published as a syncable user file.
            staged = File.createTempFile(".syncthing.saf-bridge-", ".part", parent)
            input.use { source ->
                FileOutputStream(staged).use { output ->
                    source.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (staged.length() != expectedSize) {
                return false
            }
            if (expectedHash != null && ContentHasher.sha256(staged) != expectedHash) {
                return false
            }
            if (expectedMtime > 0 && !staged.setLastModified(expectedMtime)) {
                // Timestamp preservation is best effort; content verification is authoritative.
            }
            if (expectedTargetKnown && !matchesExpectedTarget(target, expectedTarget)) {
                return false
            }
            if (!backupDir.isDirectory && !backupDir.mkdirs()) {
                return false
            }
            beforeReplace()
            if (!replace(staged, target, backupDir)) {
                return false
            }
            staged = null // ownership moved to target
            return target.isFile && target.length() == expectedSize &&
                (expectedHash == null || ContentHasher.sha256(target) == expectedHash)
        } catch (_: Exception) {
            return false
        } finally {
            try {
                input.close()
            } catch (_: Exception) {
                // Already closed in the normal path; do not mask the copy result.
            }
            staged?.delete()
        }
    }

    private fun matchesExpectedTarget(target: File, expected: SafBridge.NodeInfo?): Boolean {
        if (expected == null) {
            return !target.exists()
        }
        if (!target.exists() || target.isDirectory != expected.isDir) {
            return false
        }
        if (expected.isDir) {
            // Internal marker/ignore files are filtered from both trees. Other remaining entries
            // may be a concurrent core write and make a type replacement unsafe.
            return containsOnlyInternalEntries(target)
        }
        if (target.length() != expected.size) {
            return false
        }
        if (expected.contentHash != null) {
            return ContentHasher.sha256(target) == expected.contentHash
        }
        return expected.mtime == 0L || target.lastModified() == 0L ||
            target.lastModified() == expected.mtime
    }

    private fun replaceAtomically(staged: File, target: File, backupDir: File): Boolean {
        if (!target.exists()) {
            return staged.renameTo(target)
        }
        if (target.isDirectory && !containsOnlyInternalEntries(target)) {
            return false
        }
        try {
            Files.move(
                staged.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return true
        } catch (_: Exception) {
            // Try the provider's normal same-filesystem replacement before the rollback-capable
            // fallback. Android/Linux implementations normally map both moves to rename(2).
        }
        try {
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return true
        } catch (_: Exception) {
            // Fall back to a same-volume backup outside the synced tree.
        }

        val backup = File(backupDir, "replace-${UUID.randomUUID()}")
        if (!target.renameTo(backup)) {
            return false
        }
        // Recheck a directory after moving it: a concurrent write between the earlier check
        // and rename must not be recursively discarded.
        if (backup.isDirectory && !containsOnlyInternalEntries(backup)) {
            backup.renameTo(target)
            return false
        }
        if (!staged.renameTo(target)) {
            backup.renameTo(target)
            return false
        }
        // A leftover backup is outside the forwarded tree and is safer than undoing a verified
        // replacement. It can be cleaned on the next app start if the filesystem is transiently
        // busy.
        backup.deleteRecursively()
        return true
    }

    private fun containsOnlyInternalEntries(directory: File): Boolean {
        val children = directory.listFiles() ?: return false
        return children.all { isSyncthingInternalName(it.name) }
    }
}
