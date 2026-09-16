package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

@Serializable
class RemoteIgnoredDevice(
    var time: String = "",
    var deviceID: String = "",
    var name: String = "",
    var address: String = "",
) {
    /**
     * Returns the device name, or the first characters of the ID if the name is empty.
     */
    val displayName: String
        get() = if (name.isEmpty()) deviceID.substring(0, 7) else name
}
