package com.nutomic.syncthingandroid.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderSectionsTest {

    private fun model(id: String, group: String) = FolderUiModel(
        id = id,
        group = group,
        title = id,
        path = "/",
        typeTag = "sendreceive",
        pathShort = "/",
        overrideVisible = false,
        revertVisible = false,
        revertLabelRes = 0,
        conflictCount = 0,
        conflictFiles = emptyList(),
        lastItemText = null,
        lastItemTimeText = null,
        itemsAndSize = null,
        invalidText = null,
        statusText = null,
        statusKind = com.nutomic.syncthingandroid.ui.theme.StatusKind.OK,
        isSyncing = false,
        completion = 100,
    )

    private val natural = Comparator<String> { a, b -> a.compareTo(b) }

    @Test
    fun ungroupedFolders_landInDefaultSection() {
        val sections = buildFolderSections(
            listOf(model("a", ""), model("b", "")),
            natural
        )
        assertEquals(1, sections.size)
        assertEquals("", sections[0].groupName)
        assertEquals(listOf("a", "b"), sections[0].items.map { it.id })
    }

    @Test
    fun defaultSection_alwaysComesFirst() {
        val sections = buildFolderSections(
            listOf(model("work", "Work"), model("media", "Media"), model("backup", "")),
            natural
        )
        assertEquals(listOf("", "Media", "Work"), sections.map { it.groupName })
    }

    @Test
    fun nonDefaultSections_sortedByComparator() {
        val sections = buildFolderSections(
            listOf(model("work", "Work"), model("media", "Media")),
            natural
        )
        assertEquals(listOf("Media", "Work"), sections.map { it.groupName })
        assertEquals(listOf("media"), sections[0].items.map { it.id })
        assertEquals(listOf("work"), sections[1].items.map { it.id })
    }

    @Test
    fun folderOrder_withinGroup_preserved() {
        val sections = buildFolderSections(
            listOf(model("c", "X"), model("a", "X"), model("b", "X")),
            natural
        )
        assertEquals(listOf("c", "a", "b"), sections.single().items.map { it.id })
    }
}
