package com.nutomic.syncthingandroid.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nutomic.syncthingandroid.util.FileUtils

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Names managed by the Syncthing core inside a synced folder stay local-only:
 * they are never forwarded to the provider and never treated as provider removals.
 */
internal fun isSyncthingInternalName(name: String): Boolean {
    return name == ".stfolder" || name == ".stignore" ||
        name.startsWith(".syncthing.") || name.contains(".syncthing.")
}

/**
 * Recursive snapshot of a forwarded directory, RELATIVE paths -> [SafBridge.NodeInfo].
 * Syncthing-internal names are skipped. Shared by the per-bridge scan and the
 * stale-snapshot guards in [SafBridge.register]/[SafBridge.startAll].
 */
private fun scanForwardedDir(dir: File): Map<String, SafBridge.NodeInfo> {
    val result = LinkedHashMap<String, SafBridge.NodeInfo>()
    fun walk(dir: File, prefix: String) {
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (isSyncthingInternalName(entry.name)) {
                continue
            }
            val path = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
            if (entry.isDirectory) {
                result[path] = SafBridge.NodeInfo(isDir = true)
                walk(entry, path)
            } else {
                result[path] = SafBridge.NodeInfo(isDir = false, size = entry.length(), mtime = entry.lastModified())
            }
        }
    }
    walk(dir, "")
    return result
}

/**
 * Forwards "special" folders that have no real filesystem path (DocumentsProvider
 * roots exposed by other apps, e.g. fcitx5-android's data root) into the Syncthing
 * core.
 *
 * The core only understands real paths, and provider content is only reachable
 * through content URIs, so [SafBridge] keeps the forwarded folder in sync with the
 * provider. There is NO extra copy of the data: the forwarded directory IS the
 * folder the core syncs - the app just shuffles bytes between both worlds:
 *
 * ```
 * DocumentsProvider  <-forward->  files/saf-bridge/<hash>/  <-sync->  Syncthing core
 * ```
 *
 * A bridge is registered whenever the user picks a SAF location whose authority is
 * NOT the externalstorage provider (that one is mapped to a real path directly).
 * The mapping (forwarded dir -> tree uri) and the last-forwarded snapshot are
 * persisted, so bridges survive restarts; call [startAll] from the service and
 * [stopAll] on shutdown.
 *
 * Forwarding semantics (documented for future readers):
 *  - Every pass snapshots both sides and diffs them against the last-forwarded
 *    snapshot (three-way merge), so "deleted on one side" and "changed on the
 *    other" are told apart and deletions cannot ping-pong.
 *  - If a path changed on BOTH sides since the last pass, the forwarded-dir side
 *    wins (that content is what the core already propagated); this is logged.
 *  - SAF has no change notifications, so provider-side changes are picked up by
 *    polling; forwarded-dir changes are detected in the same pass.
 */
class SafBridge(private val context: Context) {

    companion object {

        private const val TAG = "SafBridge"

        /** Pref holding the JSON map "forwarded dir absolute path" -> "tree uri". */
        private const val PREF_MAPPINGS = "saf_bridge_mappings"

        /** Pref prefix for the persisted last-forwarded snapshot of each bridge. */
        private const val PREF_STATE_PREFIX = "saf_bridge_state_"

        /** Directory (inside the app's files dir) holding all forwarded folders. */
        private const val BRIDGE_ROOT_NAME = "saf-bridge"

        /** Provider-side change polling interval; there is no change notification. */
        private const val POLL_INTERVAL_MS = 15_000L

        /**
         * Returns true if the given SAF result must be forwarded instead of being
         * mapped to a real path (i.e. it is a third-party DocumentsProvider root).
         */
        fun requiresBridge(uri: Uri): Boolean {
            return "com.android.externalstorage.documents" != uri.authority
        }

        /** Short stable dir-name fragment for a tree uri. */
        fun hashOf(uri: Uri): String {
            val digest = MessageDigest.getInstance("MD5").digest(uri.toString().toByteArray())
            return digest.joinToString("") { String.format("%02x", it) }.substring(0, 12)
        }
    }

