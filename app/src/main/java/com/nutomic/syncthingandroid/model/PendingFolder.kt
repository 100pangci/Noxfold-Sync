package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

@Serializable
class PendingFolder(
    var label: String = "",
    var time: String = "",
    var receiveEncrypted: Boolean = false,
    var remoteEncrypted: Boolean = false,
)
