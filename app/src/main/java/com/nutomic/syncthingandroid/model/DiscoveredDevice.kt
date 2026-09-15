package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

/**
 * This receives the deserialization result of the URI_SYSTEM_DISCOVERY query.
 *
 * JSON result example
 *    {
 *          "2MY7NNQ-IRBZIFP-B2V574Y-AX6FNIP-55VGH5H-GUD3RFV-K2RXX6P-XXXXXX":
 *          {
 *              "addresses":
 *                  [
 *                      "tcp4://192.168.178.10:40001"
 *                  ]
 *          }
 *    }
 */
@Serializable
class DiscoveredDevice(
    var addresses: Array<String>? = null,
)