    private val gson = Gson()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val bridgeRoot = File(context.filesDir, BRIDGE_ROOT_NAME)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bridges = LinkedHashMap<String, Bridge>()
    private var started = false

    /**
     * Invoked by a bridge whenever it changed the forwarded directory in a way the
     * Syncthing core must pick up (files/dirs created, updated or deleted on the
     * forwarded side). The core does not watch the forwarded dir reliably (app-private
     * path, no SAF notifications), so without this the core index would go stale until
     * the next periodic rescan - e.g. right after a fresh install + config import the
     * bridge pulls the provider content in AFTER the core's initial scan already ran,
     * leaving the UI on "0 files".
     *
     * Injected by [SyncthingService] once the REST API is available; null while the
     * service is down (no-op). Kept as a plain function reference so [SafBridge] stays
     * decoupled from the REST layer.
     */
    var onForwardedDirChanged: ((folderPath: String) -> Unit)? = null

    /** Snapshot entry of one path in a forwarded/provider tree. */
    data class NodeInfo(val isDir: Boolean, val size: Long = 0, val mtime: Long = 0)

    private val stateType = object : TypeToken<Map<String, NodeInfo>>() {}.type
    private val mappingsType = object : TypeToken<Map<String, String>>() {}.type

    private fun loadMappings(): MutableMap<String, String> {
        val json = prefs.getString(PREF_MAPPINGS, null) ?: return LinkedHashMap()
        return try {
            val parsed: Map<String, String>? = gson.fromJson(json, mappingsType)
            if (parsed != null) LinkedHashMap(parsed) else LinkedHashMap()
        } catch (e: Exception) {
            Log.w(TAG, "loadMappings: Corrupt mapping pref, resetting", e)
            LinkedHashMap()
        }
    }

    private fun saveMappings(mappings: Map<String, String>) {
        // commit() on purpose: callers (register/reauthorize/unregister) must be
        // able to rely on the mapping being readable immediately afterwards.
        prefs.edit().putString(PREF_MAPPINGS, gson.toJson(mappings)).commit()
    }

