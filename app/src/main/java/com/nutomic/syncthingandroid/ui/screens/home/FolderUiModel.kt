package com.nutomic.syncthingandroid.ui.screens.home

import android.content.Context
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.ui.theme.StatusKind
import com.nutomic.syncthingandroid.util.Util

/**
 * Immutable, precomputed view data for one folder list card. Building this on
 * the polling dispatcher keeps every string/format/regex operation out of the
 * UI thread, and data class equality lets Compose skip rows that did not
 * change at all. @Immutable is what makes that skipping actually work —
 * without it the List fields would mark the class unstable.
 */
@androidx.compose.runtime.Immutable
data class FolderUiModel(
    val id: String,
    val group: String,
    val title: String,
    val path: String,
    val typeTag: String,
    val pathShort: String,
    val overrideVisible: Boolean,
    val revertVisible: Boolean,
    val revertLabelRes: Int,
    val conflictCount: Int,
    val conflictFiles: List<String>,
    val lastItemText: String?,
    val lastItemTimeText: String?,
    val itemsAndSize: String?,
    val invalidText: String?,
    val statusText: String?,
    val statusKind: StatusKind,
    val isSyncing: Boolean,
    val completion: Int,

    /**
     * True when the folder lives in the SAF forwarding root but the persisted
     * URI grant is gone (fresh install + config import). The card shows the
     * [R.string.saf_bridge_reauthorize_card] warning and intercepts taps for
     * re-authorization instead of opening the editor.
     *
     * Precomputed here on the polling dispatcher via [SafBridge.needsAuthorization]
     * so the row stays a pure renderer; the continuously-polled flow in
     * HomeDataHost flips it back to false after re-authorization, which
     * automatically restores the normal card.
     */
    val needsSafAuthorization: Boolean,
)

/**
 * Builds the view models for the folder list, ported from the legacy
 * FoldersAdapter status logic. Must be called off the main thread.
 */
