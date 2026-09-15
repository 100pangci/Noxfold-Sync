package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

/**
 * /rest/db/completion?device=deviceId&folder=folderId
 */
@Serializable
class CompletionInfo(
    var completion: Double = 0.0,
    var globalBytes: Double = 0.0,
    var globalItems: Double = 0.0,
    var needBytes: Double = 0.0,
    var needDeletes: Double = 0.0,
    var needItems: Double = 0.0,
    var remoteState: String = "unknown",
    var sequence: Long = 0,
)
