package com.nutomic.syncthingandroid.activities

import android.content.ComponentName
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.SyncthingActivity
import com.nutomic.syncthingandroid.http.ApiClient
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.DiskEvent
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.Constants.PREF_SHOW_EXACT_TIMES
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder
import com.nutomic.syncthingandroid.ui.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.FileUtils
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.nutomic.syncthingandroid.ui.screens.recentchanges.RecentChangesScreen

/**
 * Shows the list of recent changes to files and folders reported by the local Syncthing instance.
 */
class RecentChangesActivity : SyncthingActivity(), SyncthingService.OnServiceStateChangeListener {

    private var changes by mutableStateOf<List<RecentChange>>(emptyList())

    /** Drives the pull-to-refresh indicator; only set for refreshes the user asked for. */
    private var isRefreshing by mutableStateOf(false)

    /** Re-entrancy guard, separate from [isRefreshing] so background polls stay invisible. */
    private var refreshInFlight = false

    private var showExactTimes by mutableStateOf(false)
    private lateinit var preferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Same store the app-wide default SharedPreferences, read directly so this screen does
        // not need the injected singleton for one boolean.
        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        showExactTimes = preferences.getBoolean(PREF_SHOW_EXACT_TIMES, false)

        setContent {
            ApplicationTheme {
                RecentChangesScreen(
                    changes = changes,
                    isRefreshing = isRefreshing,
                    showExactTimes = showExactTimes,
                    onNavigateBack = { finish() },
                    onRefresh = { refresh() },
                    onToggleExactTimes = ::toggleExactTimes,
                    onItemClick = ::openChange,
                )
            }
        }

        startBackgroundPolling()
    }

    /**
     * Keeps the list current while the screen is in the foreground, without ever showing the refresh
     * indicator — new changes just appear.
     *
     * This polls on an interval rather than holding a long-poll open (which is how the web GUI stays
     * live). Two things make a subscription impractical here: `RestApi.getDiskEvents` has no `since`
     * parameter, so a loop would re-fetch the same buffered events and spin as fast as the server
     * could answer; and Volley's `DefaultRetryPolicy` uses a 5s socket timeout, so it abandons and
     * retries a blocked long-poll several times per call — the server explicitly notes such requests
     * "should not be retried". Short `timeout=1` queries on a timer are far more predictable.
     *
     * `repeatOnLifecycle(STARTED)` stops polling when the screen is not visible.
     */
    private fun startBackgroundPolling() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(POLL_INTERVAL)
                    refresh(showProgress = false)
                }
            }
        }
    }

    override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
        super.onServiceConnected(componentName, binder)
        (binder as SyncthingServiceBinder).service.registerOnServiceStateChangeListener(this)
    }

    override fun onServiceStateChange(newState: SyncthingService.State) {
        Log.v(TAG, "onServiceStateChange($newState)")
        if (newState == SyncthingService.State.ACTIVE) {
            refresh()
        }
    }

    override fun onDestroy() {
        service?.unregisterOnServiceStateChangeListener(this)
        super.onDestroy()
    }

    /**
     * @param showProgress whether to drive the refresh indicator. False for background polls, so
     *   they update the list silently.
     */
    private fun refresh(showProgress: Boolean = true) {
        if (refreshInFlight) return
        val api = activeApi ?: return

        refreshInFlight = true
        if (showProgress) isRefreshing = true
        // Scoped to the lifecycle, so a pending request is cancelled if the screen goes away.
        lifecycleScope.launch {
            try {
                // Both of these are cached config reads, not network calls.
                val devices = api.getDevices(true)
                val localDeviceId = api.localDevice.deviceID
                Log.v(TAG, "Querying disk events")
                fetchDiskEvents(api, DISK_EVENT_LIMIT)?.let { diskEvents ->
                    changes = diskEvents
                        .toRecentChanges(devices, localDeviceId, getString(R.string.this_device))
                }
            } finally {
                refreshInFlight = false
                isRefreshing = false
            }
        }
    }

    /**
     * Fetches the most recent disk events over the OkHttp-based [ApiClient] (phase2 of the
     * Java-to-Kotlin migration). RestApi's own Volley-based getDiskEvents was retired in
     * phase6a; this screen keeps its dedicated per-fetch client because the GUI address and
     * API key can change on config reload and it only performs one request every few seconds.
     *
     * Error handling mirrors the observable behaviour of the old Volley-based wrapper: any
     * failure (transport, HTTP error, parse) leaves the current list untouched and returns null.
     * Unlike the old wrapper there is no outer 30 s timeout guard — every failure mode now
     * surfaces promptly (connection failures and HTTP errors fail immediately; socket timeouts
     * are bounded by ApiClient's own 6 x 5 s retry loop), so the request can no longer hang
     * forever and silently rely on a timeout to unblock the coroutine.
     */
    private suspend fun fetchDiskEvents(api: RestApi, limit: Int): List<DiskEvent>? {
        return try {
            val client = ApiClient(
                httpsCertFile = Constants.getHttpsCertFile(this),
                url = api.webGuiUrl,
                apiKey = api.apiKey,
            )
            val result = client.get(
                ApiClient.URI_EVENTS_DISK,
                params = mapOf("limit" to limit.toString(), "timeout" to "1"),
            )
            // Same parse as RestApi.getDiskEvents: the API returns events oldest-first, so walk
            // the array backwards to get newest-first for display.
            val array = JsonParser.parseString(result).getAsJsonArray()
            (array.size() - 1 downTo 0).map { gson.fromJson(array[it], DiskEvent::class.java) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchDiskEvents: request failed", e)
            null
        }
    }

    private fun openChange(change: RecentChange) {
        if (!change.canOpen) return
        Log.v(TAG, "User clicked item with title '${change.rawPath}'")
        val folder = activeApi?.getFolderByID(change.folderId)
        if (folder == null) {
            Log.e(TAG, "openChange: folder unavailable for id=[${change.folderId}]")
            return
        }
        val fullPath = folder.path + File.separator + change.rawPath
        when (change.type) {
            ChangeType.DIR -> FileUtils.openFolder(this, fullPath)
            ChangeType.FILE -> FileUtils.openFile(this, fullPath)
            ChangeType.UNKNOWN -> Log.e(TAG, "openChange: unknown type for path=[${change.rawPath}]")
        }
    }

    private fun toggleExactTimes() {
        showExactTimes = !showExactTimes
        preferences.edit { putBoolean(PREF_SHOW_EXACT_TIMES, showExactTimes) }
    }

    /** The [RestApi], but only once the service is actually running. */
    private val activeApi: RestApi?
        get() = api?.takeIf { service?.currentState == SyncthingService.State.ACTIVE }

    companion object {
        private const val TAG = "RecentChangesActivity"
        private const val DISK_EVENT_LIMIT = 100

        /** How often the foreground screen re-queries. Tune freely; each query is a local call. */
        private val POLL_INTERVAL = 5.seconds
    }
}

