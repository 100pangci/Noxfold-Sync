package com.nutomic.syncthingandroid.service

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.util.Log
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.http.ApiClient
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.PermissionUtil
import com.nutomic.syncthingandroid.util.RootAccess
import com.nutomic.syncthingandroid.util.Util
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.HashSet
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "SyncthingService"

/**
 * Interval in ms, at which connections to the web gui are performed on first start
 * to find out if it's online.
 */
private const val WEB_GUI_POLL_INTERVAL = 150L

/**
 * Holds the native syncthing instance and provides an API to access it.
 */
class SyncthingService : Service() {

    companion object {
        /**
         * Delay before retrying a deferred shutdown or re-applying certificate changes.
         */
        private const val SHUTDOWN_RETRY_DELAY_MS = 1000L

        /**
         * Upper bound for deferring an ACTION_RESTART while State.STARTING, so a stuck
         * startup cannot queue retries forever.
         */
        private const val MAX_RESTART_RETRIES = 30

        /**
         * Upper bound for retrying a startup whose WebUI port stayed occupied after the
         * stale-binary kill (see [onWebUiPortOccupied]).
         */
        private const val MAX_PORT_BUSY_RETRIES = 2

        /** Intent action to perform a Syncthing restart. */
        const val ACTION_RESTART = ".SyncthingService.RESTART"

        /** Intent action to perform a Syncthing stop. */
        const val ACTION_STOP = ".SyncthingService.STOP"

        /** Intent action to reset Syncthing's database. */
        const val ACTION_RESET_DATABASE = ".SyncthingService.RESET_DATABASE"

        /** Intent action to reset Syncthing's delta indexes. */
        const val ACTION_RESET_DELTAS = ".SyncthingService.RESET_DELTAS"

        const val ACTION_REFRESH_NETWORK_INFO = ".SyncthingService.REFRESH_NETWORK_INFO"

        /** Intent action to permanently ignore a device connection request. */
        const val ACTION_IGNORE_DEVICE = ".SyncthingService.IGNORE_DEVICE"

        /** Intent action to permanently ignore a folder share request. */
        const val ACTION_IGNORE_FOLDER = ".SyncthingService.IGNORE_FOLDER"

        /** Intent action to override folder changes. */
        const val ACTION_OVERRIDE_CHANGES = ".SyncthingService.OVERRIDE_CHANGES"

        /** Intent action to revert local folder changes. */
        const val ACTION_REVERT_LOCAL_CHANGES = ".SyncthingService.REVERT_LOCAL_CHANGES"

        /** Extra used together with ACTION_IGNORE_DEVICE, ACTION_IGNORE_FOLDER. */
        const val EXTRA_NOTIFICATION_ID = ".SyncthingService.EXTRA_NOTIFICATION_ID"

        /** Extra used together with ACTION_IGNORE_DEVICE. */
        const val EXTRA_DEVICE_ID = ".SyncthingService.EXTRA_DEVICE_ID"

        /** Extra used together with ACTION_IGNORE_DEVICE. */
        const val EXTRA_DEVICE_ADDRESS = ".SyncthingService.EXTRA_DEVICE_ADDRESS"

        /** Extra used together with ACTION_IGNORE_DEVICE. */
        const val EXTRA_DEVICE_NAME = ".SyncthingService.EXTRA_DEVICE_NAME"

        /** Extra used together with ACTION_IGNORE_FOLDER. */
        const val EXTRA_FOLDER_ID = ".SyncthingService.EXTRA_FOLDER_ID"

        /** Extra used together with ACTION_IGNORE_FOLDER. */
        const val EXTRA_FOLDER_LABEL = ".SyncthingService.EXTRA_FOLDER_LABEL"

        /** Extra used together with ACTION_STOP. */
        const val EXTRA_STOP_AFTER_CRASHED_NATIVE = ".SyncthingService.EXTRA_STOP_AFTER_CRASHED_NATIVE"
    }

    /**
     * Outcome of [replaceHttpsCertificate] / [resetHttpsCertificate].
     */
    enum class HttpsCertReplaceResult {
        /** The new certificate was applied and Syncthing came back online with it. */
        SUCCESS,
        /** The files were written but Syncthing is not currently meant to run; applies on next start. */
        SUCCESS_PENDING_START,
        /** The change failed and the previous certificate was restored. */
        FAILED,
    }

    fun interface OnHttpsCertReplaceResultListener {
        fun onResult(result: HttpsCertReplaceResult, errorDetail: String?)
    }

    fun interface OnServiceStateChangeListener {
        fun onServiceStateChange(currentState: State)
    }

    /**
     * Indicates the current state of SyncthingService and of Syncthing itself.
     */
    enum class State {
        /**
         * Service is initializing, Syncthing was not started yet.
         */
        INIT,
        /**
         * Syncthing binary is starting.
         */
        STARTING,
        /**
         * Syncthing binary is running,
         * Rest API is available,
         * RestApi class read the config and is fully initialized.
         */
        ACTIVE,
        /**
         * Syncthing binary is shutting down.
         */
        DISABLED,
        /**
         * There is some problem that prevents Syncthing from running.
         */
        ERROR,
    }

    private var enableVerboseLog = false

