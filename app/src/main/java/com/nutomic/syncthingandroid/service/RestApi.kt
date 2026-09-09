package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.activities.ShareActivity
import com.nutomic.syncthingandroid.http.ApiClient
import com.nutomic.syncthingandroid.model.CachedFolderStatus
import com.nutomic.syncthingandroid.model.CompletionInfo
import com.nutomic.syncthingandroid.model.Config
import com.nutomic.syncthingandroid.model.Connection
import com.nutomic.syncthingandroid.model.Connections
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.DeviceStat
import com.nutomic.syncthingandroid.model.DiscoveredDevice
import com.nutomic.syncthingandroid.model.Event
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.FolderIgnoreList
import com.nutomic.syncthingandroid.model.FolderStatus
import com.nutomic.syncthingandroid.model.Gui
import com.nutomic.syncthingandroid.model.IgnoredFolder
import com.nutomic.syncthingandroid.model.LocalCompletion
import com.nutomic.syncthingandroid.model.Options
import com.nutomic.syncthingandroid.model.PendingDevice
import com.nutomic.syncthingandroid.model.PendingFolder
import com.nutomic.syncthingandroid.model.RemoteCompletion
import com.nutomic.syncthingandroid.model.RemoteCompletionInfo
import com.nutomic.syncthingandroid.model.RemoteIgnoredDevice
import com.nutomic.syncthingandroid.model.SharedWithDevice
import com.nutomic.syncthingandroid.model.SystemStatus
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.Util
import com.nutomic.syncthingandroid.util.Util.getLocalZonedDateTime
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.AbstractMap
import java.util.Collections
import java.util.HashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Provides functions to interact with the syncthing REST API.
 *
 * Ported from Java in phase6b; the public API surface is unchanged. Requests still go
 * through [ApiClientBridge] (callback style) so existing callers keep working; the suspend
 * layer and cached StateFlows are added on top of this class.
 */

