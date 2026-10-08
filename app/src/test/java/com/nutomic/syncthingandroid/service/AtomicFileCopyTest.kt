package com.nutomic.syncthingandroid.service

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicFileCopyTest {

    @Test
    fun failedOpenCopyLengthCheckAndReplaceKeepOldTarget() {
        val root = Files.createTempDirectory("atomic-copy-test").toFile()
        try {
            val target = File(root, "current.txt").apply { writeText("old value") }
            val backups = File(root, "backups")

            assertFalse(
                AtomicFileCopy.copy(
                    openInput = { null }, target = target, expectedSize = 3,
                    backupDir = backups,
                )
            )
            assertEquals("old value", target.readText())

            assertFalse(
                AtomicFileCopy.copy(
                    openInput = { object : InputStream() {
                        private var sent = false
                        override fun read(): Int {
                            if (!sent) {
                                sent = true
                                return 'x'.code
                            }
                            throw IOException("injected disk/stream failure")
                        }
                    } },
                    target = target, expectedSize = 3, backupDir = backups,
                )
            )
            assertEquals("old value", target.readText())

            assertFalse(
                AtomicFileCopy.copy(
                    openInput = { ByteArrayInputStream("short".toByteArray()) },
                    target = target, expectedSize = 8, backupDir = backups,
                )
            )
            assertEquals("old value", target.readText())

            assertFalse(
                AtomicFileCopy.copy(
                    openInput = { ByteArrayInputStream("new".toByteArray()) },
                    target = target, expectedSize = 3, backupDir = backups,
                    replace = { _, _, _ -> false },
                )
            )
            assertEquals("old value", target.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun successfulCopyReplacesOnlyAfterVerification() {
        val root = Files.createTempDirectory("atomic-copy-success").toFile()
        try {
            val target = File(root, "current.txt").apply { writeText("old") }
            val bytes = "complete replacement".toByteArray()
            val success = AtomicFileCopy.copy(
                openInput = { ByteArrayInputStream(bytes) },
                target = target,
                expectedSize = bytes.size.toLong(),
                expectedHash = ContentHasher.sha256(ByteArrayInputStream(bytes)),
                expectedTargetKnown = true,
                expectedTarget = SafBridge.NodeInfo(
                    isDir = false,
                    size = 3,
                    mtime = target.lastModified(),
                ),
                backupDir = File(root, "backups"),
            )

            assertTrue(success)
            assertEquals(String(bytes), target.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun directorySwapRefusesConcurrentChildThenReplacesEmptyDirectory() {
        val root = Files.createTempDirectory("atomic-copy-directory-swap").toFile()
        try {
            val target = File(root, "node").apply { mkdirs() }
            val concurrent = File(target, "concurrent.txt").apply { writeText("keep me") }
            val backups = File(root, "backups")
            val bytes = "replacement file".toByteArray()
            val expectedDir = SafBridge.NodeInfo(isDir = true)

            assertFalse(
                AtomicFileCopy.copy(
                    openInput = { ByteArrayInputStream(bytes) },
                    target = target,
                    expectedSize = bytes.size.toLong(),
                    expectedTargetKnown = true,
                    expectedTarget = expectedDir,
                    backupDir = backups,
                )
            )
            assertEquals("keep me", concurrent.readText())

            assertTrue(concurrent.delete())
            assertTrue(
                AtomicFileCopy.copy(
                    openInput = { ByteArrayInputStream(bytes) },
                    target = target,
                    expectedSize = bytes.size.toLong(),
                    expectedTargetKnown = true,
                    expectedTarget = expectedDir,
                    backupDir = backups,
                )
            )
            assertTrue(target.isFile)
            assertEquals(String(bytes), target.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
