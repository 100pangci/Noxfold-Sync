package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

@Serializable
class IgnoredFolder(
    var id: String = "",
    var label: String = "",
    var time: String = "",
)
