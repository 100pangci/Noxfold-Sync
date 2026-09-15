package com.nutomic.syncthingandroid.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager

import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.util.Util

class QuickSettingsTileSchedule : TileService(), ServiceConnection, SyncthingService.OnServiceStateChangeListener {

    // Nullable on purpose (mirrors the Java original): TileService can be destroyed without
    // ever receiving onStartListening, so onDestroy must tolerate unset fields. The `!!` sites
    // below are only reachable while the tile is listening (i.e. after onStartListening).
    private var context: Context? = null
    private var preferences: SharedPreferences? = null
    private var syncthingService: SyncthingService? = null
    private var tilesAvailableState = Tile.STATE_INACTIVE

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, pref ->
        if (pref != null && pref == Constants.PREF_BTNSTATE_FORCE_START_STOP) {
            refreshTile()
        }
    }

    override fun onDestroy() {
        logV("onDestroy()")
        syncthingService?.let {
            it.unregisterOnServiceStateChangeListener(this)
            syncthingService = null
        }
        preferences?.unregisterOnSharedPreferenceChangeListener(prefListener)
        try {
            context?.unbindService(this)
        } catch (e: IllegalArgumentException) {
            logV("Service not bound or already unbound")
        } catch (e: IllegalStateException) {
            logV("Service not bound or already unbound")
        }
        super.onDestroy()
    }

    override fun onStartListening() {
        logV("onStartListening()")
        if (qsTile != null) {
            val appContext = application.applicationContext
            context = appContext
            preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
            preferences!!.registerOnSharedPreferenceChangeListener(prefListener)

            try {
                val bindIntent = Intent(appContext, SyncthingService::class.java)
                appContext.bindService(bindIntent, this, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bind to SyncthingService", e)
            }

            refreshTile()
        }
        super.onStartListening()
    }

    override fun onClick() {
        val tile = qsTile ?: return
        if (tile.state == Tile.STATE_UNAVAILABLE) {
            return
        }
        val localBroadcastManager = LocalBroadcastManager.getInstance(context!!)
        val intent = Intent(RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED)
        intent.putExtra(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, true)
        localBroadcastManager.sendBroadcast(intent)
    }

    private fun refreshTile() {
        if (setTileUnavailable()) {
            return
        }
        updateTile(tilesAvailableState)
    }

    private fun setTileUnavailable(): Boolean {
        val tile = qsTile ?: return false

        // look through running services to see whether the app is currently running
        val syncthingRunning = Util.isServiceRunning(context!!, SyncthingService::class.java)

        // disable tile if app is not running, schedule is off, or syncthing is force-started/stopped
        if (syncthingRunning && preferences!!.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false) && preferences!!.getInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_NO_FORCE_START_STOP) == Constants.BTNSTATE_NO_FORCE_START_STOP) {
            return false
        }

        updateTile(Tile.STATE_UNAVAILABLE)
        return true
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        logV("onServiceConnected(ComponentName=$name, IBinder=$service)")
        val syncthingService = (service as SyncthingServiceBinder).service
        this.syncthingService = syncthingService
        syncthingService.registerOnServiceStateChangeListener(this)
    }

    override fun onServiceDisconnected(componentName: ComponentName) {
        syncthingService = null
    }

    private fun updateTile(newState: Int) {
        val tile = qsTile ?: return
        if (newState == tile.state) return

        tile.state = newState

        val res = context!!.resources
        val label = if (newState == Tile.STATE_INACTIVE || newState == Tile.STATE_ACTIVE) {
            res.getString(R.string.qs_schedule_label_minutes, preferences!!.getString(Constants.PREF_SYNC_DURATION_MINUTES, "5")!!.toInt())
        } else {
            res.getString(R.string.qs_schedule_disabled)
        }
        tile.label = label

        tile.updateTile()
    }

    override fun onServiceStateChange(currentState: SyncthingService.State) {
        logV("onServiceStateChange: $currentState")

        tilesAvailableState = Tile.STATE_INACTIVE
        if (currentState == SyncthingService.State.STARTING || currentState == SyncthingService.State.ACTIVE) {
            tilesAvailableState = Tile.STATE_ACTIVE
        }
        refreshTile()
    }

    private fun logV(logMessage: String) {
        if (!ENABLE_VERBOSE_LOG) return
        Log.v(TAG, logMessage)
    }

    companion object {
        private const val TAG = "QuickSettingsTileSchedule"
        private const val ENABLE_VERBOSE_LOG = false
    }
}