fun buildFolderUiModels(
    context: Context,
    api: RestApi?,
    apiConfigLoaded: Boolean,
    folders: List<Folder>,
): List<FolderUiModel> {
    val resources = context.resources
    return folders.map { folder ->
        val title = if (folder.label.isNullOrEmpty()) folder.id else folder.label
        val pathShort = getShortPathForUI(context, folder.path)

        var statusText: String? = null
        var statusKind = StatusKind.PAUSED
        var conflictCount = 0
        var conflictFiles: List<String> = emptyList()
        var lastItemText: String? = null
        var lastItemTimeText: String? = null
        var itemsAndSize: String? = null
        var invalidText: String? = folder.invalid
        var overrideVisible = false
        var revertVisible = false
        var revertLabelRes = R.string.revert_local_changes
        var isSyncing = false
        var completion = 100

        // SAF grant state: cheap pref + persisted-permission lookup, no REST
        // involved. Data-source logic itself is untouched (still SafBridge);
        // the model only surfaces it so the card can render the warning.
        val needsSafAuthorization =
            (context.applicationContext as SyncthingApp).safBridge.needsAuthorization(folder.path)

        val folderStatusEntry = if (api != null && apiConfigLoaded) api.getFolderStatus(folder.id) else null
        if (folderStatusEntry != null) {
            val folderStatus = folderStatusEntry.key
            val cached = folderStatusEntry.value

            val failedItems = folderStatus.errors > 0
            val neededItems = folderStatus.needFiles + folderStatus.needDirectories +
                    folderStatus.needSymlinks + folderStatus.needDeletes
            val outOfSync = folderStatus.state == "idle" && neededItems > 0
            overrideVisible = folder.type == Constants.FOLDER_TYPE_SEND_ONLY && outOfSync
            isSyncing = folderStatus.state == "syncing"
            completion = cached.completion.toInt()

            if (folder.type == Constants.FOLDER_TYPE_RECEIVE_ONLY) {
                revertVisible = folderStatus.receiveOnlyTotalItems > 0
                revertLabelRes = R.string.revert_local_changes
            } else if (folder.type == Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED) {
                revertVisible =
                    (folderStatus.receiveOnlyTotalItems - folderStatus.receiveOnlyChangedDeletes) > 0
                revertLabelRes = R.string.delete_unexpected_items
            }

            when {
                outOfSync -> {
                    statusText = resources.getString(R.string.status_outofsync)
                    statusKind = StatusKind.ERROR
                }
                failedItems -> {
                    statusText = resources.getString(R.string.state_failed_items, folderStatus.errors)
                    statusKind = StatusKind.ERROR
                }
                folder.paused -> {
                    statusText = resources.getString(R.string.state_paused)
                    statusKind = StatusKind.PAUSED
                }
                else -> when (folderStatus.state) {
                    "clean-waiting" -> {
                        statusText = resources.getString(R.string.state_clean_waiting)
                        statusKind = StatusKind.WARNING
                    }
                    "cleaning" -> {
                        statusText = resources.getString(R.string.state_cleaning)
                        statusKind = StatusKind.SYNCING
                    }
                    "idle" -> {
                        if (folder.getDeviceCount() <= 1) {
                            statusText = resources.getString(R.string.state_unshared)
                            statusKind = StatusKind.WARNING
                        } else if (revertVisible) {
                            statusText = resources.getString(R.string.state_local_additions)
                            statusKind = StatusKind.OK
                        } else {
                            statusText = resources.getString(R.string.state_up_to_date)
                            statusKind = StatusKind.OK
                        }
                    }
                    "scan-waiting" -> {
                        statusText = resources.getString(R.string.state_scan_waiting)
                        statusKind = StatusKind.WARNING
                    }
                    "scanning" -> {
                        statusText = resources.getString(R.string.state_scanning)
                        statusKind = StatusKind.SYNCING
                    }
                    "sync-waiting" -> {
                        statusText = resources.getString(R.string.state_sync_waiting)
                        statusKind = StatusKind.WARNING
                    }
                    "syncing" -> {
                        statusText = resources.getString(R.string.state_syncing, completion)
                        statusKind = StatusKind.SYNCING
                    }
                    "sync-preparing" -> {
                        statusText = resources.getString(R.string.state_sync_preparing)
                        statusKind = StatusKind.SYNCING
                    }
                    "error" -> {
                        statusText =
                            if (folderStatus.error.isNullOrEmpty())
                                resources.getString(R.string.state_error)
                            else
                                resources.getString(R.string.state_error_message, folderStatus.error)
                        statusKind = StatusKind.ERROR
                    }
                    "unknown" -> {
                        statusText = resources.getString(R.string.state_unknown)
                        statusKind = StatusKind.ERROR
                    }
                    else -> {
                        statusText = folderStatus.state
                        statusKind = StatusKind.ERROR
                    }
                }
            }

            // Conflicts.
            val discoveredConflicts = cached.discoveredConflictFiles ?: emptyArray()
            if (discoveredConflicts.isNotEmpty()) {
                conflictCount = discoveredConflicts.size
                conflictFiles = discoveredConflicts.toList()
            }

            // Last finished item.
            if (!cached.lastItemFinishedAction.isNullOrEmpty() &&
                !cached.lastItemFinishedItem.isNullOrEmpty() &&
                !cached.lastItemFinishedTime.isNullOrEmpty()
            ) {
                lastItemText = Util.getPathEllipsis(cached.lastItemFinishedItem)
                lastItemTimeText = Util.formatTime(cached.lastItemFinishedTime)
            }

            // Items and size summary.
            if (!folder.paused) {
                itemsAndSize =
                        resources.getQuantityString(
                            R.plurals.files,
                            folderStatus.inSyncFiles.toInt(),
                            folderStatus.inSyncFiles,
                            folderStatus.globalFiles
                        ) +
                        " \u2022 " +
                        resources.getString(
                            R.string.folder_size_format,
                            Util.readableFileSize(context, folderStatus.inSyncBytes.toDouble()),
                            Util.readableFileSize(context, folderStatus.globalBytes.toDouble())
                        )
            }

            invalidText = if (!folderStatus.invalid.isNullOrEmpty()) folderStatus.invalid else folder.invalid
        }

        FolderUiModel(
            id = folder.id,
            group = folder.group,
            title = title,
            path = folder.path,
            typeTag = folder.type,
            pathShort = pathShort,
            overrideVisible = overrideVisible,
            revertVisible = revertVisible,
            revertLabelRes = revertLabelRes,
            conflictCount = conflictCount,
            conflictFiles = conflictFiles,
            lastItemText = lastItemText,
            lastItemTimeText = lastItemTimeText,
            itemsAndSize = itemsAndSize,
            invalidText = invalidText,
            statusText = statusText,
            statusKind = statusKind,
            isSyncing = isSyncing,
            completion = completion,
            needsSafAuthorization = needsSafAuthorization,
        )
    }
}

private fun getShortPathForUI(context: Context, path: String): String {
    val shortenedPath = path
        .replaceFirst("/storage/emulated/0", "[int]")
        .replaceFirst("/storage/", "[ext]/")
        .let { p ->
            if (p.startsWith("/" + context.packageName)) {
                "/[app]" + p.removePrefix("/" + context.packageName)
            } else {
                p
            }
        }
    return Util.getPathEllipsis(shortenedPath)
}
