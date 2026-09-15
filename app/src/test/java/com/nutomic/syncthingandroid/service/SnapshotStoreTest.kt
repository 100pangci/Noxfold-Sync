package com.nutomic.syncthingandroid.service

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider

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
        store = SharedPreferencesSnapshotStore(prefs)
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
    fun loadBridge_readsStateWrittenByTheFormerGsonImplementation() {
        // Field names and null-omission must stay compatible across the
        // Gson -> kotlinx.serialization migration (persisted installs upgrade in place).
        val legacyJson = "{\"docs\":{\"isDir\":true,\"size\":0,\"mtime\":0}," +
            "\"docs/a.txt\":{\"isDir\":false,\"size\":3,\"mtime\":7,\"contentHash\":\"a\"}}"
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(SharedPreferencesSnapshotStore.STATE_PREFIX + "legacy", legacyJson)
            .commit()

        val loaded = store.loadBridge("legacy")
        assertEquals(2, loaded.size)
        assertEquals(SafBridge.NodeInfo(isDir = true), loaded["docs"])
        assertEquals(
            SafBridge.NodeInfo(isDir = false, size = 3, mtime = 7, contentHash = "a"),
            loaded["docs/a.txt"],
        )

        // Re-encoding must produce the same JSON the Gson implementation wrote.
        assertTrue(store.replaceFullSnapshot("legacy", loaded))
        val rewritten = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(SharedPreferencesSnapshotStore.STATE_PREFIX + "legacy", null)
        assertEquals(legacyJson, rewritten)
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
