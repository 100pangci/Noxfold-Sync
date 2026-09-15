package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

@Serializable
class Defaults(
    var device: Device? = null,
    var folder: Folder? = null,
    var ignores: Ignores? = null,
)
