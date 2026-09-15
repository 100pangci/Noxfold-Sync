package com.nutomic.syncthingandroid.model

import com.nutomic.syncthingandroid.service.Constants
import kotlinx.serialization.Serializable

/**
 * Sources:
 * - https://github.com/syncthing/syncthing/tree/master/lib/config
 * - https://github.com/syncthing/syncthing/blob/master/lib/config/folderconfiguration.go
 */
@Serializable
class Folder(
    // Folder Configuration
    var group: String = "",
    var id: String = "",
    var label: String = "",
    var filesystemType: String = "basic",
    var path: String = "",
    var type: String = Constants.FOLDER_TYPE_SEND_RECEIVE,
    var fsWatcherEnabled: Boolean = true,
    var fsWatcherDelayS: Float = 10f,
    private var devices: MutableList<SharedWithDevice> = ArrayList(),

    /**
     * Folder rescan interval defaults to 3600s as it is the default in
     * syncthing when the file watcher is enabled and a new folder is created.
     */
    var rescanIntervalS: Int = 3600,
    var ignorePerms: Boolean = true,
    var autoNormalize: Boolean = true,
    var minDiskFree: MinDiskFree? = null,
    var versioning: Versioning? = null,
    var copiers: Int = 0,
    var pullerMaxPendingKiB: Int = 0,
    var hashers: Int = 0,
    var order: String = "random",
    var ignoreDelete: Boolean = false,
    var scanProgressIntervalS: Int = 0,
    var pullerPauseS: Int = 0,
    var maxConflicts: Int = 10,
    var disableSparseFiles: Boolean = false,
    var paused: Boolean = false,
    var markerName: String = Constants.FILENAME_STFOLDER,

    // Since v1.1.0
    var copyOwnershipFromParent: Boolean = false,

    // Since v1.2.1, see PR #5852
    var modTimeWindowS: Int = 0,

    // Since v1.6.0
    // see PR #6587: "inorder", "random", "standard"
    var blockPullOrder: String = "standard",
    // see PR #6588
    var disableFsync: Boolean = false,
    // see PR #6573, #10200
    var maxConcurrentWrites: Int = 0,

    // Since v1.8.0
    // see PR #6746: "all", "copy_file_range", "duplicate_extents", "ioctl", "sendfile", "standard"
    var copyRangeMethod: String = "standard",

    // Since v1.9.0
    var caseSensitiveFS: Boolean = false,

    // Since v1.21.0
    var syncOwnership: Boolean = false,
    var sendOwnership: Boolean = false,

    // Since v1.22.0
    var syncXattrs: Boolean = false,
    var sendXattrs: Boolean = false,

    // Since v2.1.0
    var blockIndexing: Boolean = true,

    // Folder Status
    var invalid: String? = null,
) {
    @Serializable
    class Versioning(
        var type: String? = null,
        var cleanupIntervalS: Int = 0,
        var params: MutableMap<String, String> = HashMap(),
        // Since v1.14.0
        var fsPath: String? = null,
        var fsType: String? = null,
    )

    @Serializable
    class MinDiskFree(
        var value: Float = 1f,
        var unit: String = "%",
    )

    fun addDevice(device: Device) {
        // Avoid {@link ConfigXml#updateDevice} creating two list entries with the same device ID.
        removeDevice(device.deviceID)

        val d = SharedWithDevice()
        d.deviceID = device.deviceID
        d.introducedBy = device.introducedBy
        devices.add(d)
    }

    fun addDevice(sharedWithDevice: SharedWithDevice) {
        // Avoid {@link ConfigXml#updateDevice} creating two list entries with the same device ID.
        removeDevice(sharedWithDevice.deviceID)

        val d = SharedWithDevice()
        d.deviceID = sharedWithDevice.deviceID
        d.encryptionPassword = sharedWithDevice.encryptionPassword
        d.introducedBy = sharedWithDevice.introducedBy
        devices.add(d)
    }

    fun getSharedWithDevices(): List<SharedWithDevice> {
        return devices
    }

    /**
     * Note: This is expected to return "1" if a folder is not shared with any
     * other device. Syncthing's config will list ourself as the only device
     * sub node which is associated with the folder. This will return >= 2
     * if the folder is shared with other devices.
     */
    fun getDeviceCount(): Int {
        return devices.size
    }

    fun getDevice(deviceId: String): SharedWithDevice? {
        return devices.firstOrNull { it.deviceID == deviceId }
    }

    fun removeDevice(deviceId: String) {
        devices.removeAll { it.deviceID == deviceId }
    }

    override fun toString(): String {
        return if (label.isEmpty()) {
            if (id.isEmpty()) "" else id
        } else {
            label
        }
    }

    companion object {
        /**
         * Compares folders by labels, uses the folder ID as fallback if the label is empty.
         */
        val LABEL_COMPARATOR: Comparator<Folder> = Comparator { lhs, rhs ->
            val lhsLabel = if (!lhs.label.isEmpty()) lhs.label else lhs.id
            val rhsLabel = if (!rhs.label.isEmpty()) rhs.label else rhs.id
            lhsLabel.compareTo(rhsLabel)
        }
    }
}
