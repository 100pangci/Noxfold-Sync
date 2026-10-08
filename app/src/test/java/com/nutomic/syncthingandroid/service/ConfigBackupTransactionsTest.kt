package com.nutomic.syncthingandroid.service

import java.io.File
import java.io.IOException
import java.nio.file.Files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBackupTransactionsTest {

    @Test
    fun failedWriteValidationAndInstallKeepPreviousExport() {
        val root = Files.createTempDirectory("config-export-transaction").toFile()
        try {
            val backup = File(root, "config.zip").apply { writeText("previous usable ZIP") }

            assertFalse(
                ConfigBackupTransactions.writeValidatedAtomically(
                    target = backup,
                    write = { staged ->
                        staged.writeText("partial ZIP")
                        throw IOException("injected ZIP write failure")
                    },
                    validate = {},
                )
            )
            assertEquals("previous usable ZIP", backup.readText())

            assertFalse(
                ConfigBackupTransactions.writeValidatedAtomically(
                    target = backup,
                    write = { it.writeText("complete bytes but invalid archive") },
                    validate = { throw IOException("injected archive validation failure") },
                )
            )
            assertEquals("previous usable ZIP", backup.readText())

            assertFalse(
                ConfigBackupTransactions.writeValidatedAtomically(
                    target = backup,
                    write = { it.writeText("new archive") },
                    validate = {},
                    replace = { _, _ -> false },
                )
            )
            assertEquals("previous usable ZIP", backup.readText())

            assertTrue(
                ConfigBackupTransactions.writeValidatedAtomically(
                    target = backup,
                    write = { it.writeText("verified next archive") },
                    validate = { check(it.readText() == "verified next archive") },
                )
            )
            assertEquals("verified next archive", backup.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedMultiFileImportRollsBackThenNextAttemptCanCommit() {
        val root = Files.createTempDirectory("config-import-transaction").toFile()
        try {
            val liveConfig = File(root, "config.xml").apply { writeText("old config") }
            val liveKey = File(root, "key.pem").apply { writeText("old key") }
            val liveDb = File(root, "index-v2").apply {
                mkdirs()
                resolve("db.bin").writeText("old database")
            }
            val livePrefs = mutableMapOf("theme" to "old")

            val firstStage = stagedFiles(root, "interrupted")
            val first = ConfigBackupTransactions.replaceAll(
                replacements = listOf(
                    ConfigBackupTransactions.Replacement(firstStage.first, liveConfig),
                    ConfigBackupTransactions.Replacement(firstStage.second, liveKey),
                    ConfigBackupTransactions.Replacement(firstStage.third, liveDb),
                ),
                backupDir = File(root, "import-1/previous"),
                commit = { false },
                checkpoint = { stage, index ->
                    if (stage == "installed" && index == 1) {
                        throw IOException("injected failure after two replacements")
                    }
                },
            )
            assertFalse(first)
            assertEquals("old config", liveConfig.readText())
            assertEquals("old key", liveKey.readText())
            assertEquals("old database", File(liveDb, "db.bin").readText())
            assertEquals("old", livePrefs["theme"])

            val preferenceFailureStage = stagedFiles(root, "prefs-failed")
            val preferenceFailure = ConfigBackupTransactions.replaceAll(
                replacements = listOf(
                    ConfigBackupTransactions.Replacement(preferenceFailureStage.first, liveConfig),
                    ConfigBackupTransactions.Replacement(preferenceFailureStage.second, liveKey),
                    ConfigBackupTransactions.Replacement(preferenceFailureStage.third, liveDb),
                ),
                backupDir = File(root, "import-prefs-failed/previous"),
                commit = {
                    livePrefs["theme"] = "new"
                    livePrefs["theme"] = "old" // manager's preference rollback hook
                    false
                },
            )
            assertFalse(preferenceFailure)
            assertEquals("old", livePrefs["theme"])
            assertEquals("old config", liveConfig.readText())
            assertEquals("old key", liveKey.readText())
            assertEquals("old database", File(liveDb, "db.bin").readText())

            val secondStage = stagedFiles(root, "retry")
            val second = ConfigBackupTransactions.replaceAll(
                replacements = listOf(
                    ConfigBackupTransactions.Replacement(secondStage.first, liveConfig),
                    ConfigBackupTransactions.Replacement(secondStage.second, liveKey),
                    ConfigBackupTransactions.Replacement(secondStage.third, liveDb),
                ),
                backupDir = File(root, "import-2/previous"),
                commit = { true },
            )
            assertTrue(second)
            assertEquals("retry config", liveConfig.readText())
            assertEquals("retry key", liveKey.readText())
            assertEquals("retry database", File(liveDb, "db.bin").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    private data class StagedFiles(val first: File, val second: File, val third: File)

    private fun stagedFiles(root: File, label: String): StagedFiles {
        val stage = File(root, "$label-stage").apply { mkdirs() }
        val config = File(stage, "config.xml").apply { writeText("$label config") }
        val key = File(stage, "key.pem").apply { writeText("$label key") }
        val db = File(stage, "index-v2").apply {
            mkdirs()
            resolve("db.bin").writeText("$label database")
        }
        return StagedFiles(config, key, db)
    }
}
