package com.nutomic.syncthingandroid.ui.screens.folderpicker

import java.io.File
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for the folder picker's entry model: `ls -Ap` parsing for root browse mode,
 * shell quoting and the non-root java.io.File listing (directories first, case-insensitive).
 */
class FolderPickerRootListingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parseLsApOutput_marksDirectoriesAndJoinsPaths() {
        val entries = parseLsApOutput(
            listOf("subdir/", "plain.txt", "", ".hidden/"),
            "/data/data/other.app/files",
        )
        assertEquals(3, entries.size)
        assertEquals(PickerEntry("subdir", "/data/data/other.app/files/subdir", true), entries[0])
        assertEquals(PickerEntry("plain.txt", "/data/data/other.app/files/plain.txt", false), entries[1])
        assertEquals(PickerEntry(".hidden", "/data/data/other.app/files/.hidden", true), entries[2])
    }

    @Test
    fun shellQuote_escapesSingleQuotes() {
        assertEquals("'/sdcard/DCIM'", shellQuote("/sdcard/DCIM"))
        assertEquals("'it'\\''s'", shellQuote("it's"))
    }

    @Test
    fun listEntries_nonRoot_sortsDirectoriesFirstThenCaseInsensitive() {
        val dir = tempFolder.newFolder("bDir")
        tempFolder.newFolder("aDir")
        tempFolder.newFile("Zfile.txt")
        tempFolder.newFile("aFile.txt")

        val entries = listEntries(tempFolder.root, rootBrowse = false)
        assertEquals(
            listOf("aDir", "bDir", "aFile.txt", "Zfile.txt"),
            entries.map { it.name },
        )
        assertTrue(entries[0].isDirectory)
        assertFalse(entries[2].isDirectory)
        assertEquals(File(dir.parentFile, "aDir").absolutePath, entries[0].path)
    }

    @Test
    fun listEntries_nonRoot_emptyDirectoryReturnsEmptyList() {
        val entries = listEntries(tempFolder.root, rootBrowse = false)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun findContainingRoot_selectsDeepestMatchingRoot() {
        val roots = setOf(
            File("/"),
            File("/storage/emulated/0"),
            File("/storage/emulated/0/Download"),
        )

        val result = findContainingRoot(
            roots,
            File("/storage/emulated/0/Download/archive"),
        )

        assertEquals(File("/storage/emulated/0/Download"), result)
    }

    @Test
    fun findContainingRoot_doesNotMatchSiblingPrefix() {
        val roots = setOf(File("/storage/emulated/0/Down"))

        val result = findContainingRoot(roots, File("/storage/emulated/0/Download"))

        assertEquals(null, result)
    }

    @Test
    fun twoPaneDecision_usesActualPaneWidth() {
        assertFalse(
            useTwoPaneFolderPicker(
                isRootView = false,
                isTelevision = false,
                availableWidth = 400.dp,
            )
        )
        assertTrue(
            useTwoPaneFolderPicker(
                isRootView = false,
                isTelevision = false,
                availableWidth = 720.dp,
            )
        )
    }
}
