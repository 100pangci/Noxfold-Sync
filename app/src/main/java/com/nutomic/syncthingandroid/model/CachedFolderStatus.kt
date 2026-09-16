package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

/**
 * Caches information frequently needed by the wrapper
 * to save expensive calls to Syncthing's REST API.
 * Vars in class do not correspond to JSON results.
 */
@Serializable
data class CachedFolderStatus(
    /**
     * Calculated.
     */
    var completion: Double = 100.0,

    /**
     * Accessed by setters.
     */
    var discoveredConflictFiles: Array<String> = emptyArray(),
    var lastItemFinishedAction: String = "",
    var lastItemFinishedItem: String = "",
    var lastItemFinishedTime: String = "",
    var remoteIndexUpdated: Boolean = false,
    var paused: Boolean = false,
)
