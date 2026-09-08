package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider

import com.nutomic.syncthingandroid.SyncthingApp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import java.io.File

/**
 * Tests for bridge registration, fresh-install detection ("needs authorization")
 * and path-stable re-authorization of [SafBridge].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SyncthingApp::class)
class SafBridgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val fcitxUri: Uri =
        Uri.parse("content://org.fcitx.fcitx5.android.provider/tree/sync")

    @Before
    fun takePersistableGrant() {
        context.contentResolver.takePersistableUriPermission(
            fcitxUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    @Test
    fun register_returnsForwardedPathUnderBridgeRoot() {
        val safBridge = SafBridge(context)
        val path = safBridge.register(fcitxUri)
        assertTrue(safBridge.isForwardedPath(path))
        assertTrue(safBridge.isForwarded(path))
        assertFalse(safBridge.needsAuthorization(path))
        safBridge.unregister(path)
    }

    @Test
    fun register_isDeterministicForSameUri() {
        val safBridge = SafBridge(context)
        val first = safBridge.register(fcitxUri)
        val second = safBridge.register(fcitxUri)
        assertEquals(first, second)
        safBridge.unregister(first)
    }

    @Test
    fun freshInstall_needsAuthorization_andReauthorizeKeepsPath() {
        val safBridge = SafBridge(context)
        val path = safBridge.register(fcitxUri)

        // Simulate a fresh install + config import: prefs wiped, new instance, the
        // restored config still points at [path] but the mapping is gone.
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val reinstalled = SafBridge(context)

        assertFalse(reinstalled.isForwarded(path))
        assertTrue(reinstalled.needsAuthorization(path))
        // Normal storage paths are never "needs authorization".
        assertFalse(reinstalled.needsAuthorization("/storage/emulated/0/docs"))

        // Re-authorizing must keep the configured path EXACTLY as-is so the
        // imported config keeps working without a rewrite.
        reinstalled.reauthorize(path, fcitxUri)
        assertTrue(reinstalled.isForwarded(path))
        assertFalse(reinstalled.needsAuthorization(path))

        reinstalled.unregister(path)
    }

    @Test
    fun reauthorize_allowsDifferentUriForSameFolder() {
        val safBridge = SafBridge(context)
        val path = safBridge.register(fcitxUri)
        val otherUri = Uri.parse("content://org.fcitx.fcitx5.android.provider/tree/other")
        safBridge.reauthorize(path, otherUri)
        assertTrue(safBridge.isForwarded(path))
        safBridge.unregister(path)
        assertFalse(safBridge.isForwarded(path))
    }

    @Test
    fun restoredMappingWithLostGrant_needsAuthorization() {
        // The clear-data/reinstall case: the config import restored the mapping,
        // but the persisted grant was revoked by the system.
        val safBridge = SafBridge(context)
        val path = safBridge.register(fcitxUri)
        assertFalse(safBridge.needsAuthorization(path))

        context.contentResolver.releasePersistableUriPermission(
            fcitxUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        assertTrue(safBridge.needsAuthorization(path))

        // Re-picking the folder re-takes the grant and clears the condition.
        context.contentResolver.takePersistableUriPermission(
            fcitxUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        assertFalse(safBridge.needsAuthorization(path))
        safBridge.unregister(path)
    }

    @Test
    fun unregister_removesForwardingState() {
        val safBridge = SafBridge(context)
        val path = safBridge.register(fcitxUri)
        safBridge.unregister(path)
        assertFalse(safBridge.isForwarded(path))
        assertFalse(safBridge.isForwardedPath(path) && safBridge.isForwarded(path))
    }

    private fun stateKey(uri: Uri): String = "saf_bridge_state_" + SafBridge.hashOf(uri)

    private fun injectStaleState(uri: Uri, vararg paths: String) {
        val json = paths.joinToString(",", prefix = "{", postfix = "}") {
            "\"$it\":{\"isDir\":false,\"size\":1,\"mtime\":1}"
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(stateKey(uri), json).commit()
    }

    /**
     * A forward pass that was still in flight when the folder was removed can
     * re-persist its snapshot AFTER unregister cleared it. Re-adding the folder
     * then finds an empty forwarded dir against a stale snapshot; diffing against
     * it would read every file as "deleted on the forwarded side" and propagate
     * the deletions INTO the provider. register() must drop such a snapshot so
     * the next pass PULLS the provider content instead.
     */
    @Test
    fun register_dropsStaleSnapshot_leftOverFromAnInFlightPass() {
        val safBridge = SafBridge(context)
        val path = safBridge.register(fcitxUri)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // First pass succeeds and persists a snapshot.
        injectStaleState(fcitxUri, "a.txt", "b.txt")
        safBridge.unregister(path)
        assertFalse(safBridge.isForwarded(path))

        // Simulate the race: the in-flight pass re-writes the snapshot after
        // unregister cleared it (the fix gates that write on Bridge.active, so
        // this only happens for stale builds / crash windows - still defended).
        injectStaleState(fcitxUri, "a.txt", "b.txt")

        // Re-add while the forwarded dir is gone: the stale snapshot must not
        // survive, or the first pass would delete a.txt/b.txt from the provider.
        val repath = safBridge.register(fcitxUri)
        assertEquals(path, repath)
        assertTrue(safBridge.isForwarded(repath))
        assertNull(prefs.getString(stateKey(fcitxUri), null))
        safBridge.unregister(repath)
    }

    /**
     * Same defense on service start: a forwarded dir that exists but no longer
     * holds what the snapshot records must start from a clean snapshot, or the
     * first pass propagates bogus deletions into the provider.
     */
    @Test
    fun startAll_dropsStaleSnapshotWhenForwardedDirIsEmpty() {
        val safBridge = SafBridge(context)
        val path = safBridge.register(fcitxUri)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        File(path).mkdirs()
        injectStaleState(fcitxUri, "a.txt", "b.txt")

        safBridge.startAll()
        assertNull(prefs.getString(stateKey(fcitxUri), null))
        safBridge.stopAll()
    }

    /**
     * The guard must not clear a healthy snapshot: when the forwarded dir still
     * holds every path the snapshot records, mirroring may continue where it
     * left off.
     */
    @Test
    fun register_keepsSnapshotWhenForwardedDirStillMatches() {
        val safBridge = SafBridge(context)
        val path = safBridge.register(fcitxUri)
        safBridge.unregister(path)
        File(path).mkdirs()
        File(path, "a.txt").writeText("x")
        injectStaleState(fcitxUri, "a.txt")

        val repath = safBridge.register(fcitxUri)
        assertEquals(path, repath)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertNotNull(prefs.getString(stateKey(fcitxUri), null))
        safBridge.unregister(repath)
    }
}
