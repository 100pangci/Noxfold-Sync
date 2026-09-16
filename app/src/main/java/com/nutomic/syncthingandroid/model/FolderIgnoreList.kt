package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

/**
 * To avoid name confusion:
 * This is the exclude and include items list associated with every folder.
 */
@Serializable
class FolderIgnoreList(
    var expanded: Array<String>? = null,
    var ignore: Array<String>? = null,
)
