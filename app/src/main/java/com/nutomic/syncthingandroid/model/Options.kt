package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

/**
 * Sources:
 * - https://github.com/syncthing/syncthing/tree/main/lib/config
 * - https://github.com/syncthing/syncthing/blob/main/lib/config/optionsconfiguration.go
 */
@Serializable
class Options(
    var listenAddresses: Array<String>? = null,
    var globalAnnounceServers: Array<String>? = null,
    var globalAnnounceEnabled: Boolean = true,
    var localAnnounceEnabled: Boolean = true,
    var localAnnouncePort: Int = 21027,
    var localAnnounceMCAddr: String? = null,
    var maxSendKbps: Int = 0,
    var maxRecvKbps: Int = 0,
    var reconnectionIntervalS: Int = 60,
    var relaysEnabled: Boolean = true,
    var relayReconnectIntervalM: Int = 10,
    var startBrowser: Boolean = false,
    var natEnabled: Boolean = true,
    var natLeaseMinutes: Int = 60,
    var natRenewalMinutes: Int = 30,
    var natTimeoutSeconds: Int = 10,
    var urAccepted: Int = 0,
    var urUniqueId: String? = null,
    var urURL: String = "https://data.syncthing.net/newdata",
    var urPostInsecurely: Boolean = false,
    var urInitialDelayS: Int = 1800,
    var autoUpgradeIntervalH: Int = 0,
    var upgradeToPreReleases: Boolean = false,
    var keepTemporariesH: Int = 24,
    var cacheIgnoredFiles: Boolean = false,
    var progressUpdateIntervalS: Int = 5,
    var limitBandwidthInLan: Boolean = false,
    var releasesURL: String = "https://upgrades.syncthing.net/meta.json",
    var alwaysLocalNets: Array<String>? = null,
    var overwriteRemoteDeviceNamesOnConnect: Boolean = false,
    var tempIndexMinBlocks: Int = 10,
    var setLowPriority: Boolean = true,

    // Since v0.14.28
    var minHomeDiskFree: MinHomeDiskFree? = null,

    // Since v1.2.0
    var crURL: String = "https://crash.syncthing.net/newcrash",
    var crashReportingEnabled: Boolean = true,
    var stunKeepaliveStartS: Int = 180,
    var stunKeepaliveMinS: Int = 20,
    var stunServer: String = "default",

    // Since v1.4.0
    var maxFolderConcurrency: Int = 1,
    var maxConcurrentIncomingRequestKiB: Int = 0,

    // Since v1.10.0
    var announceLANAddresses: Boolean = true,

    // Since v1.11.0
    var sendFullIndexOnUpgrade: Boolean = false,

    // Since v1.12.0
    var featureFlag: String = "",

    // Since v1.13.0
    var connectionLimitEnough: Int = 0,
    var connectionLimitMax: Int = 0,

    var unackedNotificationID: String = "",
) {
    @Serializable
    class MinHomeDiskFree(
        var value: Float = 1f,
        var unit: String = "%",
    )

    fun isUsageReportingAccepted(urVersionMax: Int): Boolean {
        return urAccepted == urVersionMax
    }

    fun isUsageReportingDecided(urVersionMax: Int): Boolean {
        return isUsageReportingAccepted(urVersionMax) || urAccepted == USAGE_REPORTING_DENIED
    }

    companion object {
        const val USAGE_REPORTING_UNDECIDED = 0
        const val USAGE_REPORTING_DENIED = -1
    }
}
