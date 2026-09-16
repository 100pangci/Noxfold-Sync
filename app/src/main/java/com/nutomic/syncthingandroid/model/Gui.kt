package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

/**
 * Sources:
 * - https://github.com/syncthing/syncthing/tree/master/lib/config
 * - https://github.com/syncthing/syncthing/blob/master/lib/config/guiconfiguration.go
 */
@Serializable
class Gui(
    var enabled: Boolean = true,
    var useTLS: Boolean = false,
    var address: String? = "127.0.0.1:8384",
    var user: String? = null,
    var password: String? = null,
    var apiKey: String? = null,

    /**
     * Available: default, dark, black
     */
    var theme: String = "default",

    var insecureAdminAccess: Boolean = false,
    var insecureAllowFrameLoading: Boolean = false,
    var insecureSkipHostCheck: Boolean = false,
) {
    /**
     * The bind host part of [address], or "" if unset or malformed.
     */
    val bindAddress: String
        get() {
            val address = address ?: return ""
            val split = address.split(":")
            return split.firstOrNull() ?: ""
        }

    /**
     * The bind port part of [address], or "" if unset or malformed.
     */
    val bindPort: String
        get() {
            val address = address ?: return ""
            val split = address.split(":")
            return if (split.size < 2) "" else split[1]
        }
}
