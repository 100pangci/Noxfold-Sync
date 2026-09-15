package com.nutomic.syncthingandroid.model

import android.util.Log
import kotlin.math.floor

/**
 * This class caches remote folder and device synchronization
 * completion indicators defined in [RemoteCompletionInfo]
 * according to syncthing's REST "/completion" JSON result schema.
 * Completion model of syncthing's web UI is completion[deviceId][folderId].
 *
 * Entries are immutable snapshots: readers get copies and setters replace the whole
 * entry, so no Gson round-trip deep copy is needed anymore.
 *
 * Note: access is still serialized with a monitor lock instead of a coroutine
 * [kotlinx.coroutines.sync.Mutex] because all call sites are synchronous (RestApi is
 * callback-based); converting them to suspend is tracked for the threading phase.
 */
class RemoteCompletion(enableVerboseLog: Boolean) {

    private data class DeviceEntry(
        val connection: Connection,
        val folders: MutableMap<String, RemoteCompletionInfo>,
    )

    private val deviceFolderMap: MutableMap<String, DeviceEntry> = HashMap()

    /**
     * Object that must be locked upon accessing deviceFolderMap.
     */
    private val deviceFolderMapLock = Any()

    private val ENABLE_VERBOSE_LOG = enableVerboseLog

    /**
     * Removes a folder from the cache model.
     */
    private fun removeFolder(folderId: String) {
        synchronized(deviceFolderMapLock) {
            for (entry in deviceFolderMap.values) {
                if (entry.folders.containsKey(folderId)) {
                    entry.folders.remove(folderId)
                    break
                }
            }
        }
    }

    /**
     * Updates device and folder information in the cache model
     * after a config update.
     */
    fun updateFromConfig(newDevices: List<Device>, newFolders: List<Folder>) {
        synchronized(deviceFolderMapLock) {
            // Handle devices that were removed from the config.
            val removedDevices = deviceFolderMap.keys.filter { deviceId ->
                newDevices.none { it.deviceID == deviceId }
            }
            for (deviceId in removedDevices) {
                logV("updateFromConfig: Remove device '${getShortenedDeviceId(deviceId)}' from cache model")
                deviceFolderMap.remove(deviceId)
            }

            // Handle devices that were added to the config.
            for (device in newDevices) {
                if (!deviceFolderMap.containsKey(device.deviceID)) {
                    logV("updateFromConfig: Add device '${getShortenedDeviceId(device.deviceID)}' to cache model")
                    deviceFolderMap[device.deviceID] = DeviceEntry(Connection(), HashMap())
                }
            }

            // Handle folders that were removed from the config.
            val removedFolders = mutableSetOf<String>()
            for (entry in deviceFolderMap.values) {
                for (folderId in entry.folders.keys) {
                    if (newFolders.none { it.id == folderId }) {
                        removedFolders.add(folderId)
                    }
                }
            }
            for (folderId in removedFolders) {
                logV("updateFromConfig: Remove folder '$folderId' from cache model")
                removeFolder(folderId)
            }

            // Handle folders that were added to the config.
            for (folder in newFolders) {
                for (device in newDevices) {
                    if (folder.getDevice(device.deviceID) != null) {
                        // folder is shared with device.
                        val folderMap = deviceFolderMap[device.deviceID]!!.folders
                        if (!folderMap.containsKey(folder.id)) {
                            logV(
                                "updateFromConfig: Add folder '${folder.id}'" +
                                    " shared with device '${getShortenedDeviceId(device.deviceID)}' to cache model."
                            )
                            folderMap[folder.id] = RemoteCompletionInfo()
                        }
                    }
                }
            }
        }
    }

    /**
     * Accumulates per-folder completion values and calculates the average percentage
     * clamped to 0-100. Takes into account that Syncthing's WebUI considers remote
     * folders with 0% and 100% completion as up-to-date.
     */
    private class CompletionAccumulator {
        private var folderCount = 0
        private var sumCompletion = 0.0

        fun accumulate(completionInfos: Iterable<RemoteCompletionInfo>) {
            for (completionInfo in completionInfos) {
                val folderCompletion = completionInfo.completion.coerceIn(0.0, 100.0)
                if (folderCompletion != 0.0 && folderCompletion != 100.0) {
                    sumCompletion += folderCompletion
                    folderCount++
                }
            }
        }

