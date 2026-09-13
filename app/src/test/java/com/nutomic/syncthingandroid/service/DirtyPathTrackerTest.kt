package com.nutomic.syncthingandroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirtyPathTrackerTest {

    @Test
    fun nestedHints_areCoalescedToSmallestSafeScopes() {
        val tracker = DirtyPathTracker()
        tracker.markForwardedPath("notes/work/a.md")
        tracker.markForwardedPath("notes/work/b.md")
        tracker.markSafDir("notes/work")

        val dirty = tracker.take()
        assertEquals(setOf("notes/work"), dirty.forwardedDirs)
        assertEquals(setOf("notes/work"), dirty.safDirs)
    }

    @Test
    fun unknownProviderChange_marksRoot() {
        val tracker = DirtyPathTracker()
        tracker.markSafRoot()
        tracker.markSafDir("notes/work")

        assertEquals(setOf(""), tracker.take().safDirs)
    }

    @Test
    fun invalidObserverPath_fallsBackToRoot() {
        val tracker = DirtyPathTracker()
        tracker.markForwardedPath("notes/../outside/file")

        assertEquals(setOf(""), tracker.take().forwardedDirs)
    }

    @Test
    fun restore_preservesFailedScopesAndNewerHints() {
        val tracker = DirtyPathTracker()
        tracker.markForwardedPath("a/b.txt")
        val first = tracker.take()
        tracker.markForwardedPath("c/d.txt")
        tracker.restore(first)

        val restored = tracker.take()
        assertTrue("both the failed and newer scopes must remain dirty", restored.forwardedDirs.contains("a"))
        assertTrue(restored.forwardedDirs.contains("c"))
    }
}
