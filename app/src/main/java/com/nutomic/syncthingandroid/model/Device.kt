package com.nutomic.syncthingandroid.model

import android.text.TextUtils
import android.util.Log
import com.nutomic.syncthingandroid.util.Luhn
import java.util.Arrays
import java.util.Locale

/** Public fields on purpose: Gson reflective binding + direct field access from Java tests. */
class Device {
    @JvmField
    var deviceID: String = ""
    @JvmField
    var name: String = ""
    @JvmField
    var group: String = ""
    @JvmField
    var addresses: List<String>? = null
    @JvmField
    var allowedNetworks: List<String>? = null
    @JvmField
    var compression: String = "metadata"
    @JvmField
    var certName: String? = null
    @JvmField
    var introducedBy: String = ""
    @JvmField
    var introducer: Boolean = false
    @JvmField
    var paused: Boolean = false
    @JvmField
    var ignoredFolders: MutableList<IgnoredFolder>? = null
    @JvmField
    var autoAcceptFolders: Boolean = false
    @JvmField
    var maxRecvKbps: Int = 0
    @JvmField
    var maxSendKbps: Int = 0

    // Since v1.12.0
    @JvmField
    var untrusted: Boolean = false

    // Since v1.25.0
    @JvmField
    var numConnections: Int = 0

    /**
     * Returns the device name, or the first characters of the ID if the name is empty.
     */
    val displayName: String
        get() {
            return if (TextUtils.isEmpty(name)) {
                if (TextUtils.isEmpty(deviceID)) "" else deviceID.substring(0, 7)
            } else {
                name
            }
        }

    /**
     * Returns if a syncthing device ID is correctly formatted.
     */
    fun checkDeviceID(): Boolean {
        // See https://github.com/syncthing/syncthing/blob/master/lib/protocol/deviceid.go
        var deviceIdToCheck = deviceID

        // Trim "="
        deviceIdToCheck = deviceIdToCheck.replace("=", "")

        // Convert to upper case.
        deviceIdToCheck = deviceIdToCheck.uppercase(Locale.ROOT)

        // untypeoify
        deviceIdToCheck = deviceIdToCheck.replace("1", "I")
        deviceIdToCheck = deviceIdToCheck.replace("0", "O")
        deviceIdToCheck = deviceIdToCheck.replace("8", "B")

        // unchunkify
        deviceIdToCheck = deviceIdToCheck.replace("-", "")
        deviceIdToCheck = deviceIdToCheck.replace(" ", "")

        // Check length.
        when (deviceIdToCheck.length) {
            0 -> {
                // Log.w(TAG, "checkDeviceID: Empty device ID.");
                return false
            }
            56 -> {
                // unluhnify(deviceID)
                val bytesIn = deviceIdToCheck.toByteArray()
                val res = ByteArray(52)
                for (i in 0 until 4) {
                    val p = Arrays.copyOfRange(bytesIn, i * (13 + 1), (i + 1) * (13 + 1) - 1)
                    System.arraycopy(p, 0, res, i * 13, 13)

                    // Generate check digit.
                    val checkRune = Luhn.generate(p)
                    if (checkRune == null) {
                        return false
                    }
                    if (deviceIdToCheck.substring((i + 1) * 14 - 1, (i + 1) * 14 - 1 + 1) != checkRune) {
                        return false
                    }
                }
                deviceIdToCheck = String(res)
                // Fall-Through
                return try {
                    requireValidBase32(deviceIdToCheck + "====")
                    true
                } catch (e: IllegalArgumentException) {
                    false
                }
            }
            else -> {
                // Check for the "52 char" case reached via the fall-through above.
                if (deviceIdToCheck.length == 52) {
                    return try {
                        requireValidBase32(deviceIdToCheck + "====")
                        true
                    } catch (e: IllegalArgumentException) {
                        false
                    }
                }
                // Log.w(TAG, "checkDeviceID: Incorrect length (" + deviceIdToCheck + ")");
                return false
            }
        }
    }

    /**
     * Returns if device.addresses elements are correctly formatted.
     * See https://docs.syncthing.net/users/config.html#device-element for what is correct.
     */
    fun checkDeviceAddresses(): Boolean {
        if (!testCheckDeviceAddress()) {
            Log.e(TAG, "checkDeviceAddresses: testCheckDeviceAddress unit test failed")
            return false
        }
        val addresses = this.addresses ?: return false
        for (address in addresses) {
            if (!checkDeviceAddress(address)) {
                return false
            }
        }
        return true
    }

    private fun checkDeviceAddress(address: String): Boolean {
        if (address == "dynamic") {
            return true
        }

        if (!address.matches(Regex("^tcp([46])?://.*$")) &&
            !address.matches(Regex("^relay://.*$")) &&
            !address.matches(Regex("^quic([46])?://.*$"))
        ) {
            return false
        }

        // Separate protocol from address and port.
        val addressSplit = address.split("://")
        if (addressSplit.size == 1) {
            // There's only the protocol given, nothing more.
            return false
        } else if (addressSplit.size == 2) {
            return when {
                addressSplit[0].matches(Regex("^tcp.*$")) -> checkDeviceAddressTcp(addressSplit[1])
                addressSplit[0].matches(Regex("^relay.*$")) -> checkDeviceAddressRelay(addressSplit[1])
                addressSplit[0].matches(Regex("^quic.*$")) -> checkDeviceAddressTcp(addressSplit[1])
                else -> false
            }
        }

        // Protocol is given more than one time. Will match "tcp://tcp://"
        return false
    }

    private fun checkDeviceAddressTcp(address: String): Boolean {
        return checkHostPort(address, false)
    }

    private fun checkDeviceAddressRelay(address: String): Boolean {
        return checkHostPort(address, true)
    }

    /**
     * Validates "hostname:port" style address parts.
     */
    private fun checkHostPort(address: String, firstSegmentIsPort: Boolean): Boolean {
        // Check if the address ends with ":" or "]:"
        if (address.endsWith(":") ||
            address.endsWith("]:")
        ) {
            return false
        }

        // Check if there's a "hostname:port" number given in the part after "://".
        val hostnamePortSplit = address.split(":")
        if (hostnamePortSplit.size > 1) {
            // Check if the hostname or IP address given before the port is empty.
            if (TextUtils.isEmpty(hostnamePortSplit[0])) {
                return false
            }

            // Check if there's a port number given in the last part.
            val potentialPort = (if (firstSegmentIsPort)
                hostnamePortSplit[1]
            else
                hostnamePortSplit[hostnamePortSplit.size - 1]).split("/")[0]
            if (!potentialPort.endsWith("]")) {
                // It's not the end of an IPv6 address and likely a port number.
                val port = try {
                    Integer.parseInt(potentialPort)
                } catch (e: NumberFormatException) {
                    return false
                }
                if (port < 1 || port > 65535) {
                    return false
                }
            }
        }

        return true
    }

    private fun testCheckDeviceAddress(): Boolean {
        var failSuccess = true

        // Positive Syntax
        failSuccess = failSuccess && checkDeviceAddress("tcp://127.0.0.1:4000")
        failSuccess = failSuccess && checkDeviceAddress("tcp4://127.0.0.1:4000")
        failSuccess = failSuccess && checkDeviceAddress("tcp6://127.0.0.1:4000")
        failSuccess = failSuccess && checkDeviceAddress("tcp4://127.0.0.1")
        failSuccess = failSuccess && checkDeviceAddress("tcp://[2001:db8::23:42]")
        failSuccess = failSuccess && checkDeviceAddress("tcp://[2001:db8::23:42]:12345")
        failSuccess = failSuccess && checkDeviceAddress("tcp://myserver")
        failSuccess = failSuccess && checkDeviceAddress("tcp://myserver:12345")
        failSuccess = failSuccess && checkDeviceAddress("relay://stlocal:22067/?id=ID-REDACTED&pingInterval=30s&networkTimeout=2m0s&sessionLimitBps=0&globalLimitBps=0&statusAddr=:22070&providedBy=REDACTED")
        failSuccess = failSuccess && checkDeviceAddress("relay://stlocal:22067")
        failSuccess = failSuccess && checkDeviceAddress("quic://127.0.0.1")
        failSuccess = failSuccess && checkDeviceAddress("quic://127.0.0.1:24000")
        failSuccess = failSuccess && checkDeviceAddress("quic4://127.0.0.1:24000")
        failSuccess = failSuccess && checkDeviceAddress("quic6://127.0.0.1:24000")

        // Negative Syntax
        failSuccess = failSuccess && !checkDeviceAddress("tcp://myserver:")
        failSuccess = failSuccess && !checkDeviceAddress("tcp8://127.0.0.1")
        failSuccess = failSuccess && !checkDeviceAddress("udp4://127.0.0.1")
        return failSuccess
    }

    companion object {
        private const val TAG = "Device"

        /**
         * Validation-only replacement for guava `BaseEncoding.base32().decode(...)`
         * (the decoded bytes are never used): throws [IllegalArgumentException] on the
         * same inputs guava rejects - non base32 characters, invalid lengths and
         * invalid trailing padding. Leftover bits, which guava silently drops, are
         * not checked.
         */
        private fun requireValidBase32(encoded: String) {
            var dataEnd = encoded.length
            while (dataEnd > 0 && encoded[dataEnd - 1] == '=') {
                dataEnd--
            }
            val padding = encoded.length - dataEnd
            require(padding < 8) { "Invalid padding: $encoded" }
            when (dataEnd % 8) {
                1, 3, 6 -> throw IllegalArgumentException("Invalid input length: $encoded")
            }
            for (i in 0 until dataEnd) {
                val c = encoded[i]
                require(c in 'A'..'Z' || c in '2'..'7') { "Unrecognized character: $c" }
            }
        }
    }
}
