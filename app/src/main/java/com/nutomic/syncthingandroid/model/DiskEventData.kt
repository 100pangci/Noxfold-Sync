package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

/**
 * REST API endpoint "/rest/events/disk"
 */
@Serializable
class DiskEventData(
    // action = {"added", "deleted", "modified"}
    var action: String = "",

    var folder: String = "",
    var folderID: String = "",
    var label: String = "",
    var modifiedBy: String = "",
    var path: String = "",

    // type = {"file", "dir"}
    var type: String = "",
)