/**
 * Plain Gson instance, same role as RestApi's gson for parsing disk events into
 * [DiskEvent] (all model classes are Kotlin data holders since phase1).
 */
private val gson = Gson()

enum class ChangeType {
    FILE, DIR, UNKNOWN;

    companion object {
        fun of(raw: String?) = when (raw) {
            "file" -> FILE
            "dir" -> DIR
            else -> UNKNOWN
        }
    }
}

enum class ChangeAction {
    ADDED, DELETED, MODIFIED, UNKNOWN;

    companion object {
        fun of(raw: String?) = when (raw) {
            "added" -> ADDED
            "deleted" -> DELETED
            "modified" -> MODIFIED
            else -> UNKNOWN
        }
    }
}

/** Immutable UI model for a single row of [RecentChangesScreen]. */
@Immutable
data class RecentChange(
    val filename: String,
    val folderPath: String,
    val modifiedByName: String,
    /** Epoch millis of the change, or null if the reported timestamp could not be parsed. */
    val timeMillis: Long?,
    /** The timestamp exactly as Syncthing reported it; shown when [timeMillis] is null. */
    val rawTime: String,
    val type: ChangeType,
    val action: ChangeAction,
    /** Needed to resolve the on-disk location when the row is opened. */
    val folderId: String,
    val rawPath: String,
) {
    /** Deleted entries are gone from disk, and an unknown type has no viewer to launch. */
    val canOpen: Boolean get() = action != ChangeAction.DELETED && type != ChangeType.UNKNOWN
}

