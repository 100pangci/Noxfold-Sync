package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

@Serializable
data class Connection(
    var address: String = "",
    var at: String = "",
    var clientVersion: String = "",
    var connected: Boolean = false,
    var inBytesTotal: Long = 0,
    var outBytesTotal: Long = 0,
    var paused: Boolean = false,
    var type: String = "",

    // These fields are not sent from Syncthing. They are populated by [setTransferRate].
    var inBits: Long = 0,
    var outBits: Long = 0,
) {
    fun setTransferRate(previous: Connection, msElapsed: Long) {
        val secondsElapsed = msElapsed / 1000
        val inBytes = 8 * (inBytesTotal - previous.inBytesTotal) / secondsElapsed
        val outBytes = 8 * (outBytesTotal - previous.outBytesTotal) / secondsElapsed
        inBits = maxOf(0, inBytes)
        outBits = maxOf(0, outBytes)
    }
}