        fun calculatePercentage(): Int {
            if (folderCount == 0) {
                return 100
            }
            return floor(sumCompletion / folderCount).toInt().coerceIn(0, 100)
        }
    }

    /**
     * Calculates remote device sync completion percentage across all connected devices.
     * Returns "-1" if sync completion is not applicable.
     */
    fun getTotalDeviceCompletion(): Int {
        synchronized(deviceFolderMapLock) {
            var connectedDeviceCount = 0
            for (entry in deviceFolderMap.values) {
                if (entry.connection.connected) {
                    connectedDeviceCount++
                }
            }
            if (connectedDeviceCount == 0) {
                return -1
            }
            val accumulator = CompletionAccumulator()
            for (entry in deviceFolderMap.values) {
                if (!entry.connection.connected) {
                    continue
                }
                accumulator.accumulate(entry.folders.values)
            }
            return accumulator.calculatePercentage()
        }
    }

    /**
     * Calculates remote device sync completion percentage across all folders
     * shared with the device.
     */
    fun getDeviceCompletion(deviceId: String): Int {
        synchronized(deviceFolderMapLock) {
            val entry = deviceFolderMap[deviceId]
            if (entry == null) {
                logV("getDeviceCompletion: Cache miss for deviceId=[$deviceId]")
                return 100
            }

            val accumulator = CompletionAccumulator()
            accumulator.accumulate(entry.folders.values)
            return accumulator.calculatePercentage()
        }
    }

    fun getDeviceNeedBytes(deviceId: String): Double {
        synchronized(deviceFolderMapLock) {
            val entry = deviceFolderMap[deviceId]
            if (entry == null) {
                logV("getDeviceNeedBytes: Cache miss for deviceId=[$deviceId]")
                return 0.0
            }

            var sumNeedBytes = 0.0
            for (folder in entry.folders.values) {
                sumNeedBytes += folder.needBytes
            }
            return sumNeedBytes
        }
    }

    /**
     * Set completionInfo within the completion[deviceId][folderId] model.
     */
    fun setCompletionInfo(deviceId: String, folderId: String, completionInfo: RemoteCompletionInfo) {
        synchronized(deviceFolderMapLock) {
            // Add device parent node if it does not exist.
            var entry = deviceFolderMap[deviceId]
            if (entry == null) {
                entry = DeviceEntry(Connection(), HashMap())
                deviceFolderMap[deviceId] = entry
            }
            logV(
                "setCompletionInfo: Storing ${completionInfo.completion}% for folder \"" +
                    "$folderId\" at device \"" +
                    "${getShortenedDeviceId(deviceId)}\"."
            )
            // Add folder or update existing folder entry.
            entry.folders[folderId] = completionInfo
        }
    }

    /**
     * Returns remote device status.
     */
    fun getDeviceStatus(deviceId: String): Connection {
        synchronized(deviceFolderMapLock) {
            val entry = deviceFolderMap[deviceId] ?: return Connection()
            return entry.connection.copy()
        }
    }

    fun getOnlineDeviceCount(): Int {
        synchronized(deviceFolderMapLock) {
            return deviceFolderMap.values.count { it.connection.connected }
        }
    }

    /**
     * Store remote device status for later when we need info for the UI.
     */
    fun setDeviceStatus(deviceId: String, connection: Connection) {
        synchronized(deviceFolderMapLock) {
            val existing = deviceFolderMap[deviceId] ?: DeviceEntry(Connection(), HashMap())
            deviceFolderMap[deviceId] = DeviceEntry(
                connection.copy(),
                existing.folders.mapValuesTo(HashMap()) { it.value.copy() },
            )
        }
    }

    /**
     * Returns the first characters of the device ID for logging purposes.
     */
    fun getShortenedDeviceId(deviceId: String): String {
        return if (deviceId.isEmpty()) "" else deviceId.substring(0, 7)
    }

    private fun logV(logMessage: String) {
        if (ENABLE_VERBOSE_LOG) {
            Log.v(TAG, logMessage)
        }
    }

    companion object {
        private const val TAG = "RemoteCompletion"
    }
}