class RestApi(
    context: Context,
    url: URL,
    val apiKey: String,
    apiListener: OnApiAvailableListener,
    configListener: OnConfigChangedListener,
    scope: CoroutineScope? = null,
    private val runAsRoot: Boolean = false,
) {

    companion object {
        private const val TAG = "RestApi"

        /**
         * The versioning cleanup workaround is triggered every Nth app start to save resources.
         */
        private const val VERSIONING_CLEANUP_STARTUP_INTERVAL = 10
        private const val VERSIONING_CLEANUP_INTERVAL_S_TEMPORARY = 2
        private const val VERSIONING_CLEANUP_INTERVAL_S_DEFAULT = 3600
        private const val VERSIONING_CLEANUP_RESET_DELAY_MS = 10000L

        /**
         * Process name of the "find" utility that Syncthing's versioning cleanup relies on.
         * Leftover instances are killed before re-triggering the cleanup.
         */
        private const val VERSIONING_CLEANUP_PROCESS_NAME = "find"

        /**
         * Intents we sent to to other apps that subscribed to us.
         */
        private const val ACTION_NOTIFY_FOLDER_SYNC_COMPLETE = ".ACTION_NOTIFY_FOLDER_SYNC_COMPLETE"

        /**
         * Permission for apps receiving our broadcast intents.
         */
        private const val PERMISSION_RECEIVE_SYNC_STATUS = ".permission.RECEIVE_SYNC_STATUS"
    }

    fun interface OnApiAvailableListener {
        fun onApiAvailable()
    }

    fun interface OnConfigChangedListener {
        fun onConfigChanged()
    }

    fun interface OnResultListener1<T> {
        fun onResult(t: T)
    }

    interface OnReceiveEventListener {
        fun onError()

        /**
         * Called for each event.
         */
        fun onEvent(event: Event, json: JsonElement)

        /**
         * Called after all available events have been processed.
         * @param lastId The id of the last event processed. Should be used as a starting point for
         * the next round of event processing.
         */
        fun onDone(lastId: Long)
    }

    private val context: Context = context
    private var url: URL = url

    /**
     * Scope for fire-and-forget requests and the legacy callback API. Callbacks are
     * delivered on the main thread like Volley did (phase6a); tests may inject a scope
     * for deterministic delivery.
     */
    private val restScope: CoroutineScope =
        scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val httpsCertFile: File = Constants.getHttpsCertFile(context)

    /**
     * One [ApiClient] per GUI address (each owns an OkHttpClient connection pool).
     * The address can change at runtime (settings edit), which simply allocates
     * a new cache entry.
     */
    private val clients = ConcurrentHashMap<String, ApiClient>()

    /**
     * Overall sync completion, cached as a StateFlow (phase6b). Event-driven: updated
     * by [onTotalSyncCompletionChange] whenever the folder/device completion caches
     * change in a way that alters the aggregate. -1 means "not applicable / unknown".
     */
    private val mutableTotalSyncCompletion = MutableStateFlow(-1)
    val totalSyncCompletion: StateFlow<Int> = mutableTotalSyncCompletion.asStateFlow()

    /**
     * Results cached from systemInfo.
     */
    private var localDeviceId: String? = null
    private var urVersionMax: Int? = null

    /**
     * Stores the result of the last successful request to [ApiClient.URI_CONNECTIONS],
     * or null.
     */
    private var previousConnections: Connections? = null

    /**
     * Stores the timestamp of the last result of the REST API endpoint [ApiClient.URI_CONNECTIONS].
     */
    private var previousConnectionTime: Long = 0

    /**
     * In the last-finishing [readConfigFromRestApi] callback, we have to call
     * [SyncthingService.onApiAvailable] to indicate that the RestApi class is fully initialized.
     * We do this to avoid getting stuck with our main thread due to synchronous REST queries.
     * The correct indication of full initialisation is crucial to stability as other listeners of
     * SettingsActivity#SettingsFragment#onServiceStateChange need cached config and system
     * information available, e.g. SettingsFragment needs "localDeviceId".
     */
    private var asyncQueryConfigComplete = false
    private var asyncQueryVersionComplete = false
    private var asyncQuerySystemStatusComplete = false

    /**
     * Object that must be locked upon accessing the following variables:
     * asyncQueryConfigComplete, asyncQueryVersionComplete, asyncQuerySystemStatusComplete
     */
    private val asyncQueryCompleteLock = Any()

    /**
     * Object that must be locked upon accessing [config]
     */
    private val configLock = Any()

    private val enableVerboseLog: Boolean = AppPrefs.getPrefVerboseLog(context)

    /**
     * Stores the latest result of device and folder completion events.
     */
    private val localCompletion = LocalCompletion(enableVerboseLog)
    private val remoteCompletion = RemoteCompletion(enableVerboseLog)
    private var lastOnlineDeviceCount = 0
    private var lastTotalSyncCompletion = -1

    private var hasShutdown = false

    private val gson: Gson = GsonBuilder().create()

    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()

    lateinit var notificationHandler: NotificationHandler

    private val onApiAvailableListener: OnApiAvailableListener = apiListener
    private val onConfigChangedListener: OnConfigChangedListener = configListener

    /**
     * Returns the version name, or null before the first successful version query.
     */
    var version: String? = null
        private set

    private var config: Config? = null

    init {
        val app = context.applicationContext as SyncthingApp
        notificationHandler = app.notificationHandler
    }

    private fun clientFor(targetUrl: URL): ApiClient =
        clients.getOrPut(targetUrl.toString()) { ApiClient(httpsCertFile, targetUrl, apiKey) }

    /**
     * Fire-and-forget GET with optional success/error callbacks, mirroring the deleted
     * ApiClientBridge: returns immediately, exactly one of the callbacks runs later on
     * [restScope] (main thread in production). A null [onError] means "log and swallow"
     * (ApiClient already logs each failure).
     */
    private fun apiGet(
        path: String,
        params: Map<String, String>? = null,
        onSuccess: ((String) -> Unit)? = null,
        onError: ((IOException) -> Unit)? = null,
    ) {
        val targetUrl = url
        restScope.launch {
            try {
                // NOTE: request first, invoke after - `onSuccess?.invoke(post(...))` would
                // skip the request entirely when the callback is null (safe-call evaluation).
                val result = clientFor(targetUrl).get(path, params ?: emptyMap())
                onSuccess?.invoke(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                onError?.invoke(e)
            }
        }
    }

    /** Fire-and-forget POST, see [apiGet]. */
    private fun apiPost(
        path: String,
        params: Map<String, String>? = null,
        body: String? = null,
        onSuccess: ((String) -> Unit)? = null,
    ) {
        val targetUrl = url
        restScope.launch {
            try {
                // NOTE: request first, invoke after - `onSuccess?.invoke(post(...))` would
                // skip the request entirely when the callback is null (safe-call evaluation).
                val result = clientFor(targetUrl).post(path, params ?: emptyMap(), body)
                onSuccess?.invoke(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Swallowed like the old bridge's default error handler; ApiClient logged it.
            }
        }
    }

    /**
     * Gets local device ID, syncthing version and config, then calls all OnApiAvailableListeners.
     */
    fun readConfigFromRestApi() {
        LogV("Querying config from REST ...")
        synchronized(asyncQueryCompleteLock) {
            asyncQueryVersionComplete = false
            asyncQueryConfigComplete = false
            asyncQuerySystemStatusComplete = false
        }
        apiGet(ApiClient.URI_VERSION, null, { result ->
            val json = JsonParser.parseString(result).asJsonObject
            version = json.get("version").asString
            updateDebugFacilitiesCache()
            synchronized(asyncQueryCompleteLock) {
                asyncQueryVersionComplete = true
                checkReadConfigFromRestApiCompleted()
            }
        }, { })
        apiGet(ApiClient.URI_CONFIG, null, { result ->
            onReloadConfigComplete(result)
            synchronized(asyncQueryCompleteLock) {
                asyncQueryConfigComplete = true
                checkReadConfigFromRestApiCompleted()
            }
        }, { })
        getSystemStatus { info ->
            localDeviceId = info.myID
            urVersionMax = info.urVersionMax
            synchronized(asyncQueryCompleteLock) {
                asyncQuerySystemStatusComplete = true
                checkReadConfigFromRestApiCompleted()
            }
        }
    }

    private fun checkReadConfigFromRestApiCompleted() {
        if (asyncQueryVersionComplete &&
            asyncQueryConfigComplete &&
            asyncQuerySystemStatusComplete
        ) {
            LogV("Reading config from REST completed. Syncthing version is $version")
            // Tell SyncthingService it can transition to State.ACTIVE.
            onApiAvailableListener.onApiAvailable()

            triggerVersioningCleanupIfNecessary()
        }
    }

    private fun triggerVersioningCleanupIfNecessary() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        var startupCounter = sharedPreferences.getInt(Constants.PREF_APP_START_COUNTER, 0) + 1
        startupCounter = if (startupCounter == Int.MAX_VALUE) 1 else startupCounter
        sharedPreferences.edit()
            .putInt(Constants.PREF_APP_START_COUNTER, startupCounter)
            .apply()
        val shouldRunWorkaround = startupCounter % VERSIONING_CLEANUP_STARTUP_INTERVAL == 0
        if (!shouldRunWorkaround) {
            LogV("Skipping versioning cleanup because it is only triggered every $VERSIONING_CLEANUP_STARTUP_INTERVAL th startup to save resources.")
            return
        }

        // Temporarily lower cleanupIntervalS for every folder to force cleanup after startup.
        setVersioningCleanupIntervalS(VERSIONING_CLEANUP_INTERVAL_S_TEMPORARY)
        val resetCleanupIntervalHandler = Handler(Looper.getMainLooper())
        resetCleanupIntervalHandler.postDelayed({
            if (hasShutdown) {
                LogV("Skipping resetting the versioning cleanup interval due to hasShutdown == true")
                return@postDelayed
            }
            setVersioningCleanupIntervalS(VERSIONING_CLEANUP_INTERVAL_S_DEFAULT)
        }, VERSIONING_CLEANUP_RESET_DELAY_MS)
    }

    fun reloadConfig() {
        apiGet(ApiClient.URI_CONFIG, null, { result ->
            onReloadConfigComplete(result)
        }, { })
    }

    private fun onReloadConfigComplete(configResult: String) {
        val configParseSuccess: Boolean
        synchronized(configLock) {
            config = gson.fromJson(configResult, Config::class.java)
            configParseSuccess = config != null
        }
        if (!configParseSuccess) {
            throw RuntimeException("config is null: $configResult")
        }
        Log.d(TAG, "onReloadConfigComplete: Successfully parsed configuration.")

        synchronized(configLock) {
            val logRemoteIgnoredDevices = gson.toJson(config!!.remoteIgnoredDevices)
            if (logRemoteIgnoredDevices != "[]") {
                LogV("ORCC: remoteIgnoredDevices = $logRemoteIgnoredDevices")
            }

            // Loop through devices to get ignoredFolders per device.
            for (device in getDevices(false)) {
                val logIgnoredFolders = gson.toJson(device.ignoredFolders)
                if (logIgnoredFolders != "[]") {
                    LogV("ORCC: device[${device.displayName}].ignoredFolders = $logIgnoredFolders")
                }
            }
        }

        apiGet(ApiClient.URI_PENDING_DEVICES, null, { result ->
            val jsonObject = JsonParser.parseString(result).asJsonObject
            for (deviceEntry in jsonObject.entrySet()) {
                val resultDeviceId = deviceEntry.key ?: continue
                val pendingDevice = gson.fromJson(deviceEntry.value, PendingDevice::class.java)
                if (pendingDevice.time == null) {
                    continue
                }
                Log.d(TAG, "ORCC: resultDeviceId = $resultDeviceId ('${pendingDevice.name}')")
                notificationHandler.showDeviceConnectNotification(
                    resultDeviceId,
                    pendingDevice.name,
                    pendingDevice.address
                )
            }
        }, { })
        apiGet(ApiClient.URI_PENDING_FOLDERS, null, { result ->
            val jsonObject = JsonParser.parseString(result).asJsonObject
            for (folderEntry in jsonObject.entrySet()) {
                val resultFolderId = folderEntry.key ?: continue
                val jsonObjectOfferedBy = folderEntry.value.asJsonObject.get("offeredBy").asJsonObject
                for (offeredByEntry in jsonObjectOfferedBy.entrySet()) {
                    val offeredByDeviceId = offeredByEntry.key ?: continue
                    val pendingFolder = gson.fromJson(offeredByEntry.value, PendingFolder::class.java)
                    Log.d(TAG, "ORCC: resultFolderId = $resultFolderId ('${pendingFolder.label}')")
                    val matchingDevice = getDevices(false).firstOrNull {
                        it.deviceID == offeredByDeviceId
                    }
                    if (matchingDevice == null) {
                        Log.w(TAG, "ORCC: No matching device for deviceId=[$offeredByDeviceId]")
                        continue
                    }
                    val isNewFolder = folders.none { it.id == resultFolderId }
                    notificationHandler.showFolderShareNotification(
                        offeredByDeviceId,
                        matchingDevice.displayName,
                        resultFolderId,
                        pendingFolder.label,
                        pendingFolder.receiveEncrypted,
                        isNewFolder
                    )
                }
            }
        }, { })

        // Update cached device and folder information.
        val tmpFolders = folders
        localCompletion.updateFromConfig(tmpFolders)
        remoteCompletion.updateFromConfig(getDevices(true), tmpFolders)

        // Perform first query for remote device status by forcing a cache miss.
        getRemoteDeviceStatus("")

        for (folder in tmpFolders) {
            for (device in folder.getSharedWithDevices()) {
                apiGet(ApiClient.URI_DB_COMPLETION,
                    params("device" to device.deviceID, "folder" to folder.id),
                    { result ->
                        val completionInfo = gson.fromJson(result, CompletionInfo::class.java)
                        LogV("ORCC: /rest/db/completion: folder=${folder.id}" +
                            ", device=${device.displayName}" +
                            ", completion=${completionInfo.completion}" +
                            ", needBytes=${String.format(Locale.getDefault(), "%.0f", completionInfo.needBytes)}" +
                            ", remoteState=${completionInfo.remoteState}")
                        val remoteCompletionInfo = RemoteCompletionInfo()
                        remoteCompletionInfo.completion = completionInfo.completion
                        remoteCompletionInfo.needBytes = completionInfo.needBytes
                        remoteCompletion.setCompletionInfo(device.deviceID, folder.id, remoteCompletionInfo)
                    }, { })
            }
        }
    }

    /**
     * Queries debug facilities available from the currently running syncthing binary
     * if the syncthing binary version changed. First launch of the binary is also
     * considered as a version change.
     * Precondition: [version] read from REST
     */
    private fun updateDebugFacilitiesCache() {
        if (version != PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.PREF_LAST_BINARY_VERSION, "")
        ) {
            // First binary launch or binary upgraded case.
            apiGet(ApiClient.URI_SYSTEM_LOGLEVELS, null, { result ->
                try {
                    val facilitiesToStore = HashSet<String>()
                    val json = JsonParser.parseString(result).asJsonObject
                    val jsonFacilities = json.getAsJsonObject("packages")
                    for (facilityName in jsonFacilities.keySet()) {
                        facilitiesToStore.add(facilityName)
                    }
                    PreferenceManager.getDefaultSharedPreferences(context).edit()
                        .putStringSet(Constants.PREF_DEBUG_FACILITIES_AVAILABLE, facilitiesToStore)
                        .apply()

                    // Store current binary version so we will only store this information again
                    // after a binary update.
                    PreferenceManager.getDefaultSharedPreferences(context).edit()
                        .putString(Constants.PREF_LAST_BINARY_VERSION, version)
                        .apply()
                } catch (e: Exception) {
                    Log.w(TAG, "updateDebugFacilitiesCache: Failed to get debug facilities. result=$result", e)
                }
            }, { })
        }
    }

    /**
     * Permanently ignore a device when it tries to connect.
     * Ignored devices will not trigger the "DeviceRejected" event
     * in [EventPoller.onEvent].
     */
    fun ignoreDevice(deviceId: String, deviceName: String?, deviceAddress: String?) {
        synchronized(configLock) {
            // Check if the device has already been ignored.
            for (remoteIgnoredDevice in config!!.remoteIgnoredDevices!!) {
                if (deviceId == remoteIgnoredDevice.deviceID) {
                    // Device already ignored.
                    Log.d(TAG, "Device already ignored [$deviceId]")
                    return
                }
            }

            // Ignore device by moving its corresponding "pendingDevice" entry to
            // a newly created "remoteIgnoredDevice" entry.
            val remoteIgnoredDevice = RemoteIgnoredDevice()
            remoteIgnoredDevice.deviceID = deviceId
            remoteIgnoredDevice.address = deviceAddress ?: ""
            remoteIgnoredDevice.name = deviceName ?: ""
            remoteIgnoredDevice.time = getLocalZonedDateTime()
            config!!.remoteIgnoredDevices!!.add(remoteIgnoredDevice)
            sendConfig()
            Log.d(TAG, "Ignored device [$deviceId]")
        }
    }

    /**
     * Permanently ignore a folder share request.
     * Ignored folders will not trigger the "FolderRejected" event
     * in [EventPoller.onEvent].
     */
    fun ignoreFolder(deviceId: String, folderId: String, folderLabel: String?) {
        synchronized(configLock) {
            for (device in config!!.devices!!) {
                if (deviceId == device.deviceID) {
                    // Check if the folder has already been ignored.
                    for (ignoredFolder in device.ignoredFolders!!) {
                        if (folderId == ignoredFolder.id) {
                            // Folder already ignored.
                            Log.d(TAG, "ignoreFolder: Folder [$folderId] already ignored on device [$deviceId]")
                            return
                        }
                    }

                    // Ignore folder by moving its corresponding "pendingFolder" entry to
                    // a newly created "ignoredFolder" entry.
                    val ignoredFolder = IgnoredFolder()
                    ignoredFolder.id = folderId
                    ignoredFolder.label = folderLabel ?: ""
                    ignoredFolder.time = getLocalZonedDateTime()
                    device.ignoredFolders!!.add(ignoredFolder)
                    LogV("ignoreFolder: device.ignoredFolders = ${gson.toJson(device.ignoredFolders)}")
                    sendConfig()
                    Log.d(TAG, "Ignored folder [$folderId] announced by device [$deviceId]")

                    // Given deviceId handled.
                    break
                }
            }
        }
    }

    /**
     * Undo ignoring devices and folders.
     */
    fun undoIgnoredDevicesAndFolders() {
        Log.d(TAG, "Undo ignoring devices and folders ...")
        synchronized(configLock) {
            config!!.remoteIgnoredDevices!!.clear()
            for (device in config!!.devices!!) {
                device.ignoredFolders!!.clear()
            }
        }
    }

    /**
     * Override folder changes. This is the same as hitting
     * the "override changes" button from the web UI.
     */
    fun overrideChanges(folderId: String) {
        Log.d(TAG, "overrideChanges '$folderId'")
        apiPost(ApiClient.URI_DB_OVERRIDE, params("folder" to folderId))
    }

    /**
     * Rescan all folders
     */
    fun rescanAll() {
        Log.d(TAG, "rescanAll")
        apiPost(ApiClient.URI_DB_SCAN)
    }

    /**
     * Rescans the folder whose configured path equals [folderPath], if any.
     * Used by [SafBridge] to tell the core about forwarded-dir changes that
     * happened without a reliable filesystem notification (SAF pull, config import).
     * No-op when no folder is configured at that path (e.g. folder just removed).
     */
    fun rescanFolderByPath(folderPath: String) {
        val folderId = synchronized(configLock) {
            config?.folders?.firstOrNull { it.path == folderPath }?.id
        }
        if (folderId == null) {
            Log.d(TAG, "rescanFolderByPath: No configured folder at '$folderPath', skipping")
            return
        }
        Log.d(TAG, "rescanFolderByPath: '$folderPath' -> folder '$folderId'")
        apiPost(ApiClient.URI_DB_SCAN, params("folder" to folderId))
    }

    /**
     * Revert local folder changes. This is the same as hitting
     * the "Revert local changes" button from the web UI.
     */
    fun revertLocalChanges(folderId: String) {
        Log.d(TAG, "revertLocalChanges '$folderId'")
        apiPost(ApiClient.URI_DB_REVERT, params("folder" to folderId))
    }

    val webGuiUrl: URL
        get() = synchronized(configLock) {
            val gui = config!!.gui
            if (gui?.address == null) {
                Log.e(TAG, "getWebGuiUrl: config.gui.address == null, returning 127.0.0.1:${Constants.DEFAULT_WEBGUI_TCP_PORT}")
                return@synchronized Util.buildWebGuiUrl("127.0.0.1:" + Constants.DEFAULT_WEBGUI_TCP_PORT)
            }
            Util.buildWebGuiUrl(gui.address!!)
        }

    /**
     * Sends current config to Syncthing.
     * Will result in a "ConfigSaved" event.
     * EventPoller will trigger this.reloadConfig().
     */
    fun sendConfig() {
        val jsonConfig: String
        synchronized(configLock) {
            jsonConfig = gson.toJson(config)
        }
        apiPost(ApiClient.URI_SYSTEM_CONFIG, null, jsonConfig)
        url = webGuiUrl
        onConfigChangedListener.onConfigChanged()
    }

    /**
     * Posts shutdown request.
     * This will cause SyncthingNative to exit and not restart.
     */
    fun shutdown() {
        hasShutdown = true
        executorService.shutdownNow()
        // Util.killProcess polls via "ps" and must not run on the main thread. Cleaning up a
        // stale versioning "find" helper has no ordering dependency with the shutdown POST.
        // The helper is a child of the syncthing core, so in root mode it runs as root too
        // and both ps and kill must go through the root shell.
        restScope.launch(Dispatchers.IO) {
            // Under root, a bare "find" name match would signal every find process on the
            // system. Narrow it down to instances operating on OUR folder paths - the
            // core spawns the versioning helper with the folder as its search root, which
            // covers both live and orphaned leftovers.
            val argFilters: List<String>? = try {
                folders.map { it.path }
            } catch (e: Exception) {
                Log.w(TAG, "shutdown: Failed to collect folder paths for find filter", e)
                null
            }
            if (argFilters != null) {
                Util.killProcess(VERSIONING_CLEANUP_PROCESS_NAME, runAsRoot, argFilters)
            } else if (!runAsRoot) {
                // Config unavailable: keep the UID-scoped (safe) non-root cleanup, but
                // never fall back to an unfiltered root kill of every "find" process.
                Util.killProcess(VERSIONING_CLEANUP_PROCESS_NAME)
            }
        }
        apiPost(ApiClient.URI_SYSTEM_SHUTDOWN)
    }

    val folders: List<Folder>
        get() {
            val folders: List<Folder> = synchronized(configLock) {
                Util.deepCopy(config!!.folders!!, object : TypeToken<List<Folder>>() {}.type)
            }
            for (folder in folders) {
                if (folder.path.startsWith("~/")) {
                    folder.path = folder.path.replaceFirst("^~".toRegex(), FileUtils.getSyncthingTildeAbsolutePath())
                }
            }
            Collections.sort(folders, Folder.LABEL_COMPARATOR)
            return folders
        }

    /**
     * Looks a folder up in the live config WITHOUT the deep copy [getFolderByID] performs.
     *
     * This is the hot path for event handling (setLocalFolderLastItemFinished,
     * setRemoteCompletionInfo, folder status refresh): the full-config Gson deep copy used to
     * run once per event on the main thread and was a measurable source of scroll jank during
     * active syncs. Callers MUST treat the result as read-only - it is shared with the live
     * config. Returns null when the config is not loaded or the id is unknown.
     */
    internal fun findFolderReadonly(folderID: String): Folder? {
        if (Constants.ENABLE_TEST_DATA && folderID == "abcd-efgh") {
            val folder = Folder()
            folder.id = "abcd-efgh"
            folder.label = "label_abcd-efgh"
            folder.path = "/storage/emulated/0/testdata"
            folder.type = Constants.FOLDER_TYPE_SEND_RECEIVE
            return folder
        }

        synchronized(configLock) {
            return config?.folders?.firstOrNull { it.id == folderID }
        }
    }

    fun getFolderByID(folderID: String): Folder? {
        if (Constants.ENABLE_TEST_DATA && folderID == "abcd-efgh") {
            val folder = Folder()
            folder.id = "abcd-efgh"
            folder.label = "label_abcd-efgh"
            folder.path = "/storage/emulated/0/testdata"
            folder.type = Constants.FOLDER_TYPE_SEND_RECEIVE
            return folder
        }

        for (folder in folders) {
            if (folder.id == folderID) {
                return folder
            }
        }
        return null
    }

    /**
     * This is only used for new folder creation, see FolderActivity.
     */
    fun addFolder(folder: Folder) {
        synchronized(configLock) {
            // Replace an existing folder with the same id instead of adding a
            // duplicate (e.g. when the user saves twice).
            removeFolderInternal(folder.id)
            // Add the new folder to the model.
            config!!.folders!!.add(folder)
            // Send model changes to syncthing, does not require a restart.
            sendConfig()
        }
    }

    fun updateFolder(newFolder: Folder) {
        synchronized(configLock) {
            removeFolderInternal(newFolder.id)
            config!!.folders!!.add(newFolder)
            sendConfig()
        }
    }

    fun removeFolder(id: String) {
        synchronized(configLock) {
            removeFolderInternal(id)
            // localCompletion will be updated after the ConfigSaved event.
            // remoteCompletion will be updated after the ConfigSaved event.
            sendConfig()
            // Remove saved data from share activity for this folder.
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(ShareActivity.PREF_FOLDER_SAVED_SUBDIRECTORY + id)
            .apply()
    }

    private fun removeFolderInternal(id: String) {
        synchronized(configLock) {
            val iterator = config!!.folders!!.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().id == id) {
                    iterator.remove()
                    break
                }
            }
        }
    }

    /**
     * Returns a list of all existing devices.
     *
     * @param includeLocal True if the local device should be included in the result.
     */
    fun getDevices(includeLocal: Boolean): List<Device> {
        val devices = synchronized(configLock) {
            Util.deepCopy(config!!.devices!!, object : TypeToken<List<Device>>() {}.type)
        }

        val iterator = devices.iterator()
        while (iterator.hasNext()) {
            val device = iterator.next()
            val isLocalDevice = localDeviceId == device.deviceID
            if (!includeLocal && isLocalDevice) {
                iterator.remove()
                break
            }
        }
        return devices
    }

    val localDevice: Device
        get() {
            val devices = getDevices(true)
            if (devices.isEmpty()) {
                throw RuntimeException("RestApi.getLocalDevice: devices is empty.")
            }
            LogV("getLocalDevice: Looking for local device ID $localDeviceId")
            for (d in devices) {
                if (d.deviceID == localDeviceId) {
                    return Util.deepCopy(d, Device::class.java)
                }
            }
            throw RuntimeException("RestApi.getLocalDevice: Failed to get the local device crucial to continuing execution.")
        }

    /**
     * Adds or updates a device identified by its device ID.
     */
    fun updateDevice(newDevice: Device) {
        synchronized(configLock) {
            removeDeviceInternal(newDevice.deviceID)
            config!!.devices!!.add(newDevice)
            sendConfig()
        }
    }

    fun removeDevice(deviceId: String) {
        synchronized(configLock) {
            removeDeviceInternal(deviceId)
            // remoteCompletion will be updated after the ConfigSaved event.
            sendConfig()
        }
    }

    private fun removeDeviceInternal(deviceId: String) {
        synchronized(configLock) {
            val iterator = config!!.devices!!.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().deviceID == deviceId) {
                    iterator.remove()
                    break
                }
            }
        }
    }

    val options: Options
        get() = synchronized(configLock) {
            Util.deepCopy(config!!.options!!, Options::class.java)
        }

    val gui: Gui
        get() = synchronized(configLock) {
            Util.deepCopy(config!!.gui!!, Gui::class.java)
        }

    fun editSettings(newGui: Gui, newOptions: Options) {
        synchronized(configLock) {
            config!!.gui = newGui
            config!!.options = newOptions
        }
    }

    fun updateGui(newGui: Gui) {
        synchronized(configLock) {
            config!!.gui = newGui
            sendConfig()
        }
    }

    /**
     * Requests and parses information about current system status and resource usage.
     */
    fun getSystemStatus(listener: OnResultListener1<SystemStatus>) {
        apiGet(ApiClient.URI_SYSTEM_STATUS, null, { result ->
            try {
                val systemStatus = gson.fromJson(result, SystemStatus::class.java)
                listener.onResult(systemStatus)
            } catch (e: Exception) {
                Log.e(TAG, "getSystemStatus: Parsing REST API result failed. result=$result", e)
            }
        }, { })
    }

    /**
     * Suspend variant of [getSystemStatus] for coroutine callers (phase6b). Throws
     * [IOException] on transport failure and [Exception]-subclasses on parse failure.
     */
    suspend fun fetchSystemStatus(): SystemStatus {
        val result = clientFor(url).get(ApiClient.URI_SYSTEM_STATUS)
        return gson.fromJson(result, SystemStatus::class.java)
    }

    /**
     * Suspend refresh of the remote device status caches: fetches connections (with
     * transfer-rate calculation) and device last-seen stats, updating the caches so
     * subsequent [getRemoteDeviceStatus] / [getRemoteDeviceCompletion] reads are fresh.
     * Polling callers (StatusPage, HomeDataHost) use this instead of the old
     * cache-miss-triggered fire-and-forget path.
     */
    suspend fun refreshRemoteDeviceStatuses() {
        val connections = gson.fromJson(
            clientFor(url).get(ApiClient.URI_CONNECTIONS),
            Connections::class.java
        )
        calculateConnectionStats(connections)
        storeDeviceStatuses(connections)
        onTotalSyncCompletionChange()

        storeDeviceLastSeenStats(
            clientFor(url).get(ApiClient.URI_STATS_DEVICE)
        )
    }

    private fun storeDeviceStatuses(connections: Connections) {
        val connectionsMap = connections.connections ?: return
        for (e in connectionsMap.entries) {
            remoteCompletion.setDeviceStatus(e.key, e.value)
        }
    }

    private fun storeDeviceLastSeenStats(result: String) {
        val jsonObject = JsonParser.parseString(result).asJsonObject
        for (entry in jsonObject.entrySet()) {
            val resultDeviceId = entry.key
            val deviceStat = gson.fromJson(entry.value, DeviceStat::class.java)
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(Constants.PREF_CACHE_DEVICE_LASTSEEN_PREFIX + resultDeviceId, deviceStat.lastSeen)
                .apply()
        }
    }

    val isConfigLoaded: Boolean
        get() = synchronized(configLock) {
            config != null
        }

    /**
     * Requests locally discovered devices.
     */
    fun getDiscoveredDevices(listener: OnResultListener1<Map<String, DiscoveredDevice>>) {
        apiGet(ApiClient.URI_SYSTEM_DISCOVERY, null, { result ->
            val discoveredDevices: MutableMap<String, DiscoveredDevice> = gson.fromJson(
                result,
                object : TypeToken<Map<String, DiscoveredDevice>>() {}.type
            )
            if (Constants.ENABLE_TEST_DATA) {
                val fakeDiscoveredDevice = DiscoveredDevice()
                fakeDiscoveredDevice.addresses = arrayOf("tcp4://192.168.178.10:40004")
                discoveredDevices[TestData.DEVICE_A_ID] = fakeDiscoveredDevice
                discoveredDevices[TestData.DEVICE_B_ID] = fakeDiscoveredDevice
            }
            listener.onResult(discoveredDevices)
        }, { })
    }

    /**
     * Requests ignore list for given folder.
     */
    fun getFolderIgnoreList(folderId: String, listener: OnResultListener1<FolderIgnoreList>) {
        apiGet(ApiClient.URI_DB_IGNORES, params("folder" to folderId), { result ->
            val folderIgnoreList = gson.fromJson(result, FolderIgnoreList::class.java)
            listener.onResult(folderIgnoreList)
        }, { })
    }

    /**
     * Posts ignore list for given folder.
     */
    fun postFolderIgnoreList(folderId: String, ignore: Array<String>) {
        val folderIgnoreList = FolderIgnoreList()
        folderIgnoreList.ignore = ignore
        apiPost(ApiClient.URI_DB_IGNORES, params("folder" to folderId),
            gson.toJson(folderIgnoreList))
    }

    /**
     * Returns status information about the device with the given id from cache.
     * Set deviceId to "" to query status for an initially empty cache.
     */
    fun getRemoteDeviceStatus(deviceId: String): Connection {
        val cacheEntry = remoteCompletion.getDeviceStatus(deviceId)
        if (cacheEntry.at.isEmpty()) {
            // Cache miss. Query the required information so it will be available
            // on a future call to this function.
            if (deviceId.isNotEmpty()) {
                LogV("getRemoteDeviceStatus: Cache miss, deviceId=\"$deviceId\". Performing query.")
            }
            apiGet(ApiClient.URI_CONNECTIONS, null, { result ->
                // We got connection status information for ALL devices instead of one.
                // It does not hurt storing all of them.
                val connections = gson.fromJson(result, Connections::class.java)
                calculateConnectionStats(connections)
                storeDeviceStatuses(connections)
                onTotalSyncCompletionChange()
            }, { })
            apiGet(ApiClient.URI_STATS_DEVICE, null, { result ->
                // We got the last seen timestamp for ALL devices - including the local
                // device - instead of one. It does not hurt storing all of them.
                storeDeviceLastSeenStats(result)
            }, { })
        }
        return cacheEntry
    }

    fun getRemoteDeviceCompletion(deviceId: String): Int {
        return remoteCompletion.getDeviceCompletion(deviceId)
    }

    fun getRemoteDeviceNeedBytes(deviceId: String): Double {
        return remoteCompletion.getDeviceNeedBytes(deviceId)
    }

    val totalConnectionStatistic: Connection
        get() {
            val prevConnections = previousConnections ?: return Connection()
            return Util.deepCopy(prevConnections.total!!, Connection::class.java)
        }

    /**
     * Calculate transfer rates for each remote device connection and the "total device" stats.
     */
    private fun calculateConnectionStats(connections: Connections) {
        val now = System.currentTimeMillis()
        val msElapsed = now - previousConnectionTime
        var connections = connections
        if (msElapsed < Constants.REST_UPDATE_INTERVAL) {
            connections = Util.deepCopy(previousConnections!!, Connections::class.java)
            return
        }

        previousConnectionTime = now
        val connectionsMap = connections.connections!!
        for (e in connectionsMap.entries) {
            val prev: Connection = previousConnections?.connections?.get(e.key) ?: Connection()
            e.value.setTransferRate(prev, msElapsed)
        }
        val prev = previousConnections?.total ?: Connection()
        connections.total!!.setTransferRate(prev, msElapsed)
        previousConnections = connections
    }

    /**
     * Returns overall sync completion percentage representing all
     * currently running folder and device transfers.
     * Folder percentage means we are currently pulling changes from remotes.
     * Device percentage means remotes currently pull changes from us.
     * Uses cached stats instead of performing REST queries.
     */
    fun getTotalSyncCompletion(): Int {
        val totalDeviceCompletion = remoteCompletion.getTotalDeviceCompletion()
        if (totalDeviceCompletion == -1) {
            // Total sync completion is not applicable because there are no devices or no devices are connected.
            return -1
        }

        val totalFolderCompletion = localCompletion.getTotalFolderCompletion()

        // Calculate overall sync completion percentage.
        val totalSyncCompletion: Int = if (totalFolderCompletion == 100) {
            totalDeviceCompletion
        } else {
            Math.floor((totalFolderCompletion + totalDeviceCompletion).toDouble() / 2).toInt()
        }

        // Filter invalid percentage values.
        return totalSyncCompletion.coerceIn(0, 100)
    }

    /**
     * Retrieves the events that have accumulated since the given event id.
     *
     * The OnReceiveEventListeners onEvent method is called for each event.
     */
    fun getEvents(sinceId: Long, limit: Long, listener: OnReceiveEventListener) {
        val params = params("since" to sinceId.toString(), "limit" to limit.toString())
        apiGet(ApiClient.URI_EVENTS, params, { result ->
            val jsonEvents = JsonParser.parseString(result).asJsonArray
            var lastId: Long = 0

            for (json in jsonEvents) {
                try {
                    val event = gson.fromJson(json, Event::class.java)
                    if (lastId < event.id) {
                        lastId = event.id.toLong()
                    }
                    listener.onEvent(event, json)
                } catch (ex: JsonSyntaxException) {
                    Log.e(TAG, "getEvents: Skipping event due to JsonSyntaxException, raw=[$json]")
                }
            }

            listener.onDone(lastId)
        }, {
            listener.onError()
        })
    }

    /**
     * Returns status information about the folder with the given id from cache.
     */
    fun getFolderStatus(folderId: String): Map.Entry<FolderStatus, CachedFolderStatus> {
        val cacheEntry = localCompletion.getFolderStatus(folderId)
        if (cacheEntry.key.stateChanged.isEmpty()) {
            // Cache miss because we haven't received a "FolderSummary" event yet.
            // Query the required information so it will be available on a future call
            // to this function.
            LogV("getFolderStatus: Cache miss, folderId=\"$folderId\". Performing query.")
            apiGet(ApiClient.URI_DB_STATUS, params("folder" to folderId), { result ->
                val folder = findFolderReadonly(folderId)
                if (folder == null) {
                    Log.e(TAG, "getFolderStatus#onResult: folderId == null")
                    return@apiGet
                }
                localCompletion.setFolderStatus(
                    folderId,
                    folder.paused,
                    gson.fromJson(result, FolderStatus::class.java)
                )
            }, { })
        }
        return cacheEntry
    }

    private fun sendBroadcastToApps(intent: Intent) {
        val packageIdList = arrayOf(
            // "com.example.syncthingreceiver",
            "org.decsync.cc"
        )
        // intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        for (packageId in packageIdList) {
            intent.setPackage(packageId)
            (context.applicationContext as SyncthingApp).sendBroadcast(intent, PERMISSION_RECEIVE_SYNC_STATUS)
        }
    }

    fun sendBroadcastFolderSyncComplete(deviceId: String?, folder: Folder, folderState: String?) {
        val intent = Intent()
        intent.action = ACTION_NOTIFY_FOLDER_SYNC_COMPLETE
        intent.putExtra("deviceId", deviceId)
        intent.putExtra("folderId", folder.id)
        intent.putExtra("folderLabel", folder.label)
        intent.putExtra("folderPath", folder.path)
        intent.putExtra("folderState", folderState)
        sendBroadcastToApps(intent)
    }

    /**
     * Updates cached folder and device completion info according to event data.
     */
    fun setLocalFolderStatus(folderId: String?, folderStatus: FolderStatus) {
        localCompletion.setFolderStatus(folderId ?: return, folderStatus)
        onTotalSyncCompletionChange()
    }

    fun setLocalFolderLastItemFinished(
        folderId: String?,
        lastItemFinishedAction: String?,
        lastItemFinishedItem: String?,
        lastItemFinishedTime: String?
    ) {
        val fId = folderId ?: return
        // lastItemFinishedAction RAW data from Syncthing:
        // update: A file was changed or deleted
        //
        // The File.exists() below is a filesystem stat per event; it used to run on the main
        // thread (EventPoller callbacks) and stalled frames during active syncs. Offload the
        // whole cache update to the single-thread executor like the conflict-file scans; the
        // UI picks the result up on the next read, a few ms later.
        executorService.execute {
            if (hasShutdown || executorService.isShutdown) {
                return@execute
            }
            var realLastItemFinishedAction = lastItemFinishedAction

            // Check if the file was updated or deleted in reality.
            if (lastItemFinishedAction == "update") {
                val folder = findFolderReadonly(fId)
                if (!(folder == null || folder.path == null)) {
                    val fileExists = File(folder.path + "/" + lastItemFinishedItem).exists()
                    if (!fileExists) {
                        realLastItemFinishedAction = "delete"
                    }
                }
            }
            localCompletion.setLastItemFinished(
                fId,
                realLastItemFinishedAction ?: "",
                lastItemFinishedItem ?: "",
                lastItemFinishedTime ?: ""
            )
        }
    }

    fun setRemoteCompletionInfo(deviceId: String?, folderId: String?, needBytes: Double?, completion: Double?) {
        val fId = folderId ?: run {
            Log.e(TAG, "setRemoteCompletionInfo: folderId == null")
            return
        }
        val folder = findFolderReadonly(fId)
        if (folder == null) {
            Log.e(TAG, "setRemoteCompletionInfo: folderId == null")
            return
        }
        val remoteCompletionInfo = RemoteCompletionInfo()
        if (folder.paused) {
            // Fixes issue where device sync percentage is displayed 50% on wrapper UI
            // and 100% on Web UI if there are at least two folders syncing with the same
            // device and at least one of them is paused. This is caused by EventPoller
            // telling us a paused folder to be 0% complete. To get consistent UI output,
            // we assume 100% completion for paused folders.
            LogV("setRemoteCompletionInfo: Paused folder \"$folderId\" - got ${remoteCompletionInfo.completion}%, passing on 100%")
            remoteCompletionInfo.completion = 100.0
            remoteCompletionInfo.needBytes = 0.0
        } else {
            remoteCompletionInfo.completion = completion ?: 0.0
            remoteCompletionInfo.needBytes = needBytes ?: 0.0
        }
        val dId = deviceId
        if (dId != null) {
            remoteCompletion.setCompletionInfo(dId, fId, remoteCompletionInfo)
        }
        onTotalSyncCompletionChange()

        // Check if a folder completed synchronization on the local or a remote device.
        // Plan finisher workloads that need to run after folder completion.
        // They will be offloaded to a separate thread later.
        var planGetSyncConflictFiles = false
        var planOnFolderSyncCompleted = false

        val cacheEntry = localCompletion.getFolderStatus(folderId)
        val folderStatus = cacheEntry.key
        val folderIsSyncing = folderStatus.state.contains("sync")
        if (remoteCompletionInfo.completion == 100.0) {
            if (!folderIsSyncing) {
                planGetSyncConflictFiles = true

                val cachedFolderStatus = cacheEntry.value
                if (cachedFolderStatus.remoteIndexUpdated) {
                    localCompletion.setRemoteIndexUpdated(folderId, false)
                    planOnFolderSyncCompleted = true
                }
            }
        }

        // Execute planned workloads.
        if (hasShutdown || executorService.isShutdown) {
            // We are on the way to shutdown SyncthingNative.
            return
        }
        if (!planGetSyncConflictFiles && !planOnFolderSyncCompleted) {
            // No work to do.
            return
        }

        executorService.execute {
            if (hasShutdown) {
                return@execute
            }

            if (planGetSyncConflictFiles) {
                // Check for ".sync-conflict-YYYYMMDD-HHMMSS-DEVICEI*" files.
                localCompletion.setDiscoveredConflictFiles(
                    folderId,
                    Util.getSyncConflictFiles(folder.path!!)
                )
            }

            if (planOnFolderSyncCompleted) {
                onFolderSyncCompleted(
                    folder,
                    folderStatus.state,
                    deviceId
                )
            }
        }
    }

    fun onFolderSyncCompleted(folder: Folder, folderState: String?, deviceId: String?) {
        Log.d(TAG, "onFolderSyncCompleted: Completed folder=[${folder.id}]")

        // Run folder script set if enabled by user pref.
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val folderRunScriptEnabled = sharedPreferences.getBoolean(
            Constants.DYN_PREF_OBJECT_FOLDER_RUN_SCRIPT(folder.id), false
        )
        if (folderRunScriptEnabled) {
            Util.runScriptSet(
                folder.path + "/" + Constants.FILENAME_STFOLDER,
                arrayOf("sync_complete")
            )
        }

        // Notify listening third-party apps.
        sendBroadcastFolderSyncComplete(deviceId, folder, folderState)
    }

    fun setRemoteIndexUpdated(deviceId: String, folderId: String, remoteIndexUpdated: Boolean) {
        localCompletion.setRemoteIndexUpdated(folderId, remoteIndexUpdated)
    }

    fun updateLocalFolderPause(folderId: String?, newPaused: Boolean) {
        // Clear status cache when pausing or resuming the folder.
        localCompletion.setFolderStatus(folderId ?: return, newPaused, FolderStatus())
    }

    fun updateLocalFolderState(folderId: String?, newState: String?) {
        val cacheEntry = localCompletion.getFolderStatus(folderId ?: return)
        cacheEntry.key.state = newState ?: ""
        localCompletion.setFolderStatus(folderId, cacheEntry.key)
    }

    fun updateRemoteDeviceConnected(deviceId: String?, newConnected: Boolean) {
        val cacheEntry = remoteCompletion.getDeviceStatus(deviceId ?: return)
        cacheEntry.connected = newConnected
        remoteCompletion.setDeviceStatus(deviceId, cacheEntry)
        onTotalSyncCompletionChange()
    }

    fun updateRemoteDevicePaused(deviceId: String?, newPaused: Boolean) {
        val cacheEntry = remoteCompletion.getDeviceStatus(deviceId ?: return)
        cacheEntry.connected = false
        cacheEntry.paused = newPaused
        remoteCompletion.setDeviceStatus(deviceId, cacheEntry)
        onTotalSyncCompletionChange()
    }

    /**
     * Returns prettyfied usage report.
     */
    fun getUsageReport(listener: OnResultListener1<String>) {
        apiGet(ApiClient.URI_REPORT, null, { result ->
            val json = JsonParser.parseString(result)
            val gson = GsonBuilder().setPrettyPrinting().create()
            listener.onResult(gson.toJson(json))
        }, { })
    }

    fun isUsageReportingAccepted(): Boolean {
        val options = options
        if (options == null) {
            Log.e(TAG, "isUsageReportingAccepted called while options == null")
            return false
        }
        return options.isUsageReportingAccepted(urVersionMax!!)
    }

    fun isUsageReportingDecided(): Boolean {
        val options = options
        if (options == null) {
            Log.e(TAG, "isUsageReportingDecided called while options == null")
            return true
        }
        return options.isUsageReportingDecided(urVersionMax!!)
    }

    fun setUsageReporting(acceptUsageReporting: Boolean) {
        val options = options
        if (options == null) {
            Log.e(TAG, "setUsageReporting called while options == null")
            return
        }
        options.urAccepted = if (acceptUsageReporting) urVersionMax!! else Options.USAGE_REPORTING_DENIED
        synchronized(configLock) {
            config!!.options = options
        }
    }

    fun downloadSupportBundle(targetFile: File, listener: OnResultListener1<Boolean>?) {
        apiGet(ApiClient.URI_DEBUG_SUPPORT, null, { result ->
            var failSuccess = true
            LogV("downloadSupportBundle: Writing '${targetFile.path}' ...")
            var fileOutputStream: FileOutputStream? = null
            try {
                if (!targetFile.exists()) {
                    targetFile.createNewFile()
                }
                fileOutputStream = FileOutputStream(targetFile)
                fileOutputStream.write(result.toByteArray(StandardCharsets.ISO_8859_1)) // Do not use UTF-8 here because the ZIP would be corrupted.
                fileOutputStream.flush()
            } catch (e: IOException) {
                Log.w(TAG, "downloadSupportBundle: Failed to write '${targetFile.path}' #1", e)
                failSuccess = false
            } finally {
                try {
                    fileOutputStream?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "downloadSupportBundle: Failed to write '${targetFile.path}' #2", e)
                    failSuccess = false
                }
            }
            listener?.onResult(failSuccess)
        }, { })
    }

    /**
     * Event triggered by [RunConditionMonitor] routed here through [SyncthingService].
     */
    fun applyCustomRunConditions(runConditionMonitor: RunConditionMonitor) {
        synchronized(configLock) {
            var configChanged = false

            // Check if the config has been loaded.
            val config = config
            if (config == null) {
                Log.w(TAG, "applyCustomRunConditions: config is not ready yet.")
                return
            }

            // Check if the folders are available from config.
            if (config.folders != null) {
                for (folder in config.folders!!) {
                    val folderPrefixAndId = Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id
                    val shouldPause = runConditionMonitor.getCustomSyncConditionsPause(folderPrefixAndId)
                    if (shouldPause == null) {
                        continue
                    }
                    LogV("applyCustomRunConditions: f(${folder.label})=${if (!shouldPause) "1" else "0"}")
                    if (folder.paused != shouldPause) {
                        folder.paused = shouldPause
                        Log.d(TAG, "applyCustomRunConditions: f(${folder.label})=${if (!shouldPause) ">1" else ">0"}")
                        configChanged = true
                    }
                }
            } else {
                Log.d(TAG, "applyCustomRunConditions: config.folders is not ready yet.")
                return
            }

            // Check if the devices are available from config.
            if (config.devices != null) {
                for (device in config.devices!!) {
                    val devicePrefixAndId = Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID
                    val shouldPause = runConditionMonitor.getCustomSyncConditionsPause(devicePrefixAndId)
                    if (shouldPause == null) {
                        continue
                    }
                    LogV("applyCustomRunConditions: d(${device.name})=${if (!shouldPause) "1" else "0"}")
                    if (device.paused != shouldPause) {
                        device.paused = shouldPause
                        Log.d(TAG, "applyCustomRunConditions: d(${device.name})=${if (!shouldPause) ">1" else ">0"}")
                        configChanged = true
                    }
                }
            } else {
                Log.d(TAG, "applyCustomRunConditions: config.devices is not ready yet.")
                return
            }

            if (configChanged) {
                LogV("applyCustomRunConditions: Sending changed config ...")
                sendConfig()
            } else {
                LogV("applyCustomRunConditions: No action was necessary.")
            }
        }
    }

    private fun setVersioningCleanupIntervalS(cleanupIntervalS: Int) {
        synchronized(configLock) {
            for (folder in config!!.folders!!) {
                folder.versioning!!.cleanupIntervalS = cleanupIntervalS
            }
            LogV("Set VersioningCleanupIntervalS to $cleanupIntervalS")
            sendConfig()
        }
    }

    private fun onTotalSyncCompletionChange() {
        val onlineDeviceCount = remoteCompletion.getOnlineDeviceCount()
        val totalSyncCompletion = getTotalSyncCompletion()
        if (onlineDeviceCount == lastOnlineDeviceCount &&
            totalSyncCompletion == lastTotalSyncCompletion
        ) {
            return
        }
        mutableTotalSyncCompletion.value = totalSyncCompletion
        notificationHandler.updatePersistentNotification(
            context as SyncthingService,
            false, // Do not persist previous notification text.
            onlineDeviceCount,
            totalSyncCompletion
        )
        lastOnlineDeviceCount = onlineDeviceCount
        lastTotalSyncCompletion = totalSyncCompletion
    }

    /**
     * Builds the query parameter map for [ApiClientBridge] calls. Replaces the former
     * ImmutableMap.of usage (guava is being phased out in phase8); LinkedHashMap is fine
     * as query parameter order carries no meaning.
     */
    private fun params(vararg pairs: Pair<String, String>): Map<String, String> {
        return mapOf(*pairs)
    }

    private fun LogV(logMessage: String) {
        if (enableVerboseLog) {
            Log.v(TAG, logMessage)
        }
    }
}
