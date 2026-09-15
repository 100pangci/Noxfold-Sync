package com.nutomic.syncthingandroid.model

import android.util.Log
import kotlin.math.floor

/**
 * This class caches local folder synchronization
 * completion indicators defined in [CachedFolderStatus]
 * according to Syncthing's "FolderSummary" event JSON result schema.
 * Completion model of Syncthing's web UI is completion[folderId].
 *
 * Entries are immutable snapshots: readers get copies and setters replace the whole
 * entry, so no Gson round-trip deep copy is needed on the read path anymore.
 *
 * Note: access is still serialized with a monitor lock instead of a coroutine
 * [kotlinx.coroutines.sync.Mutex] because all call sites are synchronous (RestApi is
 * callback-based); converting them to suspend is tracked for the threading phase.
 */
class LocalCompletion(enableVerboseLog: Boolean) {

    data class FolderStatusEntry(
        val folderStatus: FolderStatus,
        val cachedFolderStatus: CachedFolderStatus,
    )

    private val folderMap: MutableMap<String, FolderStatusEntry> = HashMap()

    /**
     * Object that must be locked upon accessing folderMap.
     */
    private val folderMapLock = Any()

    private val ENABLE_VERBOSE_LOG = enableVerboseLog

    /**
     * Updates folder information in the cache model
     * after a config update.
     */
    fun updateFromConfig(newFolders: List<Folder>) {
        synchronized(folderMapLock) {
            // Handle folders that were removed from the config.
            val removedFolders = folderMap.keys.filter { folderId ->
                newFolders.none { it.id == folderId }
            }
            for (folderId in removedFolders) {
                logV("updateFromConfig: Remove folder '$folderId' from cache model")
                folderMap.remove(folderId)
            }

            // Handle folders that were added to the config.
            for (folder in newFolders) {
                if (!folderMap.containsKey(folder.id)) {
                    logV("updateFromConfig: Add folder '${folder.id}' to cache model.")
                    folderMap[folder.id] = FolderStatusEntry(FolderStatus(), CachedFolderStatus())
                }
            }
        }
    }

    /**
     * Calculates local folder sync completion percentage across all folders.
     */
    fun getTotalFolderCompletion(): Int {
        synchronized(folderMapLock) {
            var folderCount = 0
            var sumCompletion = 0.0
            for (entry in folderMap.values) {
                val cachedFolderStatus = entry.cachedFolderStatus
                // Filter invalid percentage values we may have got from the REST API.
                val completion = cachedFolderStatus.completion.coerceIn(0.0, 100.0)

                if (!cachedFolderStatus.paused && completion != 100.0) {
                    sumCompletion += completion
                    folderCount++
                }
            }
            if (folderCount == 0) {
                return 100
            }
            return floor(sumCompletion / folderCount).toInt().coerceIn(0, 100)
        }
    }

    /**
     * Returns local folder status including completion info.
     */
    fun getFolderStatus(folderId: String): FolderStatusEntry {
        synchronized(folderMapLock) {
            val entry = folderMap[folderId]
                ?: return FolderStatusEntry(FolderStatus(), CachedFolderStatus())
            return FolderStatusEntry(
                entry.folderStatus.copy(),
                entry.cachedFolderStatus.copy(
                    discoveredConflictFiles = entry.cachedFolderStatus.discoveredConflictFiles.copyOf()
                ),
            )
        }
    }

    /**
     * Store folderStatus for later when we need info for the UI.
     * Calculate cachedFolderStatus within the completion[folderId] model.
     */
    fun setFolderStatus(folderId: String, folderPaused: Boolean, folderStatus: FolderStatus) {
        synchronized(folderMapLock) {
            val previous = folderMap[folderId]?.cachedFolderStatus ?: CachedFolderStatus()
            val cachedFolderStatus = previous.copy(
                paused = folderPaused,
                completion = calculateCompletion(folderStatus),
            )
            if (ENABLE_VERBOSE_LOG) {
                logV(
                    "setFolderStatus: folderId=\"$folderId\"" +
                        ", state=\"${folderStatus.state}\"" +
                        ", paused=${cachedFolderStatus.paused}" +
                        ", completion=${cachedFolderStatus.completion.toInt()}%"
                )
            }

            // Add folder or update existing folder entry.
            folderMap[folderId] = FolderStatusEntry(folderStatus, cachedFolderStatus)
        }
    }

    fun setFolderStatus(folderId: String, folderStatus: FolderStatus) {
        synchronized(folderMapLock) {
            // Persist cachedFolderStatus.paused from the previous entry.
            val previous = folderMap[folderId]?.cachedFolderStatus ?: CachedFolderStatus()
            setFolderStatus(folderId, previous.paused, folderStatus)
        }
    }

    private fun calculateCompletion(folderStatus: FolderStatus): Double {
        if (folderStatus.globalBytes == 0L ||
            folderStatus.inSyncBytes > folderStatus.globalBytes
        ) {
            return 100.0
        }
        if (folderStatus.state == "idle") {
            return 100.0
        }
        return floor(folderStatus.inSyncBytes.toDouble() / folderStatus.globalBytes * 100).toDouble()
    }

    /**
     * Setters of additionally stored information
     * e.g. "ItemFinished" event details arriving through [com.nutomic.syncthingandroid.service.EventPoller].
     */
    fun setLastItemFinished(
        folderId: String,
        lastItemFinishedAction: String,
        lastItemFinishedItem: String,
        lastItemFinishedTime: String
    ) {
        synchronized(folderMapLock) {
            val entry = folderMap[folderId] ?: FolderStatusEntry(FolderStatus(), CachedFolderStatus())
            val cachedFolderStatus = entry.cachedFolderStatus.copy(
                lastItemFinishedAction = lastItemFinishedAction,
                lastItemFinishedItem = lastItemFinishedItem,
                lastItemFinishedTime = lastItemFinishedTime,
            )

            // Add folder or update existing folder entry.
            folderMap[folderId] = entry.copy(cachedFolderStatus = cachedFolderStatus)
        }
    }

    fun setRemoteIndexUpdated(folderId: String, remoteIndexUpdated: Boolean) {
        synchronized(folderMapLock) {
            val entry = folderMap[folderId] ?: FolderStatusEntry(FolderStatus(), CachedFolderStatus())
            folderMap[folderId] = entry.copy(
                cachedFolderStatus = entry.cachedFolderStatus.copy(remoteIndexUpdated = remoteIndexUpdated)
            )
        }
    }

    fun setDiscoveredConflictFiles(folderId: String, discoveredConflictFiles: Array<String>) {
        synchronized(folderMapLock) {
            val entry = folderMap[folderId] ?: FolderStatusEntry(FolderStatus(), CachedFolderStatus())
            folderMap[folderId] = entry.copy(
                cachedFolderStatus = entry.cachedFolderStatus.copy(
                    discoveredConflictFiles = discoveredConflictFiles.copyOf()
                )
            )
        }
    }

    private fun logV(logMessage: String) {
        if (ENABLE_VERBOSE_LOG) {
            Log.v(TAG, logMessage)
        }
    }

    companion object {
        private const val TAG = "LocalCompletion"
    }
}