private fun List<DiskEvent>.toRecentChanges(
    devices: List<Device>,
    localDeviceId: String,
    thisDeviceLabel: String,
): List<RecentChange> {
    return withoutUselessEvents().map { event ->
        RecentChange(
            filename = fileNameOf(event.data.path),
            folderPath = "[${event.data.label}]${File.separator}${parentPathOf(event.data.path)}",
            modifiedByName = resolveDeviceName(event.data.modifiedBy, devices, localDeviceId, thisDeviceLabel),
            timeMillis = parseEpochMillisOrNull(event.time),
            rawTime = event.time,
            type = ChangeType.of(event.data.type),
            action = ChangeAction.of(event.data.action),
            folderId = event.data.folderID,
            rawPath = event.data.path,
        )
    }
}

/**
 * Parses a Syncthing timestamp (RFC 3339, e.g. `2018-10-29T15:18:52.6183215+01:00`) to epoch millis,
 * or null if it does not parse.
 *
 * `OffsetDateTime` accepts fractional seconds with 0–9 digits and both `Z` and `+01:00` offsets, so
 * the old SimpleDateFormat normalisation is no longer needed. minSdk 26 makes java.time available
 * without core library desugaring.
 */
private fun parseEpochMillisOrNull(raw: String): Long? {
    if (raw.isEmpty()) return null
    return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
}

/**
 * Drops disk events that are not useful to display:
 *  - events carrying no data,
 *  - an "added" entry whose exact path is deleted by a later event,
 *  - any entry inside a directory that a later event deletes.
 *
 * Later means "has a higher event id". Deletions are indexed up front so this stays linear in the
 * number of events (times path depth) instead of comparing every event against every other.
 *
 */
internal fun List<DiskEvent>.withoutUselessEvents(): List<DiskEvent> {
    val valid = filter { it.data != null }

    val lastDeletedAt = HashMap<String, Long>()
    val lastDirDeletedAt = HashMap<String, Long>()
    for (event in valid) {
        if (ChangeAction.of(event.data.action) != ChangeAction.DELETED) continue
        lastDeletedAt.keepHighest(event.data.path, event.id)
        if (ChangeType.of(event.data.type) == ChangeType.DIR) {
            lastDirDeletedAt.keepHighest(event.data.path, event.id)
        }
    }
    if (lastDeletedAt.isEmpty()) return valid

    fun deletedAfter(map: Map<String, Long>, path: String, id: Long) = (map[path] ?: -1L) > id

    return valid.filterNot { event ->
        val path = event.data.path
        val supersededByDelete = ChangeAction.of(event.data.action) == ChangeAction.ADDED &&
            deletedAfter(lastDeletedAt, path, event.id)

        supersededByDelete ||
            path.ancestorPaths().any { deletedAfter(lastDirDeletedAt, it, event.id) }
    }
}

/**
 * Stores [value] under [key] unless a higher value is already recorded.
 *
 * Hand-rolled because the obvious alternatives — `merge`, `compute`, `putIfAbsent`, `getOrDefault` —
 * are all Java 8 default methods that require API 24, and minSdk is 23.
 */
private fun MutableMap<String, Long>.keepHighest(key: String, value: Long) {
    val current = this[key]
    if (current == null || value > current) {
        this[key] = value
    }
}

/** Ancestor directory paths, longest first: "a/b/c.txt" -> "a/b", "a". */
private fun String.ancestorPaths(): Sequence<String> = sequence {
    var slash = lastIndexOf('/')
    while (slash > 0) {
        yield(substring(0, slash))
        slash = lastIndexOf('/', slash - 1)
    }
}

/** Last path segment, e.g. "a/b/c.txt" -> "c.txt". */
private fun fileNameOf(path: String): String {
    // Encode "#" so Uri.parse handles it. See syncthing-android/651.
    val uriParseInput = path.replace("#", Uri.encode("#"))
    return uriParseInput.toUri().lastPathSegment.orEmpty()
}

/** Parent directory of a path, e.g. "a/b/c.txt" -> "a/b", "c.txt" -> "". */
private fun parentPathOf(path: String): String = path.substringBeforeLast('/', missingDelimiterValue = "")

/**
 * Resolves the partial device ID reported by Syncthing to a readable device name, or
 * [thisDeviceLabel] for the local device. Returns the input unchanged if it is blank or matches no
 * known device.
 */
private fun resolveDeviceName(
    modifiedBy: String,
    devices: List<Device>,
    localDeviceId: String,
    thisDeviceLabel: String,
): String {
    if (modifiedBy.isEmpty()) return modifiedBy
    val device = devices.firstOrNull { it.deviceID.startsWith(modifiedBy) } ?: return modifiedBy
    return if (device.deviceID == localDeviceId) thisDeviceLabel else device.displayName
}
