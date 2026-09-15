package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
class Event(
    var id: Int = 0,
    var globalID: Int = 0,
    var type: String? = null,
    var time: String? = null,
    var data: JsonObject? = null,
)
