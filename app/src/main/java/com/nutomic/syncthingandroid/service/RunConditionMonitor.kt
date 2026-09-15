package com.nutomic.syncthingandroid.service

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SyncStatusObserver
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.util.JobUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Holds information about the current wifi and charging state of the device.
 *
 * This information is actively read on instance creation, and then updated from intents.
 *
 * Threading model: system broadcast receivers run on the main thread and call
 * [updateShouldRunDecision] synchronously. The asynchronously dispatched sources (default
 * network callback, sync status observer, delayed battery re-evaluation) hand off through
 * [monitorScope] on [Dispatchers.Main], so every decision is evaluated on the main thread.
 *
 * [shouldRunFlow] and [runDecisionExplanationFlow] expose the last decision as observable
 * state; the [OnShouldRunChangedListener] / [OnSyncPreconditionChangedListener] callbacks
 * remain for the service.
 *
 * [shutdown] cancels [monitorScope], so pending delayed re-evaluations (e.g. a battery
 * update scheduled 5s before shutdown) no longer fire after the monitor is torn down.
 */
class RunConditionMonitor(
    private val context: Context,
    private val onShouldRunChangedListener: OnShouldRunChangedListener,
    private val onSyncPreconditionChangedListener: OnSyncPreconditionChangedListener,
) {

    companion object {
        private const val TAG = "RunConditionMonitor"

        /**
         * Delay before re-evaluating run conditions after a power state change,
         * so the OS has time to update the battery sticky broadcast.
         */
        private const val BATTERY_UPDATE_DELAY_MS = 5000L

        /**
         * Default values of the "Run on a time schedule" user preferences, in minutes.
         */
        private const val DEFAULT_SYNC_DURATION_MINUTES = "5"
        private const val DEFAULT_SLEEP_INTERVAL_MINUTES = "60"
    }

    interface OnShouldRunChangedListener {
        fun onShouldRunDecisionChanged(shouldRun: Boolean)
    }

    interface OnSyncPreconditionChangedListener {
        fun onSyncPreconditionChanged(runConditionMonitor: RunConditionMonitor)
    }

    /**
     * Result of a single sync condition evaluator.
     */
    private class SyncConditionResult(val conditionMet: Boolean, val explanation: String = "")

    lateinit var preferences: SharedPreferences

    private val res: Resources = context.resources

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val verboseLog: Boolean

    private var runDecisionExplanation: String = ""

    /**
     * Only relevant if the user has enabled turning Syncthing on by
     * time schedule for a specific amount of time periodically.
     * Holds true if we are within a "SyncthingNative should run" time frame.
     * Initial status false because we check if the last sync was more than one hour ago on app start.
     */
    private var timeConditionMatch: Boolean = false

    // Avoid re-scheduling start if run conditions change while already running.
    private var runAllowedStopScheduled: Boolean = false

    private var triggeredSyncDurationS: Int = 10
    private var triggeredSyncSleepIntervalS: Int = 10

    private var syncStatusObserverHandle: Any? = null

    /**
     * API 24+: Replaces the deprecated CONNECTIVITY_ACTION broadcast.
     * The callback only notifies about default network changes; the current
     * network state is always read synchronously from ConnectivityManager
     * when deciding if syncthing should run.
     */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Stores the result of the last call to [decideShouldRun].
     */
    private var lastDeterminedShouldRun: Boolean = false

    init {
        preferences = (context.applicationContext as SyncthingApp).preferences
        verboseLog = AppPrefs.getPrefVerboseLog(preferences)
        logV("Created new instance")

        /**
         * Register broadcast receivers.
         */
        // Default network callback; supersedes the deprecated CONNECTIVITY_ACTION broadcast.
        registerNetworkCallback()

        // BatteryReceiver
        val batteryFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ReceiverManager.registerReceiver(context, BatteryReceiver(), batteryFilter)

        // PowerSaveModeChangedReceiver
        ReceiverManager.registerReceiver(
            context,
            PowerSaveModeChangedReceiver(),
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        )

        // SyncStatusObserver to monitor android's "AutoSync" quick toggle.
        syncStatusObserverHandle = ContentResolver.addStatusChangeListener(
            ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS,
            SyncStatusObserver {
                // Posted to the main thread, like the old Handler.post() version.
                monitorScope.launch { updateShouldRunDecision() }
            }
        )

        // In-process run condition events (sync trigger + should-run re-evaluation).
        monitorScope.launch {
            RunConditionEvents.events.collect { event ->
                when (event) {
                    is RunConditionEvents.Event.SyncTriggerFired ->
                        onSyncTriggerFired(event.beginActiveTimeWindow)
                    RunConditionEvents.Event.UpdateShouldRunDecision -> {
                        logV("UpdateShouldRunDecision event received")
                        updateShouldRunDecision()
                    }
                }
            }
        }

        if (!Constants.isRunningOnEmulator()) {
            triggeredSyncSleepIntervalS = (preferences.getString(
                Constants.PREF_SLEEP_INTERVAL_MINUTES, DEFAULT_SLEEP_INTERVAL_MINUTES
            ) ?: DEFAULT_SLEEP_INTERVAL_MINUTES).toInt() * 60
        }
        var lastSyncTimeSinceBootMillisecs = preferences.getLong(Constants.PREF_LAST_RUN_TIME, 0)
        val elapsedRealtime = SystemClock.elapsedRealtime()

        /**
         * after a reboot lastSyncTimeSinceBootMillisecs might be larger than elapsedRealtime,
         * since it is referring to the previous reboot
         * in this case we set preferences.getLong(Constants.PREF_LAST_RUN_TIME, 0)
         * to -triggeredSyncSleepIntervalS, so timeConditionMatch is guaranteed to be true
         */
        if (lastSyncTimeSinceBootMillisecs > elapsedRealtime) {
            preferences.edit()
                .putLong(Constants.PREF_LAST_RUN_TIME, -(triggeredSyncSleepIntervalS * 1000).toLong())
                .apply()
            lastSyncTimeSinceBootMillisecs = 0
        }

        // Initially determine if syncthing should run under current circumstances.
        updateShouldRunDecision()

        // Initially schedule the SyncTrigger job.
        val elapsedSecondsSinceLastSync = (elapsedRealtime - lastSyncTimeSinceBootMillisecs).toInt() / 1000
        Log.d(
            TAG, "JobPrepare: timeConditionMatch=$timeConditionMatch" +
                ", elapsedRealtime=$elapsedRealtime" +
                ", lastSyncTimeSinceBootMillisecs=$lastSyncTimeSinceBootMillisecs" +
                ", elapsedSecondsSinceLastSync=$elapsedSecondsSinceLastSync"
        )
        JobUtils.scheduleSyncTriggerServiceJob(
            context,
            if (timeConditionMatch) {
                triggeredSyncDurationS
            } else {
                /**
                 * if triggeredSyncSleepIntervalS - elapsedSecondsSinceLastSync is < 0,
                 * timeConditionMatch is set to true during updateShouldRunDecision().
                 * Thus the false case cannot be triggered if the delay for
                 * scheduleSyncTriggerServiceJob would be negative.
                 */
                triggeredSyncSleepIntervalS - elapsedSecondsSinceLastSync
            },
            !timeConditionMatch
        )
    }

    fun shutdown() {
        logV("Shutting down")
        // Cancel pending deferred re-evaluations (battery delay, network/observer hand-off).
        monitorScope.cancel()
        JobUtils.cancelAllScheduledJobs(context)
        if (syncStatusObserverHandle != null) {
            ContentResolver.removeStatusChangeListener(syncStatusObserverHandle)
            syncStatusObserverHandle = null
        }

        // NetworkCallback (API 24+)
        unregisterNetworkCallback()

        ReceiverManager.unregisterAllReceivers(context)
    }

    private inner class BatteryReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_POWER_CONNECTED == intent.action ||
                Intent.ACTION_POWER_DISCONNECTED == intent.action
            ) {
                // Wait for the battery state to settle before re-evaluating, without
                // blocking the main thread.
                monitorScope.launch {
                    delay(BATTERY_UPDATE_DELAY_MS)
                    updateShouldRunDecision()
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.e(TAG, "registerNetworkCallback: getSystemService(CONNECTIVITY_SERVICE) unexpectedly returned NULL.")
            return
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onDefaultNetworkMaybeChanged()
            }

            override fun onLost(network: Network) {
                onDefaultNetworkMaybeChanged()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                onDefaultNetworkMaybeChanged()
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                onDefaultNetworkMaybeChanged()
            }
        }
        networkCallback = callback
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: RuntimeException) {
            Log.e(TAG, "registerNetworkCallback: Failed to register network callback", e)
            networkCallback = null
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "unregisterNetworkCallback: Callback was not registered", e)
            }
        }
        networkCallback = null
    }

    /**
     * Called whenever the default network may have changed. Queued onto the main
     * thread to keep listener notifications consistent with the other receivers.
     */
    private fun onDefaultNetworkMaybeChanged() {
        monitorScope.launch { updateShouldRunDecision() }
    }

    private inner class PowerSaveModeChangedReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED == intent.action) {
                updateShouldRunDecision()
            }
        }
    }

    private fun onSyncTriggerFired(extraBeginActiveTimeWindow: Boolean) {
        runAllowedStopScheduled = false
        logV("SyncTrigger: beginActiveTimeWindow=$extraBeginActiveTimeWindow")

        val prefRunOnTimeSchedule = preferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false)
        if (!prefRunOnTimeSchedule) {
            /**
             * The feature is currently disabled.
             * Reschedule the job to see if the user turned on this feature in the meantime.
             */
            timeConditionMatch = false
            JobUtils.cancelAllScheduledJobs(context)
            JobUtils.scheduleSyncTriggerServiceJob(
                context,
                triggeredSyncSleepIntervalS,
                true
            )
            return
        }

        // extraBeginActiveTimeWindow determines whether syncthing should start or stop
        if (extraBeginActiveTimeWindow) {
            // We should immediately start SyncthingNative for TRIGGERED_SYNC_DURATION_SECS.
            timeConditionMatch = true
            JobUtils.cancelAllScheduledJobs(context)
            JobUtils.scheduleSyncTriggerServiceJob(
                context,
                triggeredSyncDurationS,
                false
            )
            runAllowedStopScheduled = true
        } else {
            /**
             * Toggle the "digital input" for this condition as the condition change is
             * triggered by a time schedule.
             */
            timeConditionMatch = false
            /**
             * If Syncthing is running and the last run was more than triggeredSyncSleepIntervalS ago,
             * this stop job might actually start Syncthing (resp. leave it running) because
             * timeConditionMatch is switched to true if last run was more than triggeredSyncSleepIntervalS ago.
             * So in this case we put a new (fake) last run time slightly less than triggeredSyncSleepIntervalS ago.
             * If Syncthing really is stopped (which it should) then the wrong time gets
             * corrected immediately
             */
            val lastRunTimeMillis = preferences.getLong(Constants.PREF_LAST_RUN_TIME, 0)
            if (lastDeterminedShouldRun &&
                SystemClock.elapsedRealtime() - lastRunTimeMillis > triggeredSyncSleepIntervalS * 1000
            ) {
                preferences.edit()
                    .putLong(
                        Constants.PREF_LAST_RUN_TIME,
                        SystemClock.elapsedRealtime() - triggeredSyncSleepIntervalS * 1000 + 60 * 1000
                    )
                    .apply()
            }
        }
        updateShouldRunDecision()

        /**
         * Reschedule the job.
         * If we are within a "SyncthingNative shouldn't run" time frame,
         * let the receiver fire and change to "SyncthingNative should run" after
         * triggeredSyncSleepIntervalS seconds elapsed.
         * If we are within a "SyncthingNative should run" time frame,
         * the change to "SyncthingNative shouldn't run" after
         * TRIGGERED_SYNC_DURATION_SECS seconds elapsed should actually
         * be scheduled inside updateShouldRunDecision(), but this might
         * not always be the case.
         * Thus we schedule an additional change to "SyncthingNative shouldn't run"
         * after TRIGGERED_SYNC_DURATION_SECS seconds elapsed, but without
         * cancelling other jobs. This should only serve as a backup job and
         * will not fire if the job inside updateShouldRunDecision() is
         * scheduled correctly.
         */
        if (!runAllowedStopScheduled && !lastDeterminedShouldRun) {
            JobUtils.cancelAllScheduledJobs(context)
            JobUtils.scheduleSyncTriggerServiceJob(
                context,
                triggeredSyncSleepIntervalS,
                true
            )
        } else {
            JobUtils.scheduleSyncTriggerServiceJob(
                context,
                triggeredSyncDurationS,
                false
            )
        }
    }

    /**
     * Event handler that is fired after preconditions changed.
     * We then need to decide if syncthing should run.
     */
    fun updateShouldRunDecision() {
        if (!Constants.isRunningOnEmulator()) {
            triggeredSyncDurationS = (preferences.getString(
                Constants.PREF_SYNC_DURATION_MINUTES, DEFAULT_SYNC_DURATION_MINUTES
            ) ?: DEFAULT_SYNC_DURATION_MINUTES).toInt() * 60
            triggeredSyncSleepIntervalS = (preferences.getString(
                Constants.PREF_SLEEP_INTERVAL_MINUTES, "60"
            ) ?: "60").toInt() * 60
        }

        val newShouldRun = decideShouldRun()
        if (newShouldRun) {
            /**
             * Trigger:
             *  a) Sync pre-conditions changed
             *      a1) AND SyncthingService.State should remain ACTIVE
             *      a2) AND SyncthingService.State should transition from INIT/DISABLED to ACTIVE
             *  b) Sync pre-conditions did not change
             *      b1) AND SyncthingService.State should remain ACTIVE
             *          because a reevaluation of the run conditions was forced from code.
             * Action:
             *  SyncthingService will evaluate custom per-object run conditions
             *  and pause/unpause objects accordingly.
             */
            onSyncPreconditionChangedListener.onSyncPreconditionChanged(this)
        }

        /**
         * Check if the current conditions changed the result of decideShouldRun()
         * compared to the last determined result.
         */
        if (newShouldRun != lastDeterminedShouldRun) {
            /**
             * Notify SyncthingService in case it has to transition from
             * a) INIT/DISABLED => STARTING => ACTIVE
             * b) ACTIVE => DISABLED
             */
            onShouldRunChangedListener.onShouldRunDecisionChanged(newShouldRun)
            lastDeterminedShouldRun = newShouldRun
            if (newShouldRun &&
                !runAllowedStopScheduled &&
                preferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false) &&
                preferences.getInt(
                    Constants.PREF_BTNSTATE_FORCE_START_STOP,
                    Constants.BTNSTATE_NO_FORCE_START_STOP
                ) == Constants.BTNSTATE_NO_FORCE_START_STOP
            ) {
                JobUtils.cancelAllScheduledJobs(context)
                JobUtils.scheduleSyncTriggerServiceJob(
                    context,
                    triggeredSyncDurationS,
                    false
                )
                runAllowedStopScheduled = true
            }
            preferences.edit()
                .putLong(Constants.PREF_LAST_RUN_TIME, SystemClock.elapsedRealtime())
                .apply()
        }
    }

    fun getRunDecisionExplanation(): String {
        return runDecisionExplanation
    }

    /**
     * Each sync condition has its own evaluator function which
     * determines if the condition is met.
     */
    /**
     * Constants.PREF_RUN_ON_WIFI
     */
    private fun checkConditionSyncOnWifi(prefNameSyncOnWifi: String): SyncConditionResult {
        val prefSyncOnWifi = preferences.getBoolean(prefNameSyncOnWifi, true)
        if (!prefSyncOnWifi) {
            return SyncConditionResult(false, "\n" + res.getString(R.string.reason_wifi_disallowed))
        }

        if (isWifiOrEthernetConnection()) {
            return SyncConditionResult(true, "\n" + res.getString(R.string.reason_on_wifi))
        }

        /**
         * if (prefRunOnWifi && !isWifiOrEthernetConnection()) { return false; }
         * This is intentionally not returning "false" as the flight mode workaround
         * relevant for some phone models needs to be done by the code below.
         * ConnectivityManager.getActiveNetworkInfo() returns "null" on those phones which
         * results in assuming !isWifiOrEthernetConnection even if the phone is connected
         * to wifi during flight mode, see [isWifiOrEthernetConnection].
         */
        return SyncConditionResult(false, "\n" + res.getString(R.string.reason_not_on_wifi))
    }

    private fun checkConditionSyncOnPowerSource(prefNameSyncOnPowerSource: String): SyncConditionResult {
        when (preferences.getString(
            prefNameSyncOnPowerSource, Constants.PowerSource.CHARGER_BATTERY
        ) ?: Constants.PowerSource.CHARGER_BATTERY) {
            Constants.PowerSource.CHARGER -> {
                if (!isCharging()) {
                    return SyncConditionResult(false, res.getString(R.string.reason_not_charging))
                }
            }
            Constants.PowerSource.BATTERY -> {
                if (isCharging()) {
                    return SyncConditionResult(false, res.getString(R.string.reason_not_on_battery_power))
                }
            }
            Constants.PowerSource.CHARGER_BATTERY -> {}
        }
        return SyncConditionResult(true, "")
    }

    /**
     * Constants.PREF_WIFI_SSID_WHITELIST
     */
    private fun checkConditionSyncOnWhitelistedWifi(
        prefNameUseWifiWhitelist: String,
        prefNameSelectedWhitelistSsid: String
    ): SyncConditionResult {
        val wifiWhitelistEnabled = preferences.getBoolean(prefNameUseWifiWhitelist, false)
        val whitelistedWifiSsids: Set<String> =
            preferences.getStringSet(prefNameSelectedWhitelistSsid, hashSetOf()) ?: hashSetOf()
        return try {
            if (wifiWhitelistConditionMet(wifiWhitelistEnabled, whitelistedWifiSsids)) {
                SyncConditionResult(true, "\n" + res.getString(R.string.reason_on_whitelisted_wifi))
            } else {
                SyncConditionResult(false, "\n" + res.getString(R.string.reason_not_on_whitelisted_wifi))
            }
        } catch (e: LocationUnavailableException) {
            SyncConditionResult(false, "\n" + res.getString(R.string.reason_location_unavailable))
        }
    }

    /**
     * Constants.PREF_RUN_ON_METERED_WIFI
     */
    private fun checkConditionSyncOnMeteredWifi(prefNameSyncOnMeteredWifi: String): SyncConditionResult {
        val prefSyncOnMeteredWifi = preferences.getBoolean(prefNameSyncOnMeteredWifi, false)
        if (prefSyncOnMeteredWifi) {
            // Condition is always met as we allow both types of wifi - metered and non-metered.
            return SyncConditionResult(true, "\n" + res.getString(R.string.reason_on_metered_nonmetered_wifi))
        }

        // Check if we are on a non-metered wifi.
        if (!isMeteredNetworkConnection()) {
            return SyncConditionResult(true, "\n" + res.getString(R.string.reason_on_nonmetered_wifi))
        }

        // We disallowed non-metered wifi and are connected to metered wifi.
        return SyncConditionResult(false, "\n" + res.getString(R.string.reason_not_nonmetered_wifi))
    }

    /**
     * Constants.PREF_RUN_ON_MOBILE_DATA
     */
    private fun checkConditionSyncOnMobileData(prefNameSyncOnMobileData: String): SyncConditionResult {
        val prefSyncOnMobileData = preferences.getBoolean(prefNameSyncOnMobileData, false)
        if (!prefSyncOnMobileData) {
            return SyncConditionResult(false, res.getString(R.string.reason_mobile_data_disallowed))
        }

        if (isMobileDataConnection()) {
            return SyncConditionResult(true, res.getString(R.string.reason_on_mobile_data))
        }

        return SyncConditionResult(false, res.getString(R.string.reason_not_on_mobile_data))
    }

    /**
     * Constants.PREF_RUN_ON_ROAMING
     */
    private fun checkConditionSyncOnRoaming(prefNameSyncOnRoaming: String): SyncConditionResult {
        val prefSyncOnRoaming = preferences.getBoolean(prefNameSyncOnRoaming, false)
        if (prefSyncOnRoaming) {
            // Condition is always met as we allow both types of mobile data networks - roaming and non-roaming.
            return SyncConditionResult(true, "\n" + res.getString(R.string.reason_on_roaming_nonroaming_mobile_data))
        }

        // Check if we are on a non-roaming mobile data network.
        if (!isRoamingNetworkConnection()) {
            return SyncConditionResult(true, "\n" + res.getString(R.string.reason_on_nonroaming_mobile_data))
        }

        // We disallowed non-roaming mobile data and are connected to a mobile data network in roaming mode.
        return SyncConditionResult(false, "\n" + res.getString(R.string.reason_not_nonroaming_mobile_data))
    }

    /**
     * Determines if Syncthing should currently run.
     * Updates runDecisionExplanation.
     */
    private fun decideShouldRun(): Boolean {
        runDecisionExplanation = ""

        // Get sync condition preferences.
        val prefBtnStateForceStartStop = preferences.getInt(
            Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_NO_FORCE_START_STOP
        )
        val prefRespectPowerSaving = preferences.getBoolean(Constants.PREF_RESPECT_BATTERY_SAVING, true)
        val prefRespectMasterSync = preferences.getBoolean(Constants.PREF_RESPECT_MASTER_SYNC, false)
        val prefRunInFlightMode = preferences.getBoolean(Constants.PREF_RUN_IN_FLIGHT_MODE, false)
        val prefRunOnTimeSchedule = preferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false)

        // PREF_BTNSTATE_FORCE_START_STOP
        when (prefBtnStateForceStartStop) {
            Constants.BTNSTATE_FORCE_START -> {
                logV("decideShouldRun: PREF_BTNSTATE_FORCE_START")
                runDecisionExplanation = res.getString(R.string.reason_force_start)
                return true
            }
            Constants.BTNSTATE_FORCE_STOP -> {
                logV("decideShouldRun: PREF_BTNSTATE_FORCE_STOP")
                runDecisionExplanation = res.getString(R.string.reason_force_stop)
                return false
            }
        }

        // PREF_RUN_ON_TIME_SCHEDULE
        // set timeConditionMatch to true if the last run was more than triggeredSyncSleepIntervalS ago
        if (SystemClock.elapsedRealtime() - preferences.getLong(Constants.PREF_LAST_RUN_TIME, 0) >
            ((preferences.getString(
                Constants.PREF_SLEEP_INTERVAL_MINUTES, DEFAULT_SLEEP_INTERVAL_MINUTES
            ) ?: DEFAULT_SLEEP_INTERVAL_MINUTES).toInt()) * 60 * 1000
        ) {
            timeConditionMatch = true
        }
        if (prefRunOnTimeSchedule && !timeConditionMatch) {
            // Currently, we aren't within a "SyncthingNative should run" time frame.
            logV("decideShouldRun: PREF_RUN_ON_TIME_SCHEDULE && !timeConditionMatch")
            val minutes = ((SystemClock.elapsedRealtime() -
                preferences.getLong(Constants.PREF_LAST_RUN_TIME, 0)) / (60 * 1000)).toInt()
            val minutesText: String = if (minutes == 0) {
                res.getString(R.string.reason_not_within_time_frame_0_min)
            } else {
                String.format(res.getQuantityString(R.plurals.reason_not_within_time_frame_minutes, minutes), minutes)
            }
            runDecisionExplanation =
                String.format(res.getString(R.string.reason_not_within_time_frame_2), minutesText)
            return false
        }

        // PREF_POWER_SOURCE
        var scr = checkConditionSyncOnPowerSource(Constants.PREF_POWER_SOURCE)
        if (!scr.conditionMet) {
            logV("checkConditionSyncOnPowerSource: " + scr.explanation)
            runDecisionExplanation = scr.explanation
            return false
        }

        // Power saving
        if (prefRespectPowerSaving && isPowerSaving()) {
            logV("decideShouldRun: prefRespectPowerSaving && isPowerSaving")
            runDecisionExplanation = res.getString(R.string.reason_not_while_power_saving)
            return false
        }

        // Android global AutoSync setting.
        if (prefRespectMasterSync && !ContentResolver.getMasterSyncAutomatically()) {
            logV("decideShouldRun: prefRespectMasterSync && !getMasterSyncAutomatically")
            runDecisionExplanation = res.getString(R.string.reason_not_while_auto_sync_data_disabled)
            return false
        }

        // Run on mobile data?
        scr = checkConditionSyncOnMobileData(Constants.PREF_RUN_ON_MOBILE_DATA)
        runDecisionExplanation += scr.explanation
        if (scr.conditionMet) {
            // Mobile data is connected.
            logV("decideShouldRun: checkConditionSyncOnMobileData")

            scr = checkConditionSyncOnRoaming(Constants.PREF_RUN_ON_ROAMING)
            runDecisionExplanation += scr.explanation
            if (scr.conditionMet) {
                // Mobile data connection type is allowed.
                logV("decideShouldRun: checkConditionSyncOnMobileData && checkConditionSyncOnRoaming")
                return true
            }
        }

        // Run on WiFi?
        scr = checkConditionSyncOnWifi(Constants.PREF_RUN_ON_WIFI)
        runDecisionExplanation += scr.explanation
        if (scr.conditionMet) {
            // Wifi is connected.
            logV("decideShouldRun: checkConditionSyncOnWifi")

            scr = checkConditionSyncOnMeteredWifi(Constants.PREF_RUN_ON_METERED_WIFI)
            runDecisionExplanation += scr.explanation
            if (scr.conditionMet) {
                // Wifi type is allowed.
                logV("decideShouldRun: checkConditionSyncOnWifi && checkConditionSyncOnMeteredWifi")

                scr = checkConditionSyncOnWhitelistedWifi(
                    Constants.PREF_USE_WIFI_SSID_WHITELIST,
                    Constants.PREF_WIFI_SSID_WHITELIST
                )
                runDecisionExplanation += scr.explanation
                if (scr.conditionMet) {
                    // Wifi is whitelisted.
                    logV("decideShouldRun: checkConditionSyncOnWifi && checkConditionSyncOnMeteredWifi && checkConditionSyncOnWhitelistedWifi")
                    return true
                }
            }
        }

        // Run in flight mode.
        if (prefRunInFlightMode && isFlightMode()) {
            logV("decideShouldRun: prefRunInFlightMode && isFlightMode")
            runDecisionExplanation += "\n" + res.getString(R.string.reason_on_flight_mode)
            return true
        }

        /**
         * If none of the above run conditions matched, don't run.
         */
        logV("decideShouldRun: return false")
        return false
    }

    /**
     * Returns the desired paused state for the object with the given prefix and id
     * according to its custom sync conditions,
     * or null if custom sync conditions are disabled for it.
     */
    fun getCustomSyncConditionsPause(objectPrefixAndId: String): Boolean? {
        val customSyncConditionsEnabled = preferences.getBoolean(
            Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(objectPrefixAndId), false
        )
        if (!customSyncConditionsEnabled) {
            return null
        }
        return !checkObjectSyncConditions(objectPrefixAndId)
    }

    /**
     * Check if an object's individual sync conditions are met.
     * Precondition: Object must own pref "...CustomSyncConditionsEnabled == true".
     */
    fun checkObjectSyncConditions(objectPrefixAndId: String): Boolean {
        // Sync on specific power source?
        var scr = checkConditionSyncOnPowerSource(
            Constants.DYN_PREF_OBJECT_SYNC_ON_POWER_SOURCE(objectPrefixAndId)
        )
        if (!scr.conditionMet) {
            logV("checkObjectSyncConditions($objectPrefixAndId): checkConditionSyncOnPowerSource")
            return false
        }

        // Sync on mobile data?
        scr = checkConditionSyncOnMobileData(
            Constants.DYN_PREF_OBJECT_SYNC_ON_MOBILE_DATA(objectPrefixAndId)
        )
        if (scr.conditionMet) {
            // Mobile data is connected.
            logV("checkObjectSyncConditions($objectPrefixAndId): checkConditionSyncOnMobileData")
            return true
        }

        // Sync on WiFi?
        scr = checkConditionSyncOnWifi(Constants.DYN_PREF_OBJECT_SYNC_ON_WIFI(objectPrefixAndId))
        if (scr.conditionMet) {
            // Wifi is connected.
            logV("checkObjectSyncConditions($objectPrefixAndId): checkConditionSyncOnWifi")

            scr = checkConditionSyncOnMeteredWifi(
                Constants.DYN_PREF_OBJECT_SYNC_ON_METERED_WIFI(objectPrefixAndId)
            )
            if (scr.conditionMet) {
                // Wifi type is allowed.
                logV("checkObjectSyncConditions($objectPrefixAndId): checkConditionSyncOnWifi && checkConditionSyncOnMeteredWifi")

                scr = checkConditionSyncOnWhitelistedWifi(
                    Constants.DYN_PREF_OBJECT_USE_WIFI_SSID_WHITELIST(objectPrefixAndId),
                    Constants.DYN_PREF_OBJECT_SELECTED_WHITELIST_SSID(objectPrefixAndId)
                )
                if (scr.conditionMet) {
                    // Wifi is whitelisted.
                    logV("checkObjectSyncConditions($objectPrefixAndId): checkConditionSyncOnWifi && checkConditionSyncOnMeteredWifi && checkConditionSyncOnWhitelistedWifi")
                    return true
                }
            }
        }
        return false
    }

    /**
     * Return whether the wifi whitelist run condition is met.
     * Precondition: An active wifi connection has been detected.
     */
    private fun wifiWhitelistConditionMet(
        prefWifiWhitelistEnabled: Boolean,
        whitelistedWifiSsids: Set<String>
    ): Boolean {
        if (!prefWifiWhitelistEnabled) {
            logV("handleWifiWhitelist: !prefWifiWhitelistEnabled")
            return true
        }
        if (isWifiConnectionWhitelisted(whitelistedWifiSsids)) {
            logV("handleWifiWhitelist: isWifiConnectionWhitelisted")
            return true
        }
        return false
    }

    /**
     * Functions for run condition information retrieval.
     */

    /**
     * Returns the capabilities of the current default network, or null if the
     * device is offline (e.g. flight mode). Never called on API 23.
     */
    private fun getActiveNetworkCapabilities(): NetworkCapabilities? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(network)
    }

    /**
     * Returns true if the default network exists, is internet-capable and owns
     * the given transport. Mirrors the legacy NetworkInfo.isConnected() checks.
     */
    private fun hasActiveNetworkTransport(transport: Int): Boolean {
        val nc = getActiveNetworkCapabilities() ?: return false
        return nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            nc.hasTransport(transport)
    }

    private fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) {
            logV("isCharging: Checking battery status intent returned null")
            return false
        }
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        logV("isCharging: Battery status intent extras: ${intent.extras?.toString() ?: "null"}")
        return plugged == BatteryManager.BATTERY_PLUGGED_AC ||
            plugged == BatteryManager.BATTERY_PLUGGED_USB ||
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                plugged == BatteryManager.BATTERY_PLUGGED_DOCK)
    }

    private fun isPowerSaving(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager == null) {
            Log.e(TAG, "getSystemService(POWER_SERVICE) unexpectedly returned NULL.")
            return false
        }
        return powerManager.isPowerSaveMode
    }

    private fun isFlightMode(): Boolean {
        return getActiveNetworkCapabilities() == null
    }

    private fun isMeteredNetworkConnection(): Boolean {
        val nc = getActiveNetworkCapabilities()
        if (nc == null) {
            // In flight mode.
            return false
        }
        if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            // No network connection.
            return false
        }
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            /**
             * We treat Wi-Fi and ETHERNET as "Wi-Fi" connection.
             * Assume ETHERNET connection is un-metered to allow syncing on
             * Android TV or VirtualBox ETHERNET connection.
             */
            return false
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return cm != null && cm.isActiveNetworkMetered
    }

    private fun isMobileDataConnection(): Boolean {
        return hasActiveNetworkTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            hasActiveNetworkTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
    }

    private fun isRoamingNetworkConnection(): Boolean {
        val nc = getActiveNetworkCapabilities()
        if (nc == null || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        ) {
            // Not on a (connected) mobile data network.
            return false
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return tm != null && tm.isNetworkRoaming
    }

    private fun isWifiOrEthernetConnection(): Boolean {
        return hasActiveNetworkTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            hasActiveNetworkTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    @Suppress("DEPRECATION")
    private fun isWifiConnectionWhitelisted(whitelistedSsids: Set<String>): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false
        val wifiInfo = wifiManager.connectionInfo
        if (wifiInfo == null) {
            // May be null, if wifi has been turned off in the meantime.
            Log.d(TAG, "isWifiConnectionWhitelisted: SSID unknown due to wifiInfo == null")
            return false
        }
        val wifiSsid = wifiInfo.ssid
        if (wifiSsid == null || wifiSsid == "<unknown ssid>") {
            throw LocationUnavailableException(
                "isWifiConnectionWhitelisted: Got null SSID. Try to enable android location service."
            )
        }

        return whitelistedSsids.contains(wifiSsid)
    }

    class LocationUnavailableException(message: String) : Exception(message) {

        constructor(message: String, throwable: Throwable) : this(message) {
            initCause(throwable)
        }
    }

    private fun logV(logMessage: String) {
        if (verboseLog) {
            Log.v(TAG, logMessage)
        }
    }
}
