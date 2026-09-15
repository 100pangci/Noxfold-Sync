package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

/**
 * REST API endpoint "/rest/system/status"
 */
@Serializable
class SystemStatus(
    // Example: 25857744
    var alloc: Long = 0,

    /**
     * No longer supported since SyncthingNative v1.4.1
     * v1.4.1+ return "cpuPercent = 0"
     */
    // Example: 1.1183119275778985
    var cpuPercent: Double = 0.0,

    // Example:
    //  connectionServiceStatus: {dynamic+https://relays.syncthing.net/endpoint: {error: null, lanAddresses: [], wanAddresses: []},…}
    //      dynamic+https://relays.syncthing.net/endpoint: {error: null, lanAddresses: [], wanAddresses: []}
    //      quic://0.0.0.0:22000: {error: null, lanAddresses: ["quic://0.0.0.0:22000"],…}
    //      tcp://0.0.0.0:22000: {error: null, lanAddresses: ["tcp://0.0.0.0:22000"], wanAddresses: ["tcp://0.0.0.0:22000"]}
    var connectionServiceStatus: Map<String, SystemStatusConnectionServiceStatusElement>? = null,

    // Example: true
    var discoveryEnabled: Boolean = false,

    // Example:
    //  discoveryErrors: {,…}
    var discoveryErrors: Map<String, String>? = null,

    // Example: 5
    var discoveryMethods: Int = 0,

    // Example: 77
    var goroutines: Int = 0,

    // Example: "7LTUV3P-Y37HQXK-UUM7S5Q-2NDQT3B-SA4WAT4-T5ODX3V-XRXAF7Z-MXM7GAA"
    var myID: String? = null,

    // Example: "\"
    var pathSeparator: String? = null,

    // Example: "2019-09-21T10:59:47.1951229+02:00"
    var startTime: String? = null,

    // RAM usage, Example: 46476920
    var sys: Long = 0,

    // Example: "C:\Users\Dev"
    var tilde: String? = null,

    // Example: 29
    var uptime: Long = 0,

    // Example: 3
    var urVersionMax: Int = 0,

    // Example:
    //  lastDialStatus: {,…}
    //      tcp4://192.168.5.1: {when: "2019-09-21T09:10:35Z", error: "dial tcp4 192.168.5.1:22000: i/o timeout"}
    var lastDialStatus: Map<String, SystemStatusLastDialStatusElement>? = null,

    // Example: false
    var guiAddressOverridden: Boolean = false,

    /**
     * Since SyncthingNative v1.3.0
     */
    // Example: "127.0.0.1:8384"
    var guiAddressUsed: String? = null,
)
