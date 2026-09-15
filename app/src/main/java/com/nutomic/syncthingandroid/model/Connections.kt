package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

@Serializable
class Connections(
    var total: Connection? = null,
    var connections: Map<String, Connection>? = null,
)
