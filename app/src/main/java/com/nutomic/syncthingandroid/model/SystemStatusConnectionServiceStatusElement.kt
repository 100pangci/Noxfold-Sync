package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

/**
 * REST API endpoint "/rest/system/status"
 * Part of JSON answer in field [SystemStatus.connectionServiceStatus].
 */
@Serializable
class SystemStatusConnectionServiceStatusElement(
    var error: String? = null,
    var lanAddresses: List<String>? = null,
    var wanAddresses: List<String>? = null,
)