    /**
     * Initialize the service with State.DISABLED as [RunConditionMonitor] will
     * send an update if we should run the binary after it got instantiated in
     * [onStartCommand].
     */
    var currentState: State = State.DISABLED
        private set

    private lateinit var configRouter: ConfigRouter
    private var config: ConfigXml? = null
    private var syncthingRunnableThread: Thread? = null

    /**
     * Serializes the blocking kill/join work of the native binary off the main thread.
     * Shutdown and old-instance cleanup jobs run here in FIFO order; their continuations
     * are posted back to [handler] (main thread).
     */
    private val shutdownExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SyncthingShutdown")
    }

    /**
     * Main-thread-only guard: true while a kill by binary name is queued or running on
     * [shutdownExecutor]. [launchStartupTask] defers while this is set, so a freshly
     * spawned binary can never race a name-based kill aimed at the previous instance.
     */
    private var binaryKillPending = false

    /**
     * Main-thread-only counter of consecutive startups that aborted because the WebUI
     * port stayed occupied after the stale-binary kill (see [onWebUiPortOccupied]).
     * Reset as soon as a startup passes the port check.
     */
    private var portBusyRetryCount = 0

    private lateinit var handler: Handler

    private val onServiceStateChangeListeners = HashSet<OnServiceStateChangeListener>()
    private val binder = SyncthingServiceBinder(this)

    /**
     * Scope for the web gui availability poll. Callbacks are delivered on the
     * main thread like the old Volley/PollWebGuiAvailableTask path did.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var webGuiPollJob: Job? = null

    var api: RestApi? = null
        private set

    /**
     * Adds the "Syncthing Camera" folder to the RUNNING config right after the user
     * enabled the feature, so no restart is needed. While the core is not running,
     * ConfigXml.updateIfNeeded covers it at the next startup instead.
     */
    private val syncthingCameraPrefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Constants.PREF_ENABLE_SYNCTHING_CAMERA) {
                addSyncthingCameraFolderIfMissing()
            }
        }

    private var eventPoller: EventPoller? = null

    /** Forwarded changes that happened before the REST config became usable. */
    private val pendingSafRescans = LinkedHashSet<String>()

    private var runConditionMonitor: RunConditionMonitor? = null

    private var syncthingRunnable: SyncthingRunnable? = null

    lateinit var notificationHandler: NotificationHandler

    lateinit var preferences: SharedPreferences

    /**
     * Object that must be locked upon accessing [currentState]
     */
    private val stateLock = Any()

    /**
     * Stores the result of the last should run decision received by OnShouldRunChangedListener.
     */
    private var lastDeterminedShouldRun = false

    /**
     * True if the user granted the storage permission.
     */
    private var storagePermissionGranted = false

    private lateinit var configBackupManager: ConfigBackupManager

    private lateinit var httpsCertManager: HttpsCertManager

    /**
     * True if the last run condition decision told the service to (re)start the binary.
     */
    fun shouldRunAfterRestart(): Boolean {
        return lastDeterminedShouldRun
    }

    /**
     * Starts the native binary.
     */
    override fun onCreate() {
        super.onCreate()
        val app = application as SyncthingApp
        notificationHandler = app.notificationHandler
        preferences = app.preferences
        enableVerboseLog = AppPrefs.getPrefVerboseLog(preferences)
        LogV("onCreate")
        // Note: handing app storage back from a previous root-mode session happens on the
        // shutdown executor inside [launchStartupTask] - running the recursive chown (and a
        // possible first su spawn with an authorization dialog) here on the main thread
        // would block startup and risk ANR.
        configRouter = ConfigRouter(this)
        handler = Handler()
        configBackupManager = ConfigBackupManager(this, preferences, enableVerboseLog)
        httpsCertManager = HttpsCertManager(this, handler)

        // If runtime permissions are revoked, android kills and restarts the service.
        // We need to recheck if we still have the storage permission.
        storagePermissionGranted = PermissionUtil.haveStoragePermission(this)

        notificationHandler.setAppShutdownInProgress(false)

        // Nudge the core to rescan a forwarded folder whenever the bridge wrote
        // changes into it (the core cannot see SAF/provider-side changes itself).
        // api is resolved lazily because it may not exist yet at onCreate time. Keep a pending
        // set so the initial bridge reconcile cannot lose its nudge before config load.
        app.safBridge.onForwardedDirChanged = { folderPath ->
            requestSafCoreRescan(folderPath)
        }
        // Install the callback before starting bridges. The first reconcile is immediate and
        // may finish before the REST API exists.
        app.safBridge.startAll()
        preferences.registerOnSharedPreferenceChangeListener(syncthingCameraPrefListener)
    }

    /**
     * Handles intent actions, e.g. [ACTION_RESTART]
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        if (!storagePermissionGranted) {
            Log.e(TAG, "User revoked storage permission. Stopping service.")
            notificationHandler.showStoragePermissionRevokedNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        // Run condition monitor is enabled. Instantiate it on first onStartCommand
        // and enable callback on run condition change affecting the final decision
        // to run/terminate syncthing. After initial run conditions are collected
        // the first decision is sent to [onShouldRunDecisionChanged].
        if (runConditionMonitor == null) {
            // Kotlin cannot SAM-convert method references to plain interfaces;
            // use object expressions (phase4 decision).
            runConditionMonitor = RunConditionMonitor(
                this,
                object : RunConditionMonitor.OnShouldRunChangedListener {
                    override fun onShouldRunDecisionChanged(shouldRun: Boolean) {
                        this@SyncthingService.onShouldRunDecisionChanged(shouldRun)
                    }
                },
                object : RunConditionMonitor.OnSyncPreconditionChangedListener {
                    override fun onSyncPreconditionChanged(runConditionMonitor: RunConditionMonitor) {
                        this@SyncthingService.applyCustomRunConditions(runConditionMonitor)
                    }
                }
            )
        }
        notificationHandler.updatePersistentNotification(this)

        if (intent == null) {
            return START_STICKY
        }

        if (ACTION_RESTART == intent.action) {
            when (currentState) {
                State.ACTIVE -> shutdownToState(State.INIT) {
                    launchStartupTask(SyncthingRunnable.Command.main)
                }
                State.STARTING -> {
                    // A core is still launching (with the previous settings, e.g. the
                    // privilege mode). Restarting it now would kill it mid-startup, and
                    // silently dropping the request would leave the running core
                    // diverged from the preference - defer until the startup settled.
                    Log.w(TAG, "Restart requested in state $currentState, deferring ...")
                    scheduleRestartRetry()
                }
                else -> Log.i(TAG,
                        "Restart requested in state $currentState - core is not running, " +
                        "the next start picks up the new settings")
            }
        } else if (ACTION_STOP == intent.action) {
            if (intent.getBooleanExtra(EXTRA_STOP_AFTER_CRASHED_NATIVE, false)) {
                // We were requested to stop the service because the syncthing native
                // binary crashed. Changing currentState prevents the "defer until
                // syncthing is started" routine we normally use for clean shutdown
                // to take place. Instead, we will immediately shutdown the crashed
                // instance forcefully.
                currentState = State.ERROR
                shutdownToState(State.DISABLED)
            } else {
                // Graceful shutdown.
                if (currentState == State.STARTING ||
                    currentState == State.ACTIVE
                ) {
                    shutdownToState(State.DISABLED)
                }
            }
        } else if (ACTION_RESET_DATABASE == intent.action) {
            // 1. Stop syncthing native if it's running.
            // 2. Reset the database, syncthing native will exit after performing the reset.
            // 3. Relaunch syncthing native if it was previously running.
            Log.i(TAG, "Invoking reset of database")
            val resetDatabaseAndRestart = {
                SyncthingRunnable(this, SyncthingRunnable.Command.resetdatabase).run()
                if (lastDeterminedShouldRun) {
                    launchStartupTask(SyncthingRunnable.Command.main)
                }
            }
            if (currentState != State.DISABLED) {
                // Shutdown the running binary first; its kill/join runs on the shutdown
                // executor and we continue once it has fully exited.
                shutdownToState(State.DISABLED) { resetDatabaseAndRestart() }
            } else {
                resetDatabaseAndRestart()
            }
        } else if (ACTION_RESET_DELTAS == intent.action) {
            // 1. Stop syncthing native if it's running.
            // 2. Reset delta index, syncthing native will NOT exit after performing the reset.
            // 3. If syncthing was previously NOT running: schedule a shutdown of the native
            //    binary after it left State.STARTING (to State.ACTIVE) - the moment when the
            //    reset delta index work was completed and Web UI came up. The shutdown gets
            //    deferred until State.ACTIVE was reached, then syncthing native will be
            //    shutdown synchronously.
            Log.i(TAG, "Invoking reset of delta indexes")
            val launchResetDeltasAndMaybeStop = {
                launchStartupTask(SyncthingRunnable.Command.resetdeltas)
                if (!lastDeterminedShouldRun) {
                    // Shutdown if syncthing was not running before the UI action was raised.
                    shutdownToState(State.DISABLED)
                }
            }
            if (currentState != State.DISABLED) {
                shutdownToState(State.DISABLED) { launchResetDeltasAndMaybeStop() }
            } else {
                launchResetDeltasAndMaybeStop()
            }
        } else if (ACTION_REFRESH_NETWORK_INFO == intent.action) {
            runConditionMonitor?.updateShouldRunDecision()
        } else if (ACTION_IGNORE_DEVICE == intent.action) {
            configRouter.ignoreDevice(
                api,
                intent.getStringExtra(EXTRA_DEVICE_ID),
                intent.getStringExtra(EXTRA_DEVICE_NAME),
                intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
            )
            notificationHandler.cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        } else if (ACTION_IGNORE_FOLDER == intent.action) {
            configRouter.ignoreFolder(
                api,
                intent.getStringExtra(EXTRA_DEVICE_ID),
                intent.getStringExtra(EXTRA_FOLDER_ID),
                intent.getStringExtra(EXTRA_FOLDER_LABEL)
            )
            notificationHandler.cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        } else if (ACTION_OVERRIDE_CHANGES == intent.action && currentState == State.ACTIVE) {
            intent.getStringExtra(EXTRA_FOLDER_ID)?.let { api?.overrideChanges(it) }
        } else if (ACTION_REVERT_LOCAL_CHANGES == intent.action && currentState == State.ACTIVE) {
            intent.getStringExtra(EXTRA_FOLDER_ID)?.let { api?.revertLocalChanges(it) }
        } else {
            afterFreshServiceInstanceStart()
        }
        return START_STICKY
    }

    /**
     * Event handler to catch a fresh service startup right after the run condition
     * evaluation took place and SyncthingNative may be starting in the background
     * meanwhilst or non-present.
     */
    private fun afterFreshServiceInstanceStart() {
        LogV("afterFreshServiceInstanceStart: Service started from scratch, SyncthingNative is going to STATE_$currentState meanwhilst ...")
        if (currentState == State.DISABLED) {
            // Read and parse the config from disk.
            val configXml = ConfigXml(this)
            try {
                configXml.loadConfig()
            } catch (e: ConfigXml.OpenConfigException) {
                notificationHandler.showCrashedNotification(R.string.config_read_failed, "afterFreshServiceInstanceStart:OpenConfigException")
                synchronized(stateLock) {
                    onServiceStateChange(State.ERROR)
                }
                stopSelf()
                return
            }
        }
    }

    /**
     * After run conditions monitored by [RunConditionMonitor] changed and
     * it had an influence on the decision to run/terminate syncthing, this
     * function is called to notify this class to run/terminate the syncthing binary.
     * [onServiceStateChange] is called while applying the decision change.
     */
    private fun onShouldRunDecisionChanged(newShouldRunDecision: Boolean) {
        if (newShouldRunDecision != lastDeterminedShouldRun) {
            Log.i(TAG, "shouldRun decision changed to $newShouldRunDecision according to configured run conditions.")
            lastDeterminedShouldRun = newShouldRunDecision

            // React to the shouldRun condition change.
            if (newShouldRunDecision) {
                // Start syncthing.
                when (currentState) {
                    State.DISABLED, State.INIT ->
                        launchStartupTask(SyncthingRunnable.Command.main)
                    State.STARTING, State.ACTIVE, State.ERROR -> {}
                }
            } else {
                // Stop syncthing.
                if (currentState == State.DISABLED) {
                    return
                }
                shutdownToState(State.DISABLED)
            }
        }
    }

    /**
     * After sync preconditions changed, we need to inform [RestApi] to pause or
     * unpause devices and folders as defined in per-object sync preferences.
     */
    private fun applyCustomRunConditions(runConditionMonitor: RunConditionMonitor) {
        synchronized(stateLock) {
            if (api != null && currentState == State.ACTIVE) {
                // Forward event because syncthing is running.
                api?.applyCustomRunConditions(runConditionMonitor)
                return
            }
        }

        var configChanged = false

        // Read and parse the config from disk.
        val configXml = ConfigXml(this)
        try {
            configXml.loadConfig()
        } catch (e: ConfigXml.OpenConfigException) {
            notificationHandler.showCrashedNotification(R.string.config_read_failed, "applyCustomRunConditions:OpenConfigException")
            synchronized(stateLock) {
                onServiceStateChange(State.ERROR)
            }
            stopSelf()
            return
        }

        // Check if the folders are available from config.
        val folders: List<Folder>? = configXml.folders
        if (folders != null) {
            for (folder in folders) {
                val folderPrefixAndId = Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id
                val shouldPause = runConditionMonitor.getCustomSyncConditionsPause(folderPrefixAndId)
                if (shouldPause == null) {
                    continue
                }
                LogV("applyCustomRunConditions: f(${folder.label})=${if (!shouldPause) "1" else "0"}")
                if (folder.paused != shouldPause) {
                    configXml.setFolderPause(folder.id, shouldPause)
                    Log.d(TAG, "applyCustomRunConditions: f(${folder.label})=${if (!shouldPause) ">1" else ">0"}")
                    configChanged = true
                }
            }
        } else {
            Log.d(TAG, "applyCustomRunConditions: folders == null")
            return
        }

        // Check if the devices are available from config.
        val devices: List<Device>? = configXml.getDevices(false)
        if (devices != null) {
            for (device in devices) {
                val devicePrefixAndId = Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID
                val shouldPause = runConditionMonitor.getCustomSyncConditionsPause(devicePrefixAndId)
                if (shouldPause == null) {
                    continue
                }
                LogV("applyCustomRunConditions: d(${device.name})=${if (!shouldPause) "1" else "0"}")
                if (device.paused != shouldPause) {
                    configXml.setDevicePause(device.deviceID, shouldPause)
                    Log.d(TAG, "applyCustomRunConditions: d(${device.name})=${if (!shouldPause) ">1" else ">0"}")
                    configChanged = true
                }
            }
        } else {
            Log.d(TAG, "applyCustomRunConditions: devices == null")
            return
        }

        if (configChanged) {
            LogV("applyCustomRunConditions: Saving changed config ...")
            configXml.saveChanges()
        } else {
            LogV("applyCustomRunConditions: No action was necessary.")
        }
    }

    /**
     * Prepares to launch the syncthing binary.
     */
    fun launchStartupTask(srCommand: SyncthingRunnable.Command) {
        if (binaryKillPending) {
            // A kill by binary name (shutdown or old-instance cleanup) is still in flight;
            // spawning now could get the new binary SIGINTed by that kill.
            handler.postDelayed({ launchStartupTask(srCommand) }, SHUTDOWN_RETRY_DELAY_MS)
            return
        }

        synchronized(stateLock) {
            if (currentState != State.DISABLED && currentState != State.INIT) {
                Log.e(TAG, "launchStartupTask: Wrong state $currentState detected. Cancelling.")
                return
            }
        }

        val config = ConfigXml(this)
        try {
            config.loadConfig()
        } catch (e: ConfigXml.OpenConfigException) {
            notificationHandler.showCrashedNotification(R.string.config_read_failed, "launchStartupTask:OpenConfigException")
            synchronized(stateLock) {
                onServiceStateChange(State.ERROR)
            }
            stopSelf()
            return
        }
        this.config = config

        onServiceStateChange(State.STARTING)

        if (api == null) {
            api = RestApi(
                this, config.webGuiUrl, config.apiKey,
                { onApiAvailable() },
                { onServiceStateChange(currentState) },
                runAsRoot = AppPrefs.getLastCoreRunAsRoot(preferences),
            )
            Log.i(TAG, "Web GUI will be available at ${config.webGuiUrl}")
        }

        // Check syncthingRunnable lifecycle and create singleton.
        if (syncthingRunnable != null || syncthingRunnableThread != null) {
            Log.e(TAG, "onStartupTaskCompleteListener: Syncthing binary lifecycle violated")
            return
        }
        val runnable = SyncthingRunnable(this, srCommand)
        syncthingRunnable = runnable

        // End any still-running old instance (eg left over from an in-place app upgrade)
        // on the shutdown executor, then spawn the new binary from the main thread. The
        // cleanup kills by process name, so spawning must wait for it to complete.
        binaryKillPending = true
        shutdownExecutor.execute {
            // Safety net for starts after a root-mode session (e.g. the app process was
            // killed while the root-uid core was up): config and key material are root-owned
            // with explicit 0600 modes and must be handed back to the app UID before the
            // config is parsed further below. Runs here on the background executor - the
            // recursive chown and a possible first su spawn (Magisk dialog) must never run
            // on the main thread. Detected via the config file's actual owner - not the
            // launch marker, which a failed non-root launch would have reset. Marker is
            // intentionally kept: killStaleBinary still needs root to stop an orphaned
            // root core; the non-root launch path clears it.
            if (RootAccess.appStorageOwnedByRoot(this)) {
                Log.i(TAG, "Previous core ran as root; returning app storage to app ownership")
                RootAccess.handBackStorage(this)
            }
            killStaleBinary()
            // Probe the WebUI port AFTER the stale-binary kill, on this background
            // thread: a loopback TCP connect is a network op and must not run on the
            // main thread, and only now do we know whether the kill actually freed the
            // port. A root-uid core survives an app force-stop and ps-based matching
            // does not always find it, so the kill above can be a no-op even with a
            // stale instance still bound to our port - spawning into it would just
            // exit with code 1 ("another instance may be already running").
            val portOccupied = Util.isTcpPortListening(config.webGuiBindPort)
            handler.post {
                binaryKillPending = false
                if (syncthingRunnable == null) {
                    // shutdownToState ran while the cleanup was in flight and consumed the
                    // pending runnable - the startup was aborted, do not spawn.
                    Log.i(TAG, "launchStartupTask: Startup aborted, binary was shut down in the meantime.")
                    return@post
                }
                if (portOccupied) {
                    onWebUiPortOccupied(config.webGuiBindPort)
                    return@post
                }
                portBusyRetryCount = 0
                startBinaryThread(runnable, config)
            }
        }
    }

    /**
     * The WebUI port is still bound after the stale-binary kill - most likely a root-uid
     * core that survived an app force-stop and that neither ps-based matching nor the
     * port-owner kill could stop. Spawning now would just exit with code 1.
     *
     * Self-heals through the normal shutdown-to-INIT cycle: its prepareShutdown runs
     * RestApi.shutdown, which POSTs system/shutdown to whatever is listening on the
     * WebUI port - with the same config and API key as the stale core, so the stale core
     * exits gracefully. Then the startup is retried; after [MAX_PORT_BUSY_RETRIES]
     * failed retries the service gives up with State.ERROR.
     */
    private fun onWebUiPortOccupied(port: Int) {
        Log.e(TAG, "launchStartupTask: WebUI tcp port $port still occupied after stale kill " +
                "(stale root core?). Retry $portBusyRetryCount/$MAX_PORT_BUSY_RETRIES")
        notificationHandler.showCrashedNotification(R.string.webui_tcp_port_unavailable, port.toString())
        if (portBusyRetryCount >= MAX_PORT_BUSY_RETRIES) {
            synchronized(stateLock) {
                onServiceStateChange(State.ERROR)
            }
            stopSelf()
            return
        }
        portBusyRetryCount++
        shutdownToState(State.INIT) {
            launchStartupTask(SyncthingRunnable.Command.main)
        }
    }

    /**
     * Retries a deferred [ACTION_RESTART] (requested while State.STARTING) until the
     * startup settled. Bounded by [MAX_RESTART_RETRIES]: a startup that never reaches
     * ACTIVE must not queue retries forever. If the startup aborted (ERROR/DISABLED)
     * or the service was destroyed, the core is no longer running and the next natural
     * start picks up the new settings - the retry is dropped.
     */
    private fun scheduleRestartRetry(attempt: Int = 0) {
        if (attempt >= MAX_RESTART_RETRIES) {
            Log.w(TAG, "scheduleRestartRetry: Giving up after $attempt retries in state $currentState")
            return
        }
        handler.postDelayed({
            when (currentState) {
                State.ACTIVE -> shutdownToState(State.INIT) {
                    launchStartupTask(SyncthingRunnable.Command.main)
                }
                State.STARTING -> scheduleRestartRetry(attempt + 1)
                else -> Log.i(TAG,
                        "Deferred restart aborted in state $currentState - core is not running")
            }
        }, SHUTDOWN_RETRY_DELAY_MS)
    }

    private fun startBinaryThread(runnable: SyncthingRunnable, config: ConfigXml) {
        // Start the syncthing binary in a separate thread.
        val syncthingRunnableThread = Thread(runnable)
        syncthingRunnableThread.setUncaughtExceptionHandler { _, _ ->
            Log.e(TAG, "syncthingRunnableThread: Uncaught exception [ExecutableNotFoundException]")
            notificationHandler.showCrashedNotification(R.string.executable_not_found, Constants.FILENAME_SYNCTHING_BINARY)
        }
        this.syncthingRunnableThread = syncthingRunnableThread
        syncthingRunnableThread.start()

        // Wait for the web-gui of the native syncthing binary to come online.
        //
        // In case the binary is to be stopped, also be aware that another thread could request
        // to stop the binary in the time while waiting for the GUI to become active. See the
        // comment for [onDestroy] for details.
        if (webGuiPollJob == null) {
            // Poll for the web-gui of the native syncthing binary to come online.
            //
            // In case the binary is to be stopped, also be aware that another thread could request
            // to stop the binary in the time while waiting for the GUI to become active. See the
            // comment for [onDestroy] for details.
            val pollClient = ApiClient(
                httpsCertFile = Constants.getHttpsCertFile(this),
                url = config.webGuiUrl,
                apiKey = config.apiKey,
                logFailures = false,
            )
            webGuiPollJob = serviceScope.launch {
                pollWebGuiUntilAvailable(
                    fetch = { pollClient.get("") },
                    webGuiUrl = config.webGuiUrl.toString(),
                    onAvailable = { api?.readConfigFromRestApi() },
                )
            }
        }
    }

    /**
     * Called when [RestApi.checkReadConfigFromRestApiCompleted] detects
     * the RestApi class has been fully initialized.
     * UI stressing results in api getting null on simultaneous shutdown, so
     * we check it for safety.
     */
    private fun onApiAvailable() {
        val restApi = api
        if (restApi == null) {
            Log.e(TAG, "onApiAvailable: Did we stop the binary during startup? api == null")
            return
        }
        synchronized(stateLock) {
            if (currentState != State.STARTING) {
                Log.e(TAG, "onApiAvailable: Wrong state $currentState detected. Cancelling callback.")
                return
            }
            onServiceStateChange(State.ACTIVE)
        }

        if (eventPoller == null) {
            eventPoller = EventPoller(this, restApi).also { it.start() }
        }

        val pending = synchronized(pendingSafRescans) {
            val paths = pendingSafRescans.toList()
            pendingSafRescans.clear()
            paths
        }
        pending.forEach { restApi.rescanFolderByPath(it) }

        // Close the "enabled while STARTING" gap: ConfigXml already ran before the
        // pref was flipped, so make sure the camera folder exists once the API is up.
        addSyncthingCameraFolderIfMissing()
    }

    private fun addSyncthingCameraFolderIfMissing() {
        if (!preferences.getBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, false)) {
            return
        }
        val restApi = api ?: return
        if (!restApi.isConfigLoaded) {
            return
        }
        if (restApi.folders.any { it.id == Constants.syncthingCameraFolderId }) {
            return
        }
        val folder = buildSyncthingCameraFolder(this)
        if (folder == null) {
            Log.e(TAG, "addSyncthingCameraFolderIfMissing: Could not determine the camera storage dir")
            return
        }
        Log.i(TAG, "addSyncthingCameraFolderIfMissing: Adding camera folder to the running config")
        restApi.addFolder(folder)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    /**
     * Stops the native binary.
     * Shuts down RunConditionMonitor instance.
     */
    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        preferences.unregisterOnSharedPreferenceChangeListener(syncthingCameraPrefListener)
        val safBridge = (application as SyncthingApp).safBridge
        safBridge.onForwardedDirChanged = null
        safBridge.stopAll()
        synchronized(pendingSafRescans) { pendingSafRescans.clear() }
        if (runConditionMonitor != null) {
            // Shut down the OnShouldRunChangedListener so we won't get interrupted by run
            // condition events that occur during shutdown.
            runConditionMonitor?.shutdown()
        }
        notificationHandler.setAppShutdownInProgress(true)
        if (!storagePermissionGranted) {
            // If the storage permission got revoked, we did not start the binary and
            // are in State.INIT requiring an immediate shutdown of this service class.
            Log.i(TAG, "Shutting down syncthing binary due to missing storage permission.")
        }
        shutdownToState(State.DISABLED)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun requestSafCoreRescan(folderPath: String) {
        val readyApi: RestApi?
        synchronized(pendingSafRescans) {
            val currentApi = api
            if (currentApi == null || !currentApi.isConfigLoaded) {
                pendingSafRescans.add(folderPath)
                return
            }
            readyApi = currentApi
        }
        readyApi?.rescanFolderByPath(folderPath)
    }

    internal fun pauseSafBridgesForConfigImport(): Boolean {
        return (application as SyncthingApp).safBridge.pauseForConfigImport()
    }

    internal fun resumeSafBridgesAfterConfigImport(wasStarted: Boolean) {
        (application as SyncthingApp).safBridge.resumeAfterConfigImport(wasStarted)
    }

    /**
     * Stop SyncthingNative and all helpers like event processor and api handler.
     * Sets [currentState] to newState.
     *
     * The blocking kill/join of the native binary runs on [shutdownExecutor]: the calling
     * (main) thread returns immediately and [onShutdownComplete] is invoked on the main
     * thread once the binary has fully exited. Must be called from the main thread.
     */
    fun shutdownToState(newState: State, onShutdownComplete: (() -> Unit)? = null) {
        if (currentState == State.STARTING) {
            Log.w(TAG, "Deferring shutdown until State.STARTING was left")
            handler.postDelayed({ shutdownToState(newState, onShutdownComplete) }, SHUTDOWN_RETRY_DELAY_MS)
            return
        }

        prepareShutdown(newState)

        val runnable = syncthingRunnable
        val runnableThread = syncthingRunnableThread
        syncthingRunnable = null
        syncthingRunnableThread = null

        if (runnable == null) {
            onShutdownComplete?.let { handler.post(it) }
            return
        }

        binaryKillPending = true
        shutdownExecutor.execute {
            killBinaryAndAwaitExit(runnable, runnableThread)
            handler.post {
                binaryKillPending = false
                onShutdownComplete?.invoke()
            }
        }
    }

    /**
     * Synchronous shutdown variant for callers that must continue only after the binary has
     * exited (eg config backup on a background thread). Blocks the calling thread, so it
     * must never be called from the main thread - use [shutdownToState] there.
     */
    fun shutdownToStateBlocking(newState: State) {
        if (currentState == State.STARTING) {
            Log.w(TAG, "Deferring shutdown until State.STARTING was left")
            handler.postDelayed({ shutdownToStateBlocking(newState) }, SHUTDOWN_RETRY_DELAY_MS)
            return
        }

        prepareShutdown(newState)

        val runnable = syncthingRunnable ?: return
        val runnableThread = syncthingRunnableThread
        syncthingRunnable = null
        syncthingRunnableThread = null

        killBinaryAndAwaitExit(runnable, runnableThread)
    }

    private fun prepareShutdown(newState: State) {
        synchronized(stateLock) {
            onServiceStateChange(newState)
        }

        webGuiPollJob?.cancel()
        webGuiPollJob = null

        eventPoller?.let {
            it.stop()
            eventPoller = null
        }

        notificationHandler.cancelRestartNotification()

        api?.let {
            if (syncthingRunnable != null) {
                it.shutdown()
            }
            api = null
        }
    }

    private fun killBinaryAndAwaitExit(runnable: SyncthingRunnable, runnableThread: Thread?) {
        // Mark BEFORE the kill so the runnable's exit watcher sees the flag as soon as
        // waitFor returns: a graceful shutdown exceeding the kill grace period gets
        // escalated to SIGKILL by our own killProcess, and the resulting exit 137 must
        // not be reported as a crash.
        runnable.markPlannedShutdown()
        killStaleBinary()
        runnableThread?.let { thread ->
            LogV("Waiting for syncthingRunnableThread to finish after killProcess(Syncthing) ...")
            try {
                thread.join()
            } catch (e: InterruptedException) {
                Log.w(TAG, "syncthingRunnableThread InterruptedException")
            }
            Log.d(TAG, "Finished syncthingRunnableThread.")
        }
    }

    /**
     * Ends stale instances of the syncthing binary, e.g. leftovers from an in-place app
     * upgrade or the currently running core during a forced shutdown.
     *
     * The privilege mode comes from the last-launch marker, not the user preference: right
     * after a toggle the running core still has the previous mode, and a root-uid core is
     * invisible to the app's own `ps` (kill EPERM). Root lookups use the FULL binary path —
     * other installed forks ship cores with the same basename, which a basename match
     * would kill as collateral.
     */
    private fun killStaleBinary() {
        val asRoot = AppPrefs.getLastCoreRunAsRoot(preferences)
        val processName = if (asRoot) {
            Constants.getSyncthingBinary(this).absolutePath
        } else {
            Constants.FILENAME_SYNCTHING_BINARY
        }
        Util.killProcess(processName, asRoot)
        // Last resort for a stale root-uid core that survived an app force-stop: the
        // ps-based kill above can come up empty (the shell may not see the root process
        // or may have fallen back to an unprivileged shell). Whoever still holds our
        // WebUI port is then almost certainly that stale core - kill it by port owner,
        // but only after verifying it is an instance of our own binary.
        val port = config?.webGuiBindPort
        if (asRoot && port != null && Util.isTcpPortListening(port)) {
            Log.w(TAG, "killStaleBinary: WebUI port $port still in use after ps-based kill, " +
                    "trying port-owner kill")
            Util.killPortListenerAsRoot(port, Constants.getSyncthingBinary(this).name)
        }
    }

    /**
     * Force re-evaluating run conditions immediately e.g. after
     * preferences were modified by SettingsActivity#onStop.
     */
    fun evaluateRunConditions() {
        if (runConditionMonitor == null) {
            return
        }
        Log.d(TAG, "Forced re-evaluating run conditions ...")
        runConditionMonitor?.updateShouldRunDecision()
    }

    /**
     * Register a listener for the syncthing API state changing.
     * The listener is called immediately with the current state, and again whenever the state
     * changes. The call is always from the GUI thread.
     *
     * @see unregisterOnServiceStateChangeListener
     */
    fun registerOnServiceStateChangeListener(listener: OnServiceStateChangeListener) {
        // Initially send the current state to the new subscriber to make sure it doesn't stay
        // in undefined state forever until the state next change occurs.
        listener.onServiceStateChange(currentState)
        onServiceStateChangeListeners.add(listener)
    }

    /**
     * Unregisters a previously registered listener.
     *
     * @see registerOnServiceStateChangeListener
     */
    fun unregisterOnServiceStateChangeListener(listener: OnServiceStateChangeListener) {
        onServiceStateChangeListeners.remove(listener)
    }

    /**
     * Called to notify listeners of an API change.
     */
    private fun onServiceStateChange(newState: State) {
        if (newState == currentState) {
            Log.d(TAG, "onServiceStateChange: Called with unchanged state $newState")
            return
        }
        Log.i(TAG, "onServiceStateChange: from $currentState to $newState")
        currentState = newState
        handler.post {
            notificationHandler.updatePersistentNotification(this)
            val iterator = onServiceStateChangeListeners.iterator()
            while (iterator.hasNext()) {
                iterator.next().onServiceStateChange(currentState)
            }
        }
    }

    val runDecisionExplanation: String
        get() {
            val monitor = runConditionMonitor
                ?: return resources.getString(R.string.reason_run_condition_monitor_not_instantiated)
            return monitor.getRunDecisionExplanation()
        }

    /**
     * Exports the local config and keys to [Constants.EXPORT_PATH].
     */
    fun exportConfig(): Boolean {
        return configBackupManager.exportConfig()
    }

    /**
     * Imports config and keys from [Constants.EXPORT_PATH].
     *
     * @return True if the import was successful, false otherwise (eg if files aren't found).
     */
    fun importConfig(): Boolean {
        return configBackupManager.importConfig()
    }

    fun replaceHttpsCertificate(
        certPem: ByteArray,
        keyPem: ByteArray,
        listener: OnHttpsCertReplaceResultListener
    ) {
        httpsCertManager.replaceHttpsCertificate(certPem, keyPem, listener)
    }

    fun resetHttpsCertificate(listener: OnHttpsCertReplaceResultListener) {
        httpsCertManager.resetHttpsCertificate(listener)
    }

    private fun LogV(logMessage: String) {
        if (enableVerboseLog) {
            Log.v(TAG, logMessage)
        }
    }
}

