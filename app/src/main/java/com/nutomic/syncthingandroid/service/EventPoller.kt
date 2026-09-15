package com.nutomic.syncthingandroid.service

import android.content.AsyncQueryHandler
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Event
import com.nutomic.syncthingandroid.model.FolderStatus
import com.nutomic.syncthingandroid.util.json as jsonCodec
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Kotlin/coroutines replacement for the former Java [EventProcessor] (phase3).
 *
 * Run by the syncthing service to convert syncthing events into local state updates and
 * notifications. It polls [RestApi.getEvents] and waits for new events, preserving the old
 * polling semantics:
 *
 *  - The first poll happens one [EVENT_UPDATE_INTERVAL] after [start]; every afterwards poll
 *    is scheduled from the previous round's completion, so overlapping polls are impossible.
 *  - Each round first probes `getEvents(0, 1)`: if the reported id ran backwards, syncthing
 *    was restarted and polling resumes from zero.
 *  - The main fetch uses `getEvents(lastId, 0)`; its `onDone` id is persisted as
 *    [Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID] (unchanged key, survives process death).
 *  - Errors back off by [EVENT_UPDATE_INTERVAL] and retry, like the old `onError`.
 *
 * Threading: the default scope runs on `Dispatchers.Main.immediate`. RestApi/Volley deliver
 * responses on the main thread, so the bridging continuation resumes on the main thread and
 * all event handling stays on the main thread - exactly like the old Handler-based code.
 *
 * Divergence from the old implementation: calling [stop] discards an in-flight poll response
 * instead of processing the remaining batch afterwards. The old behaviour could invoke event
 * handling after shutdown had begun; dropping it is safer and simpler.
 */
