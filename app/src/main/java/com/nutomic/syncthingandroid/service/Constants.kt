package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.text.TextUtils

import java.io.File
import java.util.concurrent.TimeUnit

object Constants {

    // Always set ENABLE_TEST_DATA to false before building debug or release APK's.
    const val ENABLE_TEST_DATA = false

    const val FILENAME_SYNCTHING_BINARY        = "libsyncthingnative.so"
    const val FILENAME_STIGNORE                = ".stignore"
    const val FILENAME_STFOLDER                = ".stfolder"
    const val FOLDER_NAME_STVERSIONS           = ".stversions"

    // Preferences - Run conditions
    const val PREF_RUN_ON_MOBILE_DATA          = "run_on_mobile_data"
    const val PREF_RUN_ON_ROAMING              = "run_on_roaming"
    const val PREF_RUN_ON_WIFI                 = "run_on_wifi"
    const val PREF_RUN_ON_METERED_WIFI         = "run_on_metered_wifi"
    const val PREF_USE_WIFI_SSID_WHITELIST     = "use_wifi_whitelist"
    const val PREF_WIFI_SSID_WHITELIST         = "wifi_ssid_whitelist"
    const val PREF_POWER_SOURCE                = "power_source"

    object PowerSource {
        const val CHARGER_BATTERY              = "ac_and_battery_power"
        const val CHARGER                      = "ac_power"
        const val BATTERY                      = "battery_power"
    }

    const val PREF_RESPECT_BATTERY_SAVING      = "respect_battery_saving"
    const val PREF_RESPECT_MASTER_SYNC         = "respect_master_sync"
    const val PREF_RUN_IN_FLIGHT_MODE          = "run_in_flight_mode"
    const val PREF_RUN_ON_TIME_SCHEDULE        = "run_on_time_schedule"
    const val PREF_SYNC_DURATION_MINUTES       = "sync_duration_minutes"
    const val PREF_SLEEP_INTERVAL_MINUTES      = "sleep_interval_minutes"

    // Preferences - User Interface
    const val PREF_APP_THEME                   = "app_theme"
    const val PREF_EXPERT_MODE                 = "expert_mode"
    const val PREF_START_INTO_WEB_GUI          = "start_into_web_gui"

    // Preferences - Behaviour
    const val PREF_START_SERVICE_ON_BOOT       = "always_run_in_background"
    const val PREF_BROADCAST_SERVICE_CONTROL   = "broadcast_service_control"
    const val PREF_ALLOW_OVERWRITE_FILES       = "allow_overwrite_files"

    // Preferences - Syncthing Options
    const val PREF_WEBUI_USERNAME              = "webui_username"
    const val PREF_WEBUI_PASSWORD              = "webui_password"

    // Preferences - Import and Export
    const val PREF_BACKUP_REL_PATH_TO_ZIP      = "backup_rel_path_to_zip"
    const val PREF_BACKUP_PASSWORD             = "backup_password"

    // Preferences - Troubleshooting
    const val PREF_VERBOSE_LOG                 = "verbose_log"
    const val PREF_ENVIRONMENT_VARIABLES       = "environment_variables"
    const val PREF_DEBUG_FACILITIES_ENABLED    = "debug_facilities_enabled"

    // Preferences - Experimental
    const val PREF_USE_TOR                     = "use_tor"
    const val PREF_SOCKS_PROXY_ADDRESS         = "socks_proxy_address"
    const val PREF_HTTP_PROXY_ADDRESS          = "http_proxy_address"

    // Preferences - per Folder and Device Sync Conditions
    const val PREF_OBJECT_PREFIX_FOLDER        = "sc_folder_"
    const val PREF_OBJECT_PREFIX_DEVICE        = "sc_device_"

    // Preferences: Recent Changes screen
    const val PREF_SHOW_EXACT_TIMES            = "recent_changes_show_exact_times"

    // Preferences - Home screen
    const val PREF_HOME_COLLAPSED_FOLDER_GROUPS = "home_collapsed_folder_groups"
    const val PREF_HOME_COLLAPSED_DEVICE_GROUPS = "home_collapsed_device_groups"

    fun DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(objectPrefixAndId: String): String {
        return objectPrefixAndId + "_" + "custom_sync_conditions"
    }

    fun DYN_PREF_OBJECT_FOLDER_RUN_SCRIPT(folderId: String): String {
        return PREF_OBJECT_PREFIX_FOLDER + folderId + "_" + "run_script"
    }

    fun DYN_PREF_OBJECT_SYNC_ON_WIFI(objectPrefixAndId: String): String {
        return objectPrefixAndId + "_" + PREF_RUN_ON_WIFI
    }

    fun DYN_PREF_OBJECT_USE_WIFI_SSID_WHITELIST(objectPrefixAndId: String): String {
        return objectPrefixAndId + "_" + PREF_USE_WIFI_SSID_WHITELIST
    }

    fun DYN_PREF_OBJECT_SELECTED_WHITELIST_SSID(objectPrefixAndId: String): String {
        return objectPrefixAndId + "_" + PREF_WIFI_SSID_WHITELIST
    }

    fun DYN_PREF_OBJECT_SYNC_ON_METERED_WIFI(objectPrefixAndId: String): String {
        return objectPrefixAndId + "_" + PREF_RUN_ON_METERED_WIFI
    }

    fun DYN_PREF_OBJECT_SYNC_ON_MOBILE_DATA(objectPrefixAndId: String): String {
        return objectPrefixAndId + "_" + PREF_RUN_ON_MOBILE_DATA
    }

    fun DYN_PREF_OBJECT_SYNC_ON_POWER_SOURCE(objectPrefixAndId: String): String {
        return objectPrefixAndId + "_" + PREF_POWER_SOURCE
    }

    /**
     * Cached information which is not available on SettingsActivity.
     */
    const val PREF_ENABLE_SYNCTHING_CAMERA     = "enableSyncthingCamera"
    const val PREF_KNOWN_WIFI_SSIDS            = "knownWifiSsids"
    const val PREF_LAST_BINARY_VERSION         = "lastBinaryVersion"
    const val PREF_LOCAL_DEVICE_ID             = "localDeviceID"
    /**
     * Run the syncthing core as root via su (rooted devices only). The core then gains
     * unrestricted filesystem access; app-shared files stay writable thanks to the
     * umask 000 wrapper the launch path applies.
     */
    const val PREF_RUN_AS_ROOT                 = "run_as_root"
    /**
     * Cached: the privilege mode the currently/last running core was actually launched
     * with. Shutdown paths must use this (not the user preference) so a core keeps being
     * killable/inspectable right after the user toggles the preference.
     */
    const val PREF_LAST_CORE_RUN_AS_ROOT       = "last_core_run_as_root"
    // from SystemClock.elapsedRealtime()
    const val PREF_LAST_RUN_TIME               = "last_run_time"
    const val PREF_APP_START_COUNTER           = "app_start_counter"

    /**
     * Cached device stats.
     */
    const val PREF_CACHE_DEVICE_LASTSEEN_PREFIX        = "device_lastseen_"

    /**
     * {@link ConfigXml#addSyncthingCameraFolder}
     */
    const val syncthingCameraFolderId          = "syncthingAndroidCamera-52x89-60es4"

    /**
     * {@link RunConditionMonitor}
     */
    const val PREF_BTNSTATE_FORCE_START_STOP   = "btnStateForceStartStop"

    const val BTNSTATE_NO_FORCE_START_STOP        = 0
    const val BTNSTATE_FORCE_START                = 1
    const val BTNSTATE_FORCE_STOP                 = 2

    /**
     * {@link EventPoller}
     */
    const val PREF_EVENT_PROCESSOR_LAST_SYNC_ID = "last_sync_id"

    /**
     * Available options cache for preference {@link com.nutomic.syncthingandroid.R.xml#app_settings#debug_facilities_enabled}
     * Read via REST API call in {@link RestApi#updateDebugFacilitiesCache} after first successful binary startup.
     */
    const val PREF_DEBUG_FACILITIES_AVAILABLE  = "debug_facilities_available"

    /**
     * Available folder types.
     */
    const val FOLDER_TYPE_SEND_ONLY            = "sendonly"
    const val FOLDER_TYPE_SEND_RECEIVE         = "sendreceive"
    const val FOLDER_TYPE_RECEIVE_ONLY         = "receiveonly"
    const val FOLDER_TYPE_RECEIVE_ENCRYPTED    = "receiveencrypted"

    /**
     * Default listening ports.
     */
    const val DEFAULT_WEBGUI_TCP_PORT         = 8384

    /**
     * Alternative Web GUI and data listen addresses used by debug builds, so
     * debug and release can run in parallel for testing purposes.
     */
    const val DEBUG_WEBGUI_BIND_ADDRESS         = "127.0.0.1:8385"
    const val DEBUG_DATA_LISTEN_ADDRESS         = "tcp://:22001"

    /**
     * Default address of the local Tor SOCKS proxy.
     */
    const val DEFAULT_TOR_SOCKS_PROXY_ADDRESS   = "socks5://localhost:9050"
    const val DEFAULT_DATA_TCP_PORT           = 22000


    /**
     * Interval in ms at which RestAPI is polled.
     * As a rule of thumb: Poll faster on "modern" devices.
     */
    val REST_UPDATE_INTERVAL = TimeUnit.SECONDS.toMillis(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                3
            else
                5
    )

    val GUI_UPDATE_INTERVAL = TimeUnit.SECONDS.toMillis(1)

    /**
     * File in the config folder that contains configuration.
     */
    const val CONFIG_FILE = "config.xml"

    fun getConfigFile(context: Context): File {
        return File(context.filesDir, CONFIG_FILE)
    }

    /**
     * File in the config folder we write to temporarily before renaming to CONFIG_FILE.
     */
    private const val CONFIG_TEMP_FILE = "config.xml.tmp"

    fun getConfigTempFile(context: Context): File {
        return File(context.filesDir, CONFIG_TEMP_FILE)
    }

    /**
     * Name of the public key file in the data directory.
     */
    const val PUBLIC_KEY_FILE = "cert.pem"

    fun getPublicKeyFile(context: Context): File {
        return File(context.filesDir, PUBLIC_KEY_FILE)
    }

    /**
     * Name of the private key file in the data directory.
     */
    const val PRIVATE_KEY_FILE = "key.pem"

    fun getPrivateKeyFile(context: Context): File {
        return File(context.filesDir, PRIVATE_KEY_FILE)
    }

    /**
     * Name of the folder containing the index database.
     */
    private const val INDEX_DB_FOLDER = "index-v2"

    fun getIndexDbFolder(context: Context): File {
        return File(context.filesDir, INDEX_DB_FOLDER)
    }

    /**
     * Name of the public HTTPS CA file in the data directory.
     */
    const val HTTPS_CERT_FILE = "https-cert.pem"

    fun getHttpsCertFile(context: Context): File {
        return File(context.filesDir, HTTPS_CERT_FILE)
    }

    /**
     * Name of the HTTPS CA key file in the data directory.
     */
    const val HTTPS_KEY_FILE = "https-key.pem"

    fun getHttpsKeyFile(context: Context): File {
        return File(context.filesDir, HTTPS_KEY_FILE)
    }

    /**
     * Name of the file holding the SharedPreferences backup.
     * Do not use getCacheDir() because the path to import will then be wrong as
     * zipFile.extractAll will write to getFilesDir().
     */
    const val SHARED_PREFS_FILE = "sharedpreferences.dat"

    fun getSharedPrefsFile(context: Context): File {
        return File(context.filesDir, SHARED_PREFS_FILE)
    }

    /**
     * Get libsyncthingnative.so absolute path and filename.
     */
    fun getSyncthingBinary(context: Context): File {
        return File(context.applicationInfo.nativeLibraryDir, FILENAME_SYNCTHING_BINARY)
    }

    /**
     * Log file storage locations.
     */
    fun getAndroidLogFile(context: Context): File {
        // e.g. /data/data/${applicationId}/cache/android.log
        return File(context.cacheDir, "android.log")
    }

    fun getSyncthingLogFile(context: Context): File {
        // e.g. /data/data/${applicationId}/files/syncthing.log
        return File(context.filesDir, "syncthing.log")
    }

    /**
     * Checks if the app is running on an Android emulator (AVD).
     */
    fun isRunningOnEmulator(): Boolean {
        return !TextUtils.isEmpty(Build.MANUFACTURER) &&
                !TextUtils.isEmpty(Build.MODEL) &&
                        (
                            Build.MANUFACTURER == "Google" ||
                            Build.MANUFACTURER == "unknown"
                        ) && (
                                Build.MODEL == "Android SDK built for x86" ||
                                Build.MODEL == "Android SDK built for x86_64" ||
                                Build.MODEL == "sdk_gphone_x86_arm"
                        )
    }

    fun isDebuggable(context: Context): Boolean {
        return (0 != (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE))
    }

    /**
     * Decide if we should enforce HTTPS when accessing the Web UI and REST API.
     * Android 4.4 and earlier don't have support for TLS 1.2 requiring us to
     * fall back to an unencrypted HTTP connection to localhost. This applies
     * to syncthing core v0.14.53+.
     */
    fun osSupportsTLS12(): Boolean {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.N) {
            /**
             * SSLProtocolException: SSL handshake failed on Android N/7.0,
             * missing support for elliptic curves.
             * See https://issuetracker.google.com/issues/37122132
             */
            return false
        }

        return true
    }

    /**
     * Detect kernels with a bug causing kernel oops when
     * Syncthing v1.3.0+ attempts to enable the NAT feature.
     */
    fun osHasKernelBugIssue505(): Boolean {
        val kernelVersion: String? = java.lang.System.getProperty("os.version")
        if (kernelVersion == null) {
            return false
        }
        /**
         * Affected kernels:
         * Samsung Note N7000 - LOS 16 - Android 9 - 3.0.101-gf32669ee5be #1 Tue Apr 7 20:05:58 +08 2020
         */
        return kernelVersion.startsWith("3.0.") ||
                kernelVersion.startsWith("3.4.")
    }
}
