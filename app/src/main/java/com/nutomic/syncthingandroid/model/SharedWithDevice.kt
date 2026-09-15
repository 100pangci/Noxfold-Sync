package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

@Serializable
class SharedWithDevice(
    var deviceID: String = "",
    var introducedBy: String = "",

    /**
     * Since v1.12.0
     * See https://github.com/syncthing/syncthing/pull/7055
     */
    var encryptionPassword: String = "",
) {
    /**
     * Returns the device name, or the first characters of the ID if the name is empty.
     */
    val displayName: String
        get() = if (deviceID.isEmpty()) "" else deviceID.substring(0, 7)
}