class EventPoller(
    private val context: Context,
    private val restApi: RestApi,
    private val pollerScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {

    lateinit var preferences: SharedPreferences

    lateinit var notificationHandler: NotificationHandler

    private val verboseLog: Boolean

    private var lastEventId: Long = 0

    private var pollJob: Job? = null

    init {
        val app = context.applicationContext as SyncthingApp
        preferences = app.preferences
        notificationHandler = app.notificationHandler
        verboseLog = AppPrefs.getPrefVerboseLog(preferences)
    }

    /**
     * Starts (or restarts) the polling loop. Only one loop runs at any given time, like the
     * old removeCallbacks + postDelayed pattern.
     */
    fun start() {
        Log.d(TAG, "Starting event poller.")
        pollJob?.cancel()
        pollJob = pollerScope.launch { pollLoop() }
    }

    fun stop() {
        Log.d(TAG, "Stopping event poller.")
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollLoop() {
        // Restore the last event id if the poller may have been restarted.
        if (lastEventId == 0L) {
            lastEventId = preferences.getLong(Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID, 0)
        }
        while (coroutineContext.isActive) {
            delay(EVENT_UPDATE_INTERVAL)
            try {
                // First check if the event number ran backwards.
                // If that's the case we've to start at zero because syncthing was restarted.
                val probeId = fetchEvents(0, 1) { _, _ -> }
                if (probeId < lastEventId) {
                    lastEventId = 0
                }
                logV("Reading events starting with id $lastEventId")
                val newLastId = fetchEvents(lastEventId, 0, ::onEvent)
                if (newLastId > lastEventId) {
                    lastEventId = newLastId
                    // Store the last EventId in case we get killed.
                    preferences.edit()
                            .putLong(Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID, lastEventId)
                            .apply()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EventFetchAbortedException) {
                Log.d(TAG, "Event sink aborted, will retry in $EVENT_UPDATE_INTERVAL ms")
            }
        }
    }

    /**
     * Bridges the callback-based [RestApi.getEvents] into a suspending call that returns the
     * id of the last processed event. Resumes are guarded with [CancellableContinuation]
     * style isActive checks because the old Volley transport has no request cancellation:
     * a late callback after [stop] must be ignored instead of crashing the continuation.
     */
    private suspend fun fetchEvents(
        sinceId: Long,
        limit: Long,
        onEvent: (Event, JsonElement) -> Unit,
    ): Long = suspendCancellableCoroutine { continuation ->
        restApi.getEvents(sinceId, limit, object : RestApi.OnReceiveEventListener {
            override fun onError() {
                if (continuation.isActive) {
                    continuation.resumeWithException(EventFetchAbortedException())
                }
            }

            override fun onEvent(event: Event, json: JsonElement) {
                onEvent(event, json)
            }

            override fun onDone(lastId: Long) {
                if (continuation.isActive) {
                    continuation.resume(lastId)
                }
            }
        })
    }

    private class EventFetchAbortedException : Exception()

    /**
     * Performs the actual event handling.
     */
    fun onEvent(event: Event, json: JsonElement) {
        when (event.type) {
            "ConfigSaved" -> {
                logV("Forwarding ConfigSaved event to RestApi to get the updated config.")
                restApi.reloadConfig()
            }
            "DeviceConnected" -> restApi.updateRemoteDeviceConnected(
                    event.data?.string("id"),                  // deviceId
                    true
            )
            "DeviceDisconnected" -> restApi.updateRemoteDeviceConnected(
                    event.data?.string("id"),                  // deviceId
                    false
            )
            "DevicePaused" -> restApi.updateRemoteDevicePaused(
                    event.data?.string("device"),              // deviceId
                    true
            )
            "DeviceResumed" -> restApi.updateRemoteDevicePaused(
                    event.data?.string("device"),              // deviceId
                    false
            )
            "FolderCompletion" -> onFolderCompletion(event.data)
            "FolderErrors" -> {
                logV("Event ${event.type}, data ${event.data}")
                onFolderErrors(json)
            }
            "FolderPaused" -> onFolderPaused(
                    event.data?.string("id")                   // folderId
            )
            "FolderResumed" -> onFolderResumed(
                    event.data?.string("id")                   // folderId
            )
            "FolderSummary" -> onFolderSummary(
                    json,
                    event.data?.string("folder")               // folderId
            )
            "ItemFinished" -> onItemFinishedEvent(event)
            "LocalIndexUpdated" -> {
                logV("Event ${event.type}, data ${event.data}")
                onLocalIndexUpdated(
                        json,
                        event.data?.string("folder"),          // folderId
                        event.time
                )
            }
            "PendingDevicesChanged" -> {
                (event.data?.get("added") as? JsonArray)?.forEach { element ->
                    (element as? JsonObject)?.let { onPendingDevicesChanged(it) }
                }
            }
            "PendingFoldersChanged" -> {
                (event.data?.get("added") as? JsonArray)?.forEach { element ->
                    (element as? JsonObject)?.let { onPendingFoldersChanged(it) }
                }
            }
            "Ping" -> {
                // Ignored.
            }
            "StateChanged" -> onStateChanged(
                    event.data?.string("folder"),              // folderId
                    event.data?.string("to")
            )
            "DeviceDiscovered",
            "DownloadProgress",
            "FolderScanProgress",
            "FolderWatchStateChanged",
            "ItemStarted",
            "ListenAddressesChanged",
            "LoginAttempt",
            "RemoteDownloadProgress" -> logV("Ignored event ${event.type}, data ${event.data}")
            "RemoteIndexUpdated" -> onRemoteIndexUpdated(
                    event.data?.string("device"),              // deviceId
                    event.data?.string("folder"),              // folderId
                    event.data?.double("items") ?: 0.0
            )
            "Starting",
            "StartupComplete" -> logV("Ignored event ${event.type}, data ${event.data}")
            else -> Log.d(TAG, "Unhandled event ${event.type}")
        }
    }

    private fun onItemFinishedEvent(event: Event) {
        val action = event.data?.string("action")
        val error = event.data?.string("error")
        val folderId = event.data?.string("folder")
        val relativeFilePath = event.data?.string("item")

        // Lookup folder.path for the given folder.id if all fields were contained in the event data.
        var folderPath: String? = null
        var folderType: String? = null
        if (!action.isNullOrEmpty() &&
                !folderId.isNullOrEmpty() &&
                !relativeFilePath.isNullOrEmpty()) {
            for (folder in restApi.folders) {
                if (folder.id == folderId) {
                    folderPath = folder.path
                    folderType = folder.type
                    break
                }
            }
        }
        if (!folderPath.isNullOrEmpty() ||
                !folderType.isNullOrEmpty()) {
            if (error.isNullOrEmpty()) {
                // We don't intend to show errors as the last synced item on the UI.
                restApi.setLocalFolderLastItemFinished(folderId, action, relativeFilePath, event.time)
            }
            onItemFinished(action, error, folderType, folderPath + File.separator + relativeFilePath)
        } else {
            Log.w(TAG, "ItemFinished: Failed to determine folder.path for folder.id=\"${folderId ?: ""}\"")
        }
    }

    private fun onPendingDevicesChanged(added: JsonObject) {
        val deviceId = added.string("deviceID")
        val deviceName = added.string("name")
        val deviceAddress = added.string("address")
        if (deviceId == null) {
            return
        }
        Log.d(TAG, "Unknown device '$deviceName' ($deviceId) wants to connect")
        // Show device approve/ignore notification.
        notificationHandler.showDeviceConnectNotification(deviceId, deviceName, deviceAddress)
    }

    private fun onPendingFoldersChanged(added: JsonObject) {
        val deviceId = added.string("deviceID") ?: return
        val folderId = added.string("folderID") ?: return
        val folderLabel = added.string("folderLabel") ?: ""
        val receiveEncrypted = (added["receiveEncrypted"] as? JsonPrimitive)?.booleanOrNull
        Log.d(TAG, "Device '$deviceId' wants to share folder '$folderLabel' ($folderId)")
        // Find the deviceName corresponding to the deviceId.
        val deviceName = restApi.getDevices(false)
                .firstOrNull { it.deviceID == deviceId }?.displayName
        val isNewFolder = restApi.folders.none { it.id == folderId }
        // Show folder approve/ignore notification.
        notificationHandler.showFolderShareNotification(
                deviceId,
                deviceName,
                folderId,
                folderLabel,
                receiveEncrypted,
                isNewFolder
        )
    }

    private fun onFolderCompletion(eventData: JsonObject?) {
        restApi.setRemoteCompletionInfo(
                eventData?.string("device"),               // deviceId
                eventData?.string("folder"),               // folderId
                eventData?.double("needBytes"),
                eventData?.double("completion")
        )
    }

    private fun onFolderErrors(json: JsonElement) {
        val data = (json as? JsonObject)?.get("data") as? JsonObject
        if (data == null) {
            Log.e(TAG, "onFolderErrors: data == null")
            return
        }
        val errors = data["errors"] as? JsonArray
        if (errors == null) {
            Log.e(TAG, "onFolderErrors: errors == null")
            return
        }
        for (error in errors) {
            val errorObject = error as? JsonObject ?: continue
            val strError = errorObject.string("error") ?: ""
            val strPath = errorObject.string("path") ?: ""
            if (strError.isNotEmpty() &&
                    strPath.isNotEmpty() &&
                    strError.contains("insufficient space in basic")) {
                notificationHandler.showCrashedNotification(
                        R.string.notification_out_of_disk_space,
                        shortenedFileAndFolder(strPath)
                )
            }
        }
    }

    private fun onFolderPaused(folderId: String?) {
        restApi.updateLocalFolderPause(folderId, true)
    }

    private fun onFolderResumed(folderId: String?) {
        restApi.updateLocalFolderPause(folderId, false)
    }

    private fun onFolderSummary(json: JsonElement, folderId: String?) {
        val data = (json as? JsonObject)?.get("data") as? JsonObject
        if (data == null) {
            Log.e(TAG, "onFolderSummary: data == null")
            return
        }
        val summary = data["summary"]
        if (summary == null) {
            Log.e(TAG, "onFolderSummary: summary == null")
            return
        }
        val folderStatus: FolderStatus = try {
            jsonCodec.decodeFromString(summary.toString())
        } catch (e: Exception) {
            Log.e(TAG, "onFolderSummary: JSON decode failed", e)
            return
        }
        restApi.setLocalFolderStatus(folderId, folderStatus)
    }

    /**
     * Precondition: action != null
     */
    private fun onItemFinished(action: String?, error: String?, folderType: String?, fullFilePath: String) {
        if (!error.isNullOrEmpty()) {
            Log.e(TAG, "onItemFinished: Error \"$error\" reported on file: $fullFilePath")
            if (error.contains("no space left on device")) {
                notificationHandler.showCrashedNotification(
                        R.string.notification_out_of_disk_space,
                        shortenedFileAndFolder(fullFilePath)
                )
            }
            return
        }

        if (folderType == Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED) {
            // Skip notifying Android's MediaStore, MediaScanner.
            return
        }

        when (action) {
            "delete" -> {                       // file deleted
                if (File(fullFilePath).exists()) {
                    Log.i(TAG, "onItemFinished: MediaStore, Skip file deletion because file exists: $fullFilePath")
                    return
                }
                Log.i(TAG, "onItemFinished: MediaStore, Deleting file: $fullFilePath")
                val contentUri = MediaStore.Files.getContentUri("external")
                val resolver = context.contentResolver
                LoggingAsyncQueryHandler(resolver).startDelete(
                        0,                          // this will be passed to "onDeleteComplete#token"
                        fullFilePath,               // this will be passed to "onDeleteComplete#cookie"
                        contentUri,
                        MediaStore.Images.ImageColumns.DATA + " = ?",
                        arrayOf(fullFilePath)
                )
            }
            "update" -> {                       // file contents changed
                Log.i(TAG, "onItemFinished: MediaScanner, Rescanning file: $fullFilePath")
                MediaScannerConnection.scanFile(context, arrayOf(fullFilePath), null, null)
            }
            "metadata" ->                       // file metadata changed but not contents
                Log.i(TAG, "onItemFinished: MediaScanner, Skipping file: $fullFilePath")
            else ->
                Log.w(TAG, "onItemFinished: Unhandled action \"$action\"")
        }
    }

    private fun onLocalIndexUpdated(json: JsonElement, folderId: String?, dateTimeStamp: String?) {
        val data = (json as? JsonObject)?.get("data") as? JsonObject
        if (data == null) {
            Log.e(TAG, "onLocalIndexUpdated: data == null")
            return
        }
        val filenames = data["filenames"] as? JsonArray
        if (filenames == null) {
            Log.e(TAG, "onLocalIndexUpdated: filenames == null")
            return
        }
        for (i in filenames.indices) {
            val filename = (filenames[i] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (filename.isNotEmpty()) {
                logV("onLocalIndexUpdated: filename=[$filename], time=[$dateTimeStamp]")
                if (i == filenames.size - 1) {
                    // Send the last (latest) local change to the UI.
                    restApi.setLocalFolderLastItemFinished(
                            folderId,
                            "update",
                            filename,
                            dateTimeStamp
                    )
                }
            }
        }
    }

    private fun onRemoteIndexUpdated(deviceId: String?, folderId: String?, items: Double) {
        if (deviceId == null || folderId == null) {
            return
        }
        // logV("onRemoteIndexUpdated: deviceId=[$deviceId], folder=[$folderId], items=$items")
        if (items > 0) {
            restApi.setRemoteIndexUpdated(deviceId, folderId, true)
        }
    }

    /**
     * Emitted when a folder changes state.
     */
    private fun onStateChanged(folderId: String?, newState: String?) {
        restApi.updateLocalFolderState(folderId, newState)
        // logV("onStateChanged: folder=[$folderId], newState=[$newState]")
    }

    private class LoggingAsyncQueryHandler(contentResolver: ContentResolver) :
            AsyncQueryHandler(contentResolver) {
        override fun onDeleteComplete(token: Int, cookie: Any?, result: Int) {
            super.onUpdateComplete(token, cookie, result)
        }
    }

    private fun shortenedFileAndFolder(path: String): String {
        val segments = path.split(File.separator.toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        return if (segments.size < 2) {
            path
        } else {
            segments[segments.size - 2] + File.separator + segments[segments.size - 1]
        }
    }

    private fun logV(logMessage: String) {
        if (verboseLog) {
            Log.v(TAG, logMessage)
        }
    }

    companion object {
        private const val TAG = "EventPoller"

        /**
         * Minimum interval at which the events are polled from syncthing and processed.
         * This interval will not wake up the device to save battery power.
         */
        val EVENT_UPDATE_INTERVAL: Long = TimeUnit.SECONDS.toMillis(5)
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull
