package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

@Serializable
class Ignores(
    var line: List<String>? = null,
)