/**
 * Polls [fetch] every WEB_GUI_POLL_INTERVAL ms until one
 * attempt succeeds, then invokes [onAvailable] exactly once and returns.
 *
 * This replaces the former `PollWebGuiAvailableTask` (deleted in phase7): the poll loop is
 * inlined into the service, which is now Kotlin, but kept as a top-level function taking a
 * `fetch` lambda so the retry/cancel semantics stay unit-testable without a real service.
 *
 * Behaviour parity with the deleted implementation:
 *  - Transport failures (syncthing not up yet) retry silently: connection failures and
 *    timeouts log at most once every 10 attempts, everything else warns.
 *  - Cancelling the calling coroutine stops the poll and the in-flight request (ApiClient
 *    awaits the OkHttp call with coroutine-aware cancellation), like the old
 *    `cancelRequestsAndCallback` did.
 */
internal suspend fun pollWebGuiUntilAvailable(
    fetch: suspend () -> String,
    webGuiUrl: String,
    onAvailable: () -> Unit,
) {
    Log.i(TAG, "Starting to poll for web gui availability")
    var logIncidence = 0
    while (true) {
        try {
            fetch()
            Log.i(TAG, "Web GUI has come online at $webGuiUrl")
            onAvailable()
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (e is ConnectException || e is SocketTimeoutException) {
                logIncidence++
                if (logIncidence == 1 || logIncidence % 10 == 0) {
                    Log.v(TAG, "Polling web gui ... ($logIncidence)")
                }
            } else {
                Log.w(TAG, "Unexpected error while polling web gui", e)
            }
            delay(WEB_GUI_POLL_INTERVAL)
        }
    }
}