    private fun loadState(stateKey: String): Map<String, NodeInfo> {
        val json = prefs.getString(PREF_STATE_PREFIX + stateKey, null) ?: return emptyMap()
        return try {
            val parsed: Map<String, NodeInfo>? = gson.fromJson(json, stateType)
            parsed ?: emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "loadState: Corrupt state pref for $stateKey, starting over", e)
            emptyMap()
        }
    }

    private fun saveState(stateKey: String, state: Map<String, NodeInfo>) {
        prefs.edit().putString(PREF_STATE_PREFIX + stateKey, gson.toJson(state)).commit()
    }

    /**
     * True while the persisted snapshot for [stateKey] still matches the current
     * forwarded directory, i.e. mirroring may safely continue where it left off.
     *
     * False when the forwarded dir is missing or no longer holds paths the
     * snapshot records. Such a stale snapshot must be dropped BEFORE the next
     * pass, otherwise the empty forwarded dir is read as "everything was deleted"
     * and those deletions are propagated INTO the provider (wiping the user's
     * data; ".stfolder"-style internal names are filtered and would survive).
     *
     * A snapshot can go stale without being cleared: a forward pass that was
     * still in flight when the folder was removed re-persists its snapshot after
     * unregister cleared the prefs, a crash can interrupt unregister between the
     * pref removal and the directory delete, or a config import can restore a
     * snapshot for a wiped directory.
     */
    private fun snapshotMatchesForwardedDir(folderPath: String, stateKey: String): Boolean {
        val state = loadState(stateKey)
        if (state.isEmpty()) {
            return true
        }
        val dir = File(folderPath)
        if (!dir.isDirectory) {
            return false
        }
        val present = scanForwardedDir(dir)
        return state.keys.all { present.containsKey(it) }
    }

    /**
     * Registers a bridge for [uri] (idempotent) and returns the forwarded folder
     * path that must be stored as folder.path in the Syncthing config.
     */
    fun register(uri: Uri): String {
        val folderPath = File(bridgeRoot, hashOf(uri)).absolutePath
        val mappings = loadMappings()
        if (mappings[folderPath] != uri.toString()) {
            mappings[folderPath] = uri.toString()
            saveMappings(mappings)
        }
        synchronized(bridges) {
            if (!bridges.containsKey(folderPath)) {
                // Re-adding a folder whose forwarded dir is gone/emptied while a
                // stale snapshot survived (e.g. an in-flight pass re-persisted it
                // after the folder removal): diffing against it would read the
                // empty dir as deletions and wipe the provider. Start from a clean
                // snapshot so the provider content is PULLED instead.
                if (!snapshotMatchesForwardedDir(folderPath, hashOf(uri))) {
                    Log.i(TAG, "register: Stale snapshot for [$folderPath], dropping it")
                    prefs.edit().remove(PREF_STATE_PREFIX + hashOf(uri)).commit()
                }
                bridges[folderPath] = Bridge(folderPath, uri)
            }
        }
        if (started) {
            synchronized(bridges) { bridges[folderPath] }?.start()
        }
        return folderPath
    }

    /** Returns true if [folderPath] is a forwarded folder managed by a bridge. */
    fun isForwarded(folderPath: String): Boolean {
        return loadMappings().containsKey(folderPath)
    }

    /** Returns true if [path] lives under the forwarding root (regardless of state). */
    fun isForwardedPath(path: String): Boolean {
        return path.startsWith(bridgeRoot.absolutePath + File.separator)
    }

    /**
     * True if [uri] still carries a persisted read+write grant. Grants are revoked
     * by clearing app data / reinstalling, even though a config import may have
     * restored the mapping itself (the backup includes shared preferences).
     */
    private fun hasUsableGrant(uri: Uri): Boolean {
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    /**
     * True for a folder that is configured to live in the forwarding root but whose
     * bridge is not usable: either the mapping is gone, or the mapping was restored
     * by a config import while the SAF grant was lost (fresh install + import).
     */
    fun needsAuthorization(path: String): Boolean {
        if (!isForwardedPath(path)) {
            return false
        }
        val uriString = loadMappings()[path] ?: return true
        return !hasUsableGrant(Uri.parse(uriString))
    }

    /**
     * Re-creates the bridge for an already-configured forwarded folder after its
     * authorization was lost (fresh install + config import). The folder path is
     * kept EXACTLY as-is so the imported config keeps working without a rewrite.
     */
    fun reauthorize(folderPath: String, uri: Uri) {
        val mappings = loadMappings()
        mappings[folderPath] = uri.toString()
        saveMappings(mappings)
        // Re-authorizing implies a fresh forwarded dir (data wiped / re-install):
        // drop any stale imported snapshot so provider content is PULLED instead of
        // being diffed against it (which could produce bogus provider deletions).
        prefs.edit().remove(PREF_STATE_PREFIX + hashOf(uri)).commit()
        val bridge = synchronized(bridges) {
            bridges.getOrPut(folderPath) { Bridge(folderPath, uri) }
        }
        if (started) {
            bridge.start()
        }
    }

    /**
     * Drops the bridge for [folderPath], removes the persisted mapping/state and
     * deletes the forwarded directory (call when the folder is removed in the UI).
     * Safe to call for non-forwarded paths: they are ignored.
     */
    fun unregister(folderPath: String) {
        val bridge = synchronized(bridges) { bridges.remove(folderPath) }
        bridge?.stop()
        val mappings = loadMappings()
        val uriString = mappings.remove(folderPath)
        if (uriString == null) {
            return
        }
        saveMappings(mappings)
        // commit() (not apply()): an in-flight forward pass may still be running
        // on this bridge (stop() is cooperative, it does not interrupt the pass)
        // and any state write it does afterwards must not land AFTER this removal.
        // The pass itself is additionally gated by Bridge.active, see forwardPass.
        prefs.edit().remove(PREF_STATE_PREFIX + hashOf(Uri.parse(uriString))).commit()
        File(folderPath).deleteRecursively()
    }

    /** Starts the forwarding loops for all persisted mappings (idempotent). */
    fun startAll() {
        if (started) {
            return
        }
        started = true
        bridgeRoot.mkdirs()
        val mappings = loadMappings()
        if (mappings.isEmpty()) {
            Log.i(TAG, "startAll: No forwarded folders registered")
            return
        }
        for ((folderPath, uriString) in mappings) {
            val uri = Uri.parse(uriString)
            if (!hasUsableGrant(uri)) {
                // Grant revoked by clear-data/reinstall; the config import restored
                // the mapping but only re-picking the folder can restore access.
                Log.i(TAG, "startAll: Skipping [$folderPath], SAF grant lost; open the folder to re-authorize")
                continue
            }
            // Fresh or foreign forwarded dir (config imported onto a wiped install,
            // crash interrupted a folder removal, ...): start from an empty snapshot
            // so provider content is PULLED instead of being diffed against a stale
            // snapshot (which could produce bogus provider deletions).
            if (!snapshotMatchesForwardedDir(folderPath, hashOf(uri))) {
                Log.i(TAG, "startAll: Stale snapshot for [$folderPath], dropping it")
                prefs.edit().remove(PREF_STATE_PREFIX + hashOf(uri)).commit()
            }
            val bridge = Bridge(folderPath, uri)
            synchronized(bridges) { bridges[folderPath] = bridge }
            bridge.start()
        }
    }

    fun stopAll() {
        started = false
        synchronized(bridges) {
            bridges.values.forEach { it.stop() }
            bridges.clear()
        }
        scope.cancel()
        // Fresh scope so the singleton instance can be started again by the service.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private inner class Bridge(val folderPath: String, val uri: Uri) {

        private val stateKey = hashOf(uri)
        private val forwardedDir = File(folderPath)
        private val tree: SafTree = DocumentFileSafTree(context, uri)
        private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
        private var emptySafScans = 0
        private var loop: Job? = null

        /**
         * Flipped to false by [stop]; a pass that is already running consults it
         * before persisting the snapshot or nudging the core, so a stopped bridge
         * can never undo unregister's state cleanup.
         */
        @Volatile
        private var active = true

        fun start() {
            if (loop?.isActive == true) {
                return
            }
            loop = scope.launch {
                Log.i(TAG, "start: Forwarding [$folderPath]")
                while (isActive) {
                    try {
                        forwardPass()
                    } catch (e: Exception) {
                        Log.w(TAG, "forwardPass: Failed for [$folderPath]", e)
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
        }

        fun stop() {
            // Cooperative: the pass currently in flight (if any) is not
            // interruptible and runs to completion - only the next poll loop
            // iteration stops. forwardPass consults [active] before persisting
            // anything so a stopped bridge can never re-write a stale snapshot
            // (e.g. after unregister cleared it) or nudge the core.
            active = false
            loop?.cancel()
            loop = null
        }

        suspend fun forwardPass() = kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (!inFlight.compareAndSet(false, true)) {
                return@withContext
            }
            try {
                if (!active) {
                    // Stopped between the last poll iteration and this one.
                    return@withContext
                }
                val saf = tree.scan()
                val fwd = scanForwardedDir(forwardedDir)
                val last = loadState(stateKey)

                // A single failed/empty provider scan (transient provider error) must
                // never wipe the whole tree; require two consecutive empty scans
                // before accepting a genuinely emptied provider.
                if (saf.isEmpty() && last.isNotEmpty()) {
                    emptySafScans++
                    if (emptySafScans < 2) {
                        Log.w(
                            TAG, "forwardPass: [$stateKey] Provider scan empty while " +
                                "snapshot holds ${last.size} entries; skipping this pass"
                        )
                        return@withContext
                    }
                    Log.w(TAG, "forwardPass: [$stateKey] Provider empty $emptySafScans x in a row; accepting deletions")
                } else {
                    emptySafScans = 0
                }

                val plan = MirrorMerge.plan(saf, fwd, last)
                val appliedFwd = applyToForwardedDir(plan, tree)
                val appliedSaf = applyToSaf(plan)
                // Only operations that actually succeeded advance the snapshot;
                // failures are retried next pass (see MirrorMerge.verifiedResult).
                // Never persist when the bridge was stopped mid-pass (folder
                // removed): writing now would resurrect the snapshot that
                // unregister just cleared and turn the next re-add into a
                // provider-wide deletion.
                if (!active) {
                    Log.i(TAG, "forwardPass: [$stateKey] Bridge stopped mid-pass, not persisting state")
                    return@withContext
                }
                saveState(stateKey, MirrorMerge.verifiedResult(plan, appliedFwd, appliedSaf))
                if (plan.hasWork()) {
                    Log.i(TAG, "forwardPass: [$stateKey] ${plan.summary()}")
                }
                // The core cannot see the forwarded dir change on its own (no SAF
                // change notifications; fs watcher on the app-private dir is not
                // guaranteed) - nudge it to rescan this folder now instead of
                // waiting up to rescanIntervalS.
                if (appliedFwd.isNotEmpty()) {
                    onForwardedDirChanged?.invoke(folderPath)
                }
            } finally {
                inFlight.set(false)
            }
        }

        /**
         * Applies the provider->forwarded-dir part of [plan]; paths in the returned
         * set reached the target state and may advance the snapshot. Plan paths are
         * RELATIVE to the forwarded dir.
         */
        private fun applyToForwardedDir(plan: MirrorMerge.Plan, tree: SafTree): Set<String> {
            val applied = HashSet<String>()
            for (path in plan.deleteInForwarded) {
                if (File(forwardedDir, path).deleteRecursively()) {
                    applied.add(path)
                } else {
                    Log.w(TAG, "applyToForwardedDir: Failed to delete $path")
                }
            }
            for (path in plan.makeDirsInForwarded) {
                val dir = File(forwardedDir, path)
                if (dir.mkdirs() || dir.isDirectory) {
                    applied.add(path)
                } else {
                    Log.w(TAG, "applyToForwardedDir: Failed to create dir $path")
                }
            }
            val tempDir = File(bridgeRoot, stateKey + ".tmp")
            tempDir.mkdirs()
            for ((path, info) in plan.copyToForwarded) {
                val target = File(forwardedDir, path)
                target.parentFile?.mkdirs()
                if (target.exists()) {
                    target.delete()
                }
                val stream = tree.open(path)
                if (stream == null) {
                    Log.w(TAG, "applyToForwardedDir: Provider stream unavailable for $path")
                    continue
                }
                try {
                    val temp = File.createTempFile("fwd", ".part", tempDir)
                    stream.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    // A partially written file must never be renamed into place.
                    if (temp.length() != info.size) {
                        Log.w(TAG, "applyToForwardedDir: Size mismatch after copy of $path")
                        temp.delete()
                        continue
                    }
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                    if (info.mtime > 0) {
                        target.setLastModified(info.mtime)
                    }
                    if (target.length() == info.size) {
                        applied.add(path)
                    } else {
                        Log.w(TAG, "applyToForwardedDir: Verification failed for $path")
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "applyToForwardedDir: Failed to forward $path", e)
                }
            }
            tempDir.deleteRecursively()
            return applied
        }

        /**
         * Applies the forwarded-dir->provider part of [plan]; paths in the returned
         * set reached the target state on the provider side.
         */
        private fun applyToSaf(plan: MirrorMerge.Plan): Set<String> {
            val applied = HashSet<String>()
            for (path in plan.deleteInSaf) {
                if (tree.delete(path)) {
                    applied.add(path)
                } else {
                    Log.w(TAG, "applyToSaf: Failed to delete $path")
                }
            }
            for (path in plan.makeDirsInSaf) {
                if (tree.createDir(path)) {
                    applied.add(path)
                } else {
                    Log.w(TAG, "applyToSaf: Failed to create dir $path")
                }
            }
            for (path in plan.copyToSaf) {
                val source = File(forwardedDir, path)
                if (!source.isFile) {
                    continue
                }
                try {
                    source.inputStream().use { input ->
                        if (tree.writeFile(path, input)) {
                            applied.add(path)
                        } else {
                            Log.w(TAG, "applyToSaf: Failed to write $path")
                        }
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "applyToSaf: Failed to forward $path", e)
                }
            }
            return applied
        }
    }
}


/**
 * Pure three-way merge between the provider tree ("saf") and the forwarded dir
 * ("fwd") against the last-forwarded snapshot ("last"). No Android dependencies,
 * so it can be unit-tested directly.
 *
 * Per-path decision:
 *  - changed on one side only  -> apply that side to the other;
 *  - deleted on one side only  -> propagate the deletion;
 *  - changed/deleted on both   -> the forwarded-dir side wins (its content is what
 *    the core already propagated); logged via [Plan.summary].
 */
internal object MirrorMerge {

    class Plan(
        val deleteInForwarded: List<String>,
        val makeDirsInForwarded: List<String>,
        val copyToForwarded: List<Pair<String, SafBridge.NodeInfo>>,
        val deleteInSaf: List<String>,
        val makeDirsInSaf: List<String>,
        val copyToSaf: List<String>,
        val result: Map<String, SafBridge.NodeInfo>,
    ) {
        fun hasWork(): Boolean {
            return deleteInForwarded.isNotEmpty() || makeDirsInForwarded.isNotEmpty() ||
                copyToForwarded.isNotEmpty() || deleteInSaf.isNotEmpty() ||
                makeDirsInSaf.isNotEmpty() || copyToSaf.isNotEmpty()
        }

        fun summary(): String {
            return "toFwd[-%d +dirs %d +files %d] toSaf[-%d +dirs %d +files %d]".format(
                deleteInForwarded.size, makeDirsInForwarded.size, copyToForwarded.size,
                deleteInSaf.size, makeDirsInSaf.size, copyToSaf.size
            )
        }
    }

    /**
     * Narrows [Plan.result] down to the entries whose required operations ACTUALLY
     * succeeded. Failed operations are left out of the snapshot, so the next pass
     * sees them as "provider changed" again and RETRIES - they must never be
     * mistaken for deletions on the forwarded side (which would propagate
     * deletions into the provider).
     */
    fun verifiedResult(
        plan: Plan,
        appliedFwd: Set<String>,
        appliedSaf: Set<String>
    ): Map<String, SafBridge.NodeInfo> {
        val result = LinkedHashMap<String, SafBridge.NodeInfo>()
        for ((path, target) in plan.result) {
            val touchedFwd = plan.deleteInForwarded.contains(path) ||
                plan.makeDirsInForwarded.contains(path) ||
                plan.copyToForwarded.any { it.first == path }
            val touchedSaf = plan.deleteInSaf.contains(path) ||
                plan.makeDirsInSaf.contains(path) ||
                plan.copyToSaf.contains(path)
            if ((!touchedFwd || appliedFwd.contains(path)) &&
                (!touchedSaf || appliedSaf.contains(path))
            ) {
                result[path] = target
            }
        }
        return result
    }

    private fun depth(path: String): Int = path.count { it == '/' }

    /**
     * Node equality: directories compare by type only (dir mtimes are not preserved
     * across providers); files compare by size and mtime, tolerating a missing
     * (zero) mtime on either side to avoid rewrite oscillation.
     */
    private fun sameNode(a: SafBridge.NodeInfo?, b: SafBridge.NodeInfo?): Boolean {
        if (a == null || b == null) {
            return a === b
        }
        if (a.isDir != b.isDir) {
            return false
        }
        if (a.isDir) {
            return true
        }
        return a.size == b.size &&
            (a.mtime == b.mtime || a.mtime == 0L || b.mtime == 0L)
    }

    fun plan(
        saf: Map<String, SafBridge.NodeInfo>,
        fwd: Map<String, SafBridge.NodeInfo>,
        last: Map<String, SafBridge.NodeInfo>
    ): Plan {
        val allPaths = (saf.keys + fwd.keys + last.keys).distinct()
            .sortedWith(compareBy({ depth(it) }, { it }))

        val deleteInForwarded = ArrayList<String>()
        val makeDirsInForwarded = ArrayList<String>()
        val copyToForwarded = ArrayList<Pair<String, SafBridge.NodeInfo>>()
        val deleteInSaf = ArrayList<String>()
        val makeDirsInSaf = ArrayList<String>()
        val copyToSaf = ArrayList<String>()
        val result = LinkedHashMap<String, SafBridge.NodeInfo>()

        // Actions are driven by WHICH side changed, never by comparing the two
        // current sides (their mtimes can legitimately differ; provider mtimes are
        // unreliable). When pushing to the provider we cannot know the resulting
        // provider mtime, so the snapshot records 0 for pushed files - the tolerant
        // comparison then treats any provider mtime as "unchanged" on the next pass.
        for (path in allPaths) {
            val s = saf[path]
            val f = fwd[path]
            val l = last[path]
            val safChanged = !sameNode(s, l)
            val fwdChanged = !sameNode(f, l)

            when {
                !safChanged && !fwdChanged -> {
                    if (s != null) {
                        result[path] = s
                    }
                }
                safChanged && !fwdChanged -> {
                    // Provider side is the source of truth: bring the forwarded dir over.
                    when {
                        s == null -> deleteInForwarded.add(path)
                        s.isDir -> makeDirsInForwarded.add(path)
                        else -> copyToForwarded.add(path to s)
                    }
                    if (s != null) {
                        result[path] = s
                    }
                }
                !safChanged && fwdChanged -> {
                    // Forwarded dir changed (core synced it): push to the provider.
                    if (f == null) {
                        deleteInSaf.add(path)
                    } else if (f.isDir) {
                        makeDirsInSaf.add(path)
                        result[path] = f
                    } else {
                        copyToSaf.add(path)
                        result[path] = SafBridge.NodeInfo(isDir = false, size = f.size)
                    }
                }
                else -> {
                    // Both sides differ from the snapshot: forwarded dir wins.
                    if (f == null) {
                        deleteInSaf.add(path)
                    } else if (f.isDir) {
                        makeDirsInSaf.add(path)
                        result[path] = f
                    } else {
                        copyToSaf.add(path)
                        result[path] = SafBridge.NodeInfo(isDir = false, size = f.size)
                    }
                }
            }
        }

        return Plan(
            deleteInForwarded = deleteInForwarded.sortedByDescending { depth(it) },
            makeDirsInForwarded = makeDirsInForwarded,
            copyToForwarded = copyToForwarded,
            deleteInSaf = deleteInSaf.sortedByDescending { depth(it) },
            makeDirsInSaf = makeDirsInSaf,
            copyToSaf = copyToSaf,
            result = result
        )
    }
}

/**
 * Read/write abstraction over a SAF tree, so the forwarding engine can be tested
 * without Android. Paths are '/'-joined names relative to the tree root.
 */
internal interface SafTree {

    /**
     * Recursive listing. Dirs are normalized to NodeInfo(isDir=true); files carry
     * size/mtime. Throws on provider failures so callers skip the pass instead of
     * misreading a transient error as "everything was deleted".
     */
    fun scan(): Map<String, SafBridge.NodeInfo>

    /** Opens the file at [path] for reading, or null if unavailable. */
    fun open(path: String): InputStream?

    /** Creates the directory at [path] including parents; true on success. */
    fun createDir(path: String): Boolean

    /** Creates or overwrites the file at [path] with the stream content. */
    fun writeFile(path: String, data: InputStream): Boolean

    /** Deletes the file or directory (recursively); true on success. */
    fun delete(path: String): Boolean
}

/**
 * [SafTree] backed by a DocumentsProvider tree via [DocumentFile]. Provider calls
 * are not cached: forwarded trees are small (config/data files) and correctness
 * beats speed here.
 */
internal class DocumentFileSafTree(private val context: Context, treeUri: Uri) : SafTree {

    companion object {

        private const val TAG = "SafBridge"
    }

    private val root: DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

    private fun resolve(path: String): DocumentFile? {
        var dir = root ?: return null
        var node: DocumentFile? = null
        for (segment in path.split('/')) {
            node = dir.findFile(segment) ?: return null
            dir = node
        }
        return node
    }

    private fun resolveParent(path: String): DocumentFile? {
        val index = path.lastIndexOf('/')
        return if (index <= 0) {
            root
        } else {
            resolve(path.substring(0, index))
        }
    }

    private fun ensureDir(path: String): DocumentFile? {
        var dir = root ?: return null
        for (segment in path.split('/')) {
            val existing = dir.findFile(segment)
            dir = when {
                existing != null && existing.isDirectory -> existing
                existing == null -> dir.createDirectory(segment) ?: return null
                else -> return null // A file blocks the directory we need.
            } ?: return null
        }
        return dir
    }

    override fun scan(): Map<String, SafBridge.NodeInfo> {
        val rootNode = root ?: throw IOException("Tree uri unavailable")
        val result = LinkedHashMap<String, SafBridge.NodeInfo>()
        fun walk(dir: DocumentFile, prefix: String) {
            val expected = childCount(dir)
                ?: throw IOException("Provider query failed for ${dir.uri}")
            val children = dir.listFiles()
            if (children.size != expected) {
                // listFiles() swallows query failures into an empty array; never
                // mistake that for a genuinely emptied provider directory.
                throw IOException("Provider returned ${children.size}/$expected children for ${dir.uri}")
            }
            for (child in children) {
                val name = child.name ?: continue
                if (isSyncthingInternalName(name)) {
                    continue
                }
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) {
                    result[path] = SafBridge.NodeInfo(isDir = true)
                    walk(child, path)
                } else {
                    result[path] = SafBridge.NodeInfo(
                        isDir = false,
                        size = child.length(),
                        mtime = child.lastModified()
                    )
                }
            }
        }
        walk(rootNode, "")
        return result
    }

    /**
     * Row count of the directory query, or null when the query FAILED.
     * [DocumentFile.listFiles] hides query failures behind empty arrays.
     */
    private fun childCount(dir: DocumentFile): Int? {
        return try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                dir.uri, DocumentsContract.getDocumentId(dir.uri)
            )
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null
            )?.use { it.count }
        } catch (e: Exception) {
            Log.w(TAG, "childCount: query failed for ${dir.uri}", e)
            null
        }
    }

    override fun open(path: String): InputStream? {
        return try {
            val node = resolve(path) ?: return null
            context.contentResolver.openInputStream(node.uri)
        } catch (e: Exception) {
            Log.w(TAG, "open: Failed to open $path", e)
            null
        }
    }

    override fun createDir(path: String): Boolean {
        return try {
            ensureDir(path) != null
        } catch (e: Exception) {
            Log.w(TAG, "createDir: Failed to create $path", e)
            false
        }
    }

    override fun writeFile(path: String, data: InputStream): Boolean {
        return try {
            val parentPath = path.substringBeforeLast('/', "")
            val parent = if (parentPath.isEmpty()) root else ensureDir(parentPath)
            val name = path.substringAfterLast('/')
            if (parent == null) {
                return false
            }
            val existing = parent.findFile(name)
            val extension = name.substringAfterLast('.', "")
            val mimeType = if (extension.isEmpty()) {
                "application/octet-stream"
            } else {
                FileUtils.getMimeTypeFromFileExtension(extension).ifEmpty { "application/octet-stream" }
            }
            val target = existing ?: parent.createFile(mimeType, name) ?: return false
            context.contentResolver.openOutputStream(target.uri)?.use { output ->
                data.copyTo(output)
                output.flush()
            } ?: return false
            true
        } catch (e: Exception) {
            Log.w(TAG, "writeFile: Failed to write $path", e)
            false
        }
    }

    override fun delete(path: String): Boolean {
        return try {
            resolve(path)?.delete() ?: true // Already gone counts as deleted.
        } catch (e: Exception) {
            Log.w(TAG, "delete: Failed to delete $path", e)
            false
        }
    }
}

