package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

@Serializable
class PendingDevice(
    var time: String = "",
    var name: String = "",
    var address: String = "",
)
