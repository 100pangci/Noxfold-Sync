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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
internal fun scanForwardedDir(dir: File): Map<String, SafBridge.NodeInfo> {
    return scanForwardedSubtree(dir, "")
}

internal fun scanForwardedSubtree(
    root: File,
    subtree: String,
): Map<String, SafBridge.NodeInfo> {
    val result = LinkedHashMap<String, SafBridge.NodeInfo>()
    val dir = if (subtree.isEmpty()) root else File(root, subtree)
    if (!dir.isDirectory) {
        throw IOException("Forwarded subtree unavailable for ${dir.absolutePath}")
    }
    if (subtree.isNotEmpty()) {
        result[subtree] = SafBridge.NodeInfo(isDir = true)
    }
    fun walk(current: File, prefix: String) {
        val entries = current.listFiles()
            ?: throw IOException("Forwarded directory query failed for ${current.absolutePath}")
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
    walk(dir, subtree)
    return result
}

/**
 * Forwards "special" folders that have no real filesystem path (DocumentsProvider
 * roots exposed by other apps, e.g. fcitx5-android's data root) into the Syncthing
 * core.
 *
 * The core only understands real paths, and provider content is only reachable
 * through content URIs, so [SafBridge] keeps the forwarded folder in sync with the
 * provider. The forwarded directory is the Syncthing working tree and is maintained as a
 * mirror of the provider tree; no additional staging copy is kept:
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
 *  - SAF does not provide reliable, precise recursive filesystem notifications comparable to
 *    inotify. Provider observers are treated as change hints, while reconciliation remains the
 *    source of truth; a periodic full reconciliation is always retained as a fallback.
 */
class SafBridge(private val context: Context) {

    companion object {

        private const val TAG = "SafBridge"

        /** Pref holding the JSON map "forwarded dir absolute path" -> "tree uri". */
        private const val PREF_MAPPINGS = "saf_bridge_mappings"

        /** Directory (inside the app's files dir) holding all forwarded folders. */
        private const val BRIDGE_ROOT_NAME = "saf-bridge"

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
    private val snapshotStore: SnapshotStore = SharedPreferencesSnapshotStore(prefs, gson)
    private val bridgeRoot = File(context.filesDir, BRIDGE_ROOT_NAME)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private val bridges = LinkedHashMap<String, Bridge>()
    private var nextGeneration = 0L
    @Volatile
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
    data class NodeInfo(
        val isDir: Boolean,
        val size: Long = 0,
        val mtime: Long = 0,
        val contentHash: String? = null,
    )

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

    private fun commitMappingsAndRemoveStates(
        mappings: Map<String, String>,
        stateKeys: Set<String>,
    ) {
        // Mapping and state cleanup must be one preference transaction. A bridge generation
        // still in flight is additionally checked under [lifecycleLock] before it can commit.
        val editor = prefs.edit().putString(PREF_MAPPINGS, gson.toJson(mappings))
        stateKeys.forEach { editor.remove(SharedPreferencesSnapshotStore.STATE_PREFIX + it) }
        editor.commit()
    }

    private fun loadState(stateKey: String): Map<String, NodeInfo> {
        return snapshotStore.loadBridge(stateKey)
    }

    private fun saveState(stateKey: String, state: Map<String, NodeInfo>) {
        if (!snapshotStore.replaceFullSnapshot(stateKey, state)) {
            throw IOException("Could not commit snapshot for $stateKey")
        }
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
    private enum class SnapshotMatch {
        MATCH,
        STALE,
        UNKNOWN,
    }

    private fun snapshotMatchesForwardedDir(folderPath: String, stateKey: String): SnapshotMatch {
        return try {
            val state = loadState(stateKey)
            if (state.isEmpty()) {
                return SnapshotMatch.MATCH
            }
            val dir = File(folderPath)
            if (!dir.isDirectory) {
                return SnapshotMatch.STALE
            }
            val present = scanForwardedDir(dir)
            if (state.keys.all { present.containsKey(it) }) {
                SnapshotMatch.MATCH
            } else {
                SnapshotMatch.STALE
            }
        } catch (e: IOException) {
            Log.w(TAG, "snapshotMatchesForwardedDir: Forwarded scan unavailable for [$folderPath]", e)
            SnapshotMatch.UNKNOWN
        }
    }

    /**
     * Registers a bridge for [uri] (idempotent) and returns the forwarded folder
     * path that must be stored as folder.path in the Syncthing config.
     */
    fun register(uri: Uri): String {
        val folderPath = File(bridgeRoot, hashOf(uri)).absolutePath
        val bridgeToStart: Bridge?
        synchronized(lifecycleLock) {
            val mappings = loadMappings()
            if (mappings[folderPath] != uri.toString()) {
                mappings[folderPath] = uri.toString()
                saveMappings(mappings)
            }
            val bridge = bridges[folderPath] ?: run {
                var providerFirstBootstrap = false
                // Re-adding a folder whose forwarded dir is gone/emptied while a
                // stale snapshot survived (e.g. an in-flight pass re-persisted it
                // after the folder removal): diffing against it would read the
                // empty dir as deletions and wipe the provider. Start from a clean
                // snapshot so the provider content is PULLED instead.
                when (snapshotMatchesForwardedDir(folderPath, hashOf(uri))) {
                    SnapshotMatch.STALE -> {
                        Log.i(TAG, "register: Stale snapshot for [$folderPath], dropping it")
                        snapshotStore.clearBridge(hashOf(uri))
                        providerFirstBootstrap = true
                    }
                    SnapshotMatch.UNKNOWN -> {
                        Log.w(TAG, "register: Cannot validate snapshot for [$folderPath], keeping it")
                    }
                    SnapshotMatch.MATCH -> Unit
                }
                Bridge(
                    folderPath,
                    uri,
                    ++nextGeneration,
                    providerFirstBootstrap,
                ).also { bridges[folderPath] = it }
            }
            bridgeToStart = if (started) bridge else null
        }
        bridgeToStart?.start()
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
        val bridgeToStart: Bridge?
        synchronized(lifecycleLock) {
            val mappings = loadMappings()
            val oldBridge = bridges.remove(folderPath)
            val oldUri = oldBridge?.uri ?: mappings[folderPath]?.let(Uri::parse)
            oldBridge?.stop()
            mappings[folderPath] = uri.toString()
            // Re-authorizing implies a fresh forwarded dir (data wiped / re-install):
            // drop stale state for both the previous and new URI. The old in-memory bridge
            // is replaced so it can never continue reading the previous provider tree.
            val stateKeys = buildSet {
                oldUri?.let { add(hashOf(it)) }
                add(hashOf(uri))
            }
            commitMappingsAndRemoveStates(mappings, stateKeys)
            val bridge = Bridge(folderPath, uri, ++nextGeneration, providerFirstBootstrap = true)
            bridges[folderPath] = bridge
            bridgeToStart = if (started) bridge else null
        }
        bridgeToStart?.start()
    }

    /**
     * Drops the bridge for [folderPath], removes the persisted mapping/state and
     * deletes the forwarded directory (call when the folder is removed in the UI).
     * Safe to call for non-forwarded paths: they are ignored.
     */
    fun unregister(folderPath: String) {
        synchronized(lifecycleLock) {
            val bridge = bridges.remove(folderPath)
            val mappings = loadMappings()
            val uriString = mappings.remove(folderPath)
            if (bridge == null && uriString == null) {
                return
            }
            // Invalidate before clearing persisted state. Any in-flight pass that reaches its
            // commit gate after this point fails the generation check.
            bridge?.stop()
            val stateKeys = buildSet {
                bridge?.uri?.let { add(hashOf(it)) }
                uriString?.let { add(hashOf(Uri.parse(it))) }
            }
            commitMappingsAndRemoveStates(mappings, stateKeys)
        }
        File(folderPath).deleteRecursively()
    }

    /** Starts the forwarding loops for all persisted mappings (idempotent). */
    fun startAll() {
        val bridgesToStart: List<Bridge>
        synchronized(lifecycleLock) {
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
            val staleStateKeys = LinkedHashSet<String>()
            val created = ArrayList<Bridge>()
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
                val providerFirstBootstrap = when (snapshotMatchesForwardedDir(folderPath, hashOf(uri))) {
                    SnapshotMatch.STALE -> {
                        Log.i(TAG, "startAll: Stale snapshot for [$folderPath], dropping it")
                        staleStateKeys.add(hashOf(uri))
                        true
                    }
                    SnapshotMatch.UNKNOWN -> {
                        Log.w(TAG, "startAll: Cannot validate snapshot for [$folderPath], keeping it")
                        false
                    }
                    SnapshotMatch.MATCH -> false
                }
                val bridge = Bridge(
                    folderPath,
                    uri,
                    ++nextGeneration,
                    providerFirstBootstrap,
                )
                bridges[folderPath] = bridge
                created.add(bridge)
            }
            if (staleStateKeys.isNotEmpty()) {
                commitMappingsAndRemoveStates(mappings, staleStateKeys)
            }
            bridgesToStart = created
        }
        bridgesToStart.forEach { it.start() }
    }

    fun stopAll() {
        synchronized(lifecycleLock) {
            started = false
            bridges.values.forEach { it.stop() }
            bridges.clear()
            scope.cancel()
            // Fresh scope so the singleton instance can be started again by the service.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
    }

    /** Stops active sessions before an on-disk config/preferences replacement. */
    fun pauseForConfigImport(): Boolean {
        val wasStarted = synchronized(lifecycleLock) { started }
        stopAll()
        return wasStarted
    }

    /** Re-reads mappings and snapshots after a config/preferences replacement. */
    fun resumeAfterConfigImport(wasStarted: Boolean) {
        if (wasStarted) {
            startAll()
        }
    }

    private fun isCurrent(bridge: Bridge): Boolean {
        synchronized(lifecycleLock) {
            return isCurrentLocked(bridge)
        }
    }

    private fun isCurrentLocked(bridge: Bridge): Boolean {
        return started && bridges[bridge.folderPath]?.generation == bridge.generation &&
            bridges[bridge.folderPath] === bridge && bridge.active &&
            bridge.generationGate.isCurrent(bridge.generation)
    }

    private data class ScopeScan(
        val scope: String,
        val saf: Map<String, NodeInfo>,
        val forwarded: Map<String, NodeInfo>,
    )

    private inner class Bridge(
        val folderPath: String,
        val uri: Uri,
        val generation: Long,
        providerFirstBootstrap: Boolean,
    ) {

        private val stateKey = hashOf(uri)
        private val forwardedDir = File(folderPath)
        private val tree: SafTree = DocumentFileSafTree(context, uri)
        private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
        private var emptySafScans = 0
        val generationGate = GenerationGate(generation)
        private val dirtyPaths = DirtyPathTracker()
        @Volatile
        private var providerFirstBootstrap = providerFirstBootstrap
        private val startStopLock = Any()
        private val providerObserver = ProviderObserver(context.contentResolver, uri) {
            dirtyPaths.markSafRoot()
            scheduler?.requestReconcile()
        }
        private val forwardedObserver = ForwardedTreeObserver(
            root = forwardedDir,
            onChangeHint = { path ->
                dirtyPaths.markForwardedPath(path)
                scheduler?.requestReconcile()
            },
        )
        @Volatile
        private var scheduler: ReconcileScheduler? = null

        /**
         * Flipped to false by [stop]; a pass that is already running consults it
         * before persisting the snapshot or nudging the core, so a stopped bridge
         * can never undo unregister's state cleanup.
         */
        @Volatile
        var active = true

        fun start() {
            synchronized(startStopLock) {
                if (!active || scheduler != null) {
                    return
                }
                forwardedDir.mkdirs()
                try {
                    providerObserver.start()
                } catch (e: Exception) {
                    // A provider may reject observer registration. The scheduler still starts
                    // and the periodic full reconciliation remains sufficient for correctness.
                    Log.w(TAG, "start: Provider observer unavailable for [$folderPath]", e)
                }
                forwardedObserver.start()
                val newScheduler = ReconcileScheduler(
                    scope = scope,
                    reconcile = { full -> forwardPass(full) },
                    onFailure = { e ->
                        Log.w(TAG, "reconcile: Failed for [$folderPath]", e)
                    },
                )
                scheduler = newScheduler
                Log.i(TAG, "start: Forwarding [$folderPath], generation=$generation")
                newScheduler.start()
            }
        }

        fun stop() {
            val currentScheduler: ReconcileScheduler?
            synchronized(startStopLock) {
                // Cooperative: a blocking provider/file operation already in progress may
                // finish, but generation checks prevent it from committing state afterwards.
                active = false
                generationGate.invalidate()
                currentScheduler = scheduler
                scheduler = null
            }
            providerObserver.stop()
            forwardedObserver.stop()
            currentScheduler?.stop()
        }

        suspend fun forwardPass(full: Boolean) = kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (!inFlight.compareAndSet(false, true)) {
                return@withContext
            }
            val requested = dirtyPaths.take()
            try {
                if (!isCurrent(this@Bridge)) {
                    // Stopped between the last poll iteration and this one.
                    return@withContext
                }
                val last = loadState(stateKey)
                val requestedScopes = if (full || requested.full) {
                    listOf("")
                } else {
                    coalesceScopes(requested.roots)
                }
                if (requestedScopes.isEmpty()) {
                    return@withContext
                }

                val scans = try {
                    scanScopes(requestedScopes, last)
                } catch (e: IOException) {
                    if (requestedScopes.size == 1 && requestedScopes[0].isEmpty()) {
                        throw e
                    }
                    // A subtree may have been created/deleted between the observer event and
                    // this scan. Re-read the root rather than guessing that the subtree is empty.
                    Log.i(TAG, "forwardPass: [$stateKey] Subtree scan unavailable, retrying full scan", e)
                    scanScopes(listOf(""), last)
                }
                if (!isCurrent(this@Bridge)) {
                    return@withContext
                }

                // A single failed/empty provider scan (transient provider error) must
                // never wipe the whole tree; require two consecutive empty scans
                // before accepting a genuinely emptied provider.
                val providerLooksEmpty = scans.any { scan ->
                    scan.saf.isEmpty() && (
                        entriesInScope(last, scan.scope).isNotEmpty() ||
                            (providerFirstBootstrap && scan.forwarded.isNotEmpty())
                        )
                }
                if (providerLooksEmpty) {
                    emptySafScans++
                    if (emptySafScans < 2) {
                        dirtyPaths.restore(requested.copy(full = full || requested.full))
                        scheduler?.requestReconcile()
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

                val updated = LinkedHashMap(last)
                var appliedForwardedAny = false
                var hasWork = false
                val summaries = ArrayList<String>()
                for (scan in scans) {
                    if (!isCurrent(this@Bridge)) {
                        return@withContext
                    }
                    val lastScope = entriesInScope(last, scan.scope)
                    // A reauthorization, fresh install, or stale baseline must restore provider
                    // content first. Treat the currently forwarded tree as the comparison
                    // baseline for this bootstrap pass, so stale forwarded files cannot win a
                    // conflict against the freshly authorized provider tree.
                    val mergeBaseline = if (providerFirstBootstrap) {
                        scan.forwarded
                    } else {
                        lastScope
                    }
                    val plan = MirrorMerge.plan(scan.saf, scan.forwarded, mergeBaseline)
                    val appliedFwd = applyToForwardedDir(plan, tree)
                    val appliedSaf = applyToSaf(plan)
                    val verified = MirrorMerge.verifiedResult(plan, appliedFwd, appliedSaf, mergeBaseline)
                    replaceScope(updated, scan.scope, verified)
                    appliedForwardedAny = appliedForwardedAny || appliedFwd.isNotEmpty()
                    hasWork = hasWork || plan.hasWork()
                    if (plan.hasWork()) {
                        summaries.add(plan.summary())
                    }
                }
                // Only operations that actually succeeded advance the snapshot;
                // failures are retried next pass (see MirrorMerge.verifiedResult).
                // Never persist when the bridge was stopped mid-pass (folder
                // removed): writing now would resurrect the snapshot that
                // unregister just cleared and turn the next re-add into a
                // provider-wide deletion.
                if (!isCurrent(this@Bridge)) {
                    Log.i(TAG, "forwardPass: [$stateKey] Bridge stopped mid-pass, not persisting state")
                    return@withContext
                }
                val committed = synchronized(lifecycleLock) {
                    if (!isCurrentLocked(this@Bridge)) {
                        false
                    } else {
                        generationGate.commitIfCurrent(generation) {
                            saveState(stateKey, updated)
                            providerFirstBootstrap = false
                        }
                    }
                }
                if (!committed) {
                    Log.i(TAG, "forwardPass: [$stateKey] Generation invalid before commit")
                    return@withContext
                }
                if (hasWork) {
                    Log.i(TAG, "forwardPass: [$stateKey] ${summaries.joinToString("; ")}")
                }
                // The core cannot be assumed to observe the app-private working tree. Nudge it
                // after a committed forwarded change instead of waiting for its scan interval.
                if (appliedForwardedAny && isCurrent(this@Bridge)) {
                    onForwardedDirChanged?.invoke(folderPath)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isCurrent(this@Bridge)) {
                    // An unknown scan or apply failure leaves the invalidation pending. The
                    // periodic full fallback will retry even if the provider sends no event.
                    dirtyPaths.restore(requested.copy(full = full || requested.full))
                }
                throw e
            } finally {
                inFlight.set(false)
            }
        }

        private fun scanScopes(
            scopes: List<String>,
            last: Map<String, NodeInfo>,
        ): List<ScopeScan> {
            return scopes.map { scope ->
                val safMetadata = if (scope.isEmpty()) tree.scan() else tree.scanSubtree(scope)
                val forwardedMetadata = scanForwardedSubtree(forwardedDir, scope)
                val (saf, forwarded) = addContentHashes(safMetadata, forwardedMetadata, last)
                ScopeScan(scope, saf, forwarded)
            }
        }

        private fun addContentHashes(
            safMetadata: Map<String, NodeInfo>,
            forwardedMetadata: Map<String, NodeInfo>,
            last: Map<String, NodeInfo>,
        ): Pair<Map<String, NodeInfo>, Map<String, NodeInfo>> {
            val saf = LinkedHashMap(safMetadata)
            val forwarded = LinkedHashMap(forwardedMetadata)
            val paths = saf.keys + forwarded.keys + last.keys
            for (path in paths) {
                val safNode = saf[path]
                val forwardedNode = forwarded[path]
                val baseline = last[path]
                if (!needsContentHash(safNode, baseline) &&
                    !needsContentHash(forwardedNode, baseline)
                ) {
                    continue
                }
                if (safNode?.isDir == false && safNode.contentHash == null) {
                    val hash = tree.contentHash(path)
                        ?: throw IOException("Provider content unavailable for hashing: $path")
                    saf[path] = safNode.copy(contentHash = hash)
                }
                if (forwardedNode?.isDir == false && forwardedNode.contentHash == null) {
                    forwarded[path] = forwardedNode.copy(
                        contentHash = ContentHasher.sha256(File(forwardedDir, path))
                    )
                }
            }
            return saf to forwarded
        }

        private fun needsContentHash(
            current: NodeInfo?,
            baseline: NodeInfo?,
        ): Boolean {
            if (current?.isDir != false) {
                return false
            }
            if (baseline == null) {
                return current.mtime == 0L
            }
            return !baseline.isDir && current.size == baseline.size &&
                (current.mtime == 0L || baseline.mtime == 0L)
        }

        private fun coalesceScopes(scopes: Set<String>): List<String> {
            val result = ArrayList<String>()
            for (scope in scopes.sortedWith(compareBy({ it.count { ch -> ch == '/' } }, { it }))) {
                if (result.any { it.isEmpty() || scope == it || scope.startsWith("$it/") }) {
                    continue
                }
                result.removeAll { it.startsWith("$scope/") }
                result.add(scope)
            }
            return result
        }

        private fun entriesInScope(
            entries: Map<String, NodeInfo>,
            scope: String,
        ): Map<String, NodeInfo> {
            if (scope.isEmpty()) {
                return entries
            }
            return entries.filterKeys { it == scope || it.startsWith("$scope/") }
        }

        private fun replaceScope(
            target: MutableMap<String, NodeInfo>,
            scope: String,
            replacement: Map<String, NodeInfo>,
        ) {
            if (scope.isEmpty()) {
                target.clear()
            } else {
                target.keys.removeAll { it == scope || it.startsWith("$scope/") }
            }
            target.putAll(replacement)
        }

        /**
         * Applies the provider->forwarded-dir part of [plan]; paths in the returned
         * set reached the target state and may advance the snapshot. Plan paths are
         * RELATIVE to the forwarded dir.
         */
        private fun applyToForwardedDir(plan: MirrorMerge.Plan, tree: SafTree): Set<String> {
            val applied = HashSet<String>()
            for (path in plan.deleteInForwarded) {
                if (!isCurrent(this@Bridge)) {
                    return applied
                }
                if (File(forwardedDir, path).deleteRecursively()) {
                    applied.add(path)
                } else {
                    Log.w(TAG, "applyToForwardedDir: Failed to delete $path")
                }
            }
            for (path in plan.makeDirsInForwarded) {
                if (!isCurrent(this@Bridge)) {
                    return applied
                }
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
                if (!isCurrent(this@Bridge)) {
                    break
                }
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
                    if (!isCurrent(this@Bridge)) {
                        temp.delete()
                        break
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
                if (!isCurrent(this@Bridge)) {
                    return applied
                }
                if (tree.delete(path)) {
                    applied.add(path)
                } else {
                    Log.w(TAG, "applyToSaf: Failed to delete $path")
                }
            }
            for (path in plan.makeDirsInSaf) {
                if (!isCurrent(this@Bridge)) {
                    return applied
                }
                if (tree.createDir(path)) {
                    applied.add(path)
                } else {
                    Log.w(TAG, "applyToSaf: Failed to create dir $path")
                }
            }
            for (path in plan.copyToSaf) {
                if (!isCurrent(this@Bridge)) {
                    return applied
                }
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
     * Applies only verified operations to the previous baseline. In particular, a failed delete
     * must retain the old baseline entry; dropping it would make the next pass interpret the
     * still-present file as a new change on the other side and could restore stale data.
     */
    fun verifiedResult(
        plan: Plan,
        appliedFwd: Set<String>,
        appliedSaf: Set<String>
    ): Map<String, SafBridge.NodeInfo> {
        // Keep the original helper contract for pure callers that do not provide a baseline.
        // The bridge always uses the overload below so failed deletes retain their baseline.
        return verifiedResult(plan, appliedFwd, appliedSaf, emptyMap())
    }

    fun verifiedResult(
        plan: Plan,
        appliedFwd: Set<String>,
        appliedSaf: Set<String>,
        baseline: Map<String, SafBridge.NodeInfo>,
    ): Map<String, SafBridge.NodeInfo> {
        val touchedFwd = (plan.deleteInForwarded + plan.makeDirsInForwarded +
            plan.copyToForwarded.map { it.first }).toSet()
        val touchedSaf = (plan.deleteInSaf + plan.makeDirsInSaf + plan.copyToSaf).toSet()
        val touched = touchedFwd + touchedSaf
        val result = LinkedHashMap(baseline)

        // Unchanged paths and successfully represented targets can be copied directly. Touched
        // paths are applied below so failures can keep their baseline entry.
        for ((path, target) in plan.result) {
            if (path !in touched) {
                result[path] = target
            }
        }

        for (path in touched) {
            if ((!touchedFwd.contains(path) || appliedFwd.contains(path)) &&
                (!touchedSaf.contains(path) || appliedSaf.contains(path))
            ) {
                val target = plan.result[path]
                if (target == null) {
                    result.remove(path)
                } else {
                    result[path] = target
                }
            }
        }
        return result
    }

    private fun depth(path: String): Int = path.count { it == '/' }

    /**
         * Node equality: directories compare by type only (dir mtimes are not preserved
         * across providers); files use a content hash when one is available, otherwise compare
         * size and mtime while tolerating a missing (zero) mtime.
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
        if (a.contentHash != null || b.contentHash != null) {
            return a.size == b.size && a.contentHash != null && a.contentHash == b.contentHash
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
                        result[path] = SafBridge.NodeInfo(
                            isDir = false,
                            size = f.size,
                            contentHash = f.contentHash,
                        )
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
                        result[path] = SafBridge.NodeInfo(
                            isDir = false,
                            size = f.size,
                            contentHash = f.contentHash,
                        )
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
            result = result,
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

    /**
     * Scans one directory subtree. Implementations may use a full scan as a conservative
     * fallback, but must throw on provider uncertainty rather than return an empty map.
     */
    fun scanSubtree(path: String): Map<String, SafBridge.NodeInfo> {
        if (path.isEmpty()) {
            return scan()
        }
        return scan().filterKeys { it == path || it.startsWith("$path/") }
    }

    /** Opens the file at [path] for reading, or null if unavailable. */
    fun open(path: String): InputStream?

    /** Computes a streaming hash for [path], or null when the provider cannot open it. */
    fun contentHash(path: String): String? {
        return open(path)?.use(ContentHasher::sha256)
    }

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

    override fun scan(): Map<String, SafBridge.NodeInfo> = scanSubtree("")

    override fun scanSubtree(path: String): Map<String, SafBridge.NodeInfo> {
        val rootNode = if (path.isEmpty()) {
            root ?: throw IOException("Tree uri unavailable")
        } else {
            resolve(path) ?: throw IOException("Provider subtree unavailable for $path")
        }
        if (!rootNode.isDirectory) {
            throw IOException("Provider subtree is not a directory: $path")
        }
        val result = LinkedHashMap<String, SafBridge.NodeInfo>()
        if (path.isNotEmpty()) {
            result[path] = SafBridge.NodeInfo(isDir = true)
        }
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
                val name = child.name
                    ?: throw IOException("Provider returned an unnamed document for ${child.uri}")
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
        walk(rootNode, path)
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
            // The path was present in the verified scan that produced this plan. A null result
            // is therefore treated as unknown/query failure, not as a successful deletion.
            resolve(path)?.delete() ?: false
        } catch (e: Exception) {
            Log.w(TAG, "delete: Failed to delete $path", e)
            false
        }
    }
}
