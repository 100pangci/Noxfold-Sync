package com.nutomic.syncthingandroid.ui.screens.home

import android.content.Context
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.ui.appPreferences
import com.nutomic.syncthingandroid.ui.theme.StatusKind
import com.nutomic.syncthingandroid.util.Util

private const val ACTIVE_SYNC_BITS_PER_SECOND_THRESHOLD = 50L * 1024 * 8
private const val TIMESTAMP_NEVER_SEEN = "1970-01-01T00:00:00Z"

/**
 * Immutable, precomputed view data for one device list card (see [FolderUiModel]).
 * @Immutable makes row skipping work despite the List fields.
 */
@androidx.compose.runtime.Immutable
data class DeviceUiModel(
    val id: String,
    val group: String,
    val displayName: String,
    val lastSeenText: String,
    val sharedFolderNames: List<String>,
    val statusText: String,
    val statusKind: StatusKind,
    val isSyncing: Boolean,
    val completion: Int,
    val rateText: String?,
)

/**
 * Builds the view models for the device list, ported from the legacy
 * DevicesAdapter status logic. Must be called off the main thread.
 */
fun buildDeviceUiModels(
    context: Context,
    api: RestApi?,
    apiConfigLoaded: Boolean,
    devices: List<Device>,
    sharedFoldersByDevice: Map<String, List<com.nutomic.syncthingandroid.model.Folder>>,
): List<DeviceUiModel> {
    val resources = context.resources
    val preferences = context.appPreferences()
    return devices
        .sortedWith { lhs, rhs ->
            val lhsName = if (lhs.name.isNullOrEmpty()) lhs.deviceID else lhs.name
            val rhsName = if (rhs.name.isNullOrEmpty()) rhs.deviceID else rhs.name
            lhsName.compareTo(rhsName)
        }
        .map { device ->
            val deviceLastSeen = preferences.getString(
                Constants.PREF_CACHE_DEVICE_LASTSEEN_PREFIX + device.deviceID, ""
            ) ?: ""
            val lastSeenText = resources.getString(
                R.string.device_last_seen,
                if (deviceLastSeen.isEmpty() || deviceLastSeen == TIMESTAMP_NEVER_SEEN)
                    resources.getString(R.string.device_last_seen_never)
                else
                    Util.formatDateTime(deviceLastSeen)
            )

            val sharedFolders = sharedFoldersByDevice[device.deviceID] ?: emptyList()
            val sharedFolderNames = sharedFolders.map { it.toString() }.filter { it.isNotEmpty() }

            var statusText: String
            var statusKind: StatusKind
            var isSyncing = false
            var completion = 100
            var rateText: String? = null

            if (device.paused) {
                statusText = resources.getString(R.string.device_paused)
                statusKind = StatusKind.PAUSED
            } else if (api == null || !apiConfigLoaded) {
                statusText = resources.getString(R.string.device_disconnected)
                statusKind = StatusKind.ERROR
            } else {
                val conn = api.getRemoteDeviceStatus(device.deviceID)
                completion = api.getRemoteDeviceCompletion(device.deviceID)
                val needBytes = api.getRemoteDeviceNeedBytes(device.deviceID)

                if (conn.connected) {
                    rateText = "\u21f5 " +
                            resources.getString(R.string.download_title) + " \u02c5 " +
                            Util.readableTransferRate(context, conn.inBits) + " \u2022 " +
                            resources.getString(R.string.upload_title) + " \u02c4 " +
                            Util.readableTransferRate(context, conn.outBits)
                    val syncingState = completion != 100
                    isSyncing = syncingState
                    if (!syncingState) {
                        if ((conn.inBits + conn.outBits) >= ACTIVE_SYNC_BITS_PER_SECOND_THRESHOLD) {
                            statusText = resources.getString(R.string.state_syncing_general)
                            statusKind = StatusKind.SYNCING
                        } else {
                            statusText = resources.getString(R.string.device_up_to_date)
                            statusKind = StatusKind.OK
                        }
                    } else {
                        statusText = resources.getString(
                            R.string.device_syncing_percent_bytes,
                            completion,
                            Util.readableFileSize(context, needBytes)
                        )
                        statusKind = StatusKind.SYNCING
                    }
                } else {
                    statusText =
                        if (needBytes == 0.0)
                            resources.getString(R.string.device_disconnected)
                        else
                            resources.getString(
                                R.string.device_disconnected_not_synced,
                                Util.readableFileSize(context, needBytes)
                            )
                    statusKind = StatusKind.ERROR
                }
            }

            DeviceUiModel(
                id = device.deviceID,
                group = device.group,
                displayName = device.displayName,
                lastSeenText = lastSeenText,
                sharedFolderNames = sharedFolderNames,
                statusText = statusText,
                statusKind = statusKind,
                isSyncing = isSyncing,
                completion = completion,
                rateText = rateText,
            )
        }
}
