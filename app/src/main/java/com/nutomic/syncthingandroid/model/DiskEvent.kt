package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

/**
 * REST API endpoint "/rest/events/disk"
 */
@Serializable
class DiskEvent(
    var id: Long = 0,
    var globalID: Long = 0,
    var time: String = "",

    // type = {"LocalChangeDetected", "RemoteChangeDetected"}
    var type: String = "",

    var data: DiskEventData = DiskEventData(),
)
