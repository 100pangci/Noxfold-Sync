package com.nutomic.syncthingandroid.service

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider

import com.google.gson.Gson

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnapshotStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: SnapshotStore

    @Before
    fun setUp() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear().commit()
        store = SharedPreferencesSnapshotStore(prefs, Gson())
    }

    @Test
    fun roundTripSupportsSubtreeAndAtomicReplacement() {
        val state = mapOf(
            "docs" to SafBridge.NodeInfo(isDir = true),
            "docs/a.txt" to SafBridge.NodeInfo(isDir = false, size = 3, mtime = 0, contentHash = "a"),
            "other.txt" to SafBridge.NodeInfo(isDir = false, size = 5, mtime = 4),
        )
        assertTrue(store.replaceFullSnapshot("bridge", state))
        assertEquals(state, store.loadBridge("bridge"))
        assertEquals(
            state.filterKeys { it == "docs" || it.startsWith("docs/") },
            store.loadSubtree("bridge", "docs"),
        )

        assertTrue(store.transaction("bridge") { it.remove("other.txt") })
        assertEquals(2, store.loadBridge("bridge").size)
        assertTrue(store.clearBridge("bridge"))
        assertTrue(store.loadBridge("bridge").isEmpty())
    }

    @Test
    fun putAndRemoveEntriesStayBehindTheStoreBoundary() {
        assertTrue(
            store.putEntries(
                "bridge",
                mapOf("a.txt" to SafBridge.NodeInfo(isDir = false, size = 1)),
            )
        )
        assertTrue(store.removeEntries("bridge", setOf("a.txt")))
        assertTrue(store.loadBridge("bridge").isEmpty())
    }
}
