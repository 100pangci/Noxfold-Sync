package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

import androidx.preference.PreferenceManager

import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.util.Util

class QuickSettingsTileForce : TileService() {

    // Nullable on purpose (mirrors the Java original): TileService can be destroyed without
    // ever receiving onStartListening, so lifecycle teardown must tolerate unset fields.
    // The `!!` sites are only reachable while the tile is listening (i.e. after onStartListening).
    private var context: Context? = null
    private var preferences: SharedPreferences? = null
    private var res: Resources? = null

    override fun onStartListening() {
        val tile = qsTile
        if (tile != null) {
            val appContext = application.applicationContext
            context = appContext
            res = appContext.resources
            preferences = PreferenceManager.getDefaultSharedPreferences(appContext)

            // search through running services to see whether the app is currently running
            val syncthingRunning = Util.isServiceRunning(appContext, SyncthingService::class.java)
            // disable tile if app is not running
            if (!syncthingRunning) {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.updateTile()
                return
            }

            // update tile to reflect forced-state
            updateTileState(tile, preferences!!.getInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_NO_FORCE_START_STOP))
        }
        super.onStartListening()
    }

    override fun onClick() {
        val tile = qsTile ?: return
        val newState = when (preferences!!.getInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_NO_FORCE_START_STOP)) {
            Constants.BTNSTATE_FORCE_START -> Constants.BTNSTATE_FORCE_STOP
            Constants.BTNSTATE_NO_FORCE_START_STOP -> Constants.BTNSTATE_FORCE_START
            else -> Constants.BTNSTATE_NO_FORCE_START_STOP
        }
        val editor = preferences!!.edit()
        editor.putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, newState)
        editor.apply()

        RunConditionEvents.requestUpdateShouldRunDecision()

        updateTileState(tile, newState)
        tile.updateTile()
    }

    private fun updateTileState(tile: Tile, force: Int) {
        when (force) {
            Constants.BTNSTATE_FORCE_START -> {
                tile.label = res!!.getString(R.string.qs_forced_to_run)
                tile.state = Tile.STATE_ACTIVE
                tile.icon = Icon.createWithResource(context!!, R.drawable.ic_qs_forced_to_run)
            }
            Constants.BTNSTATE_FORCE_STOP -> {
                tile.label = res!!.getString(R.string.qs_forced_to_stop)
                tile.state = Tile.STATE_ACTIVE
                tile.icon = Icon.createWithResource(context!!, R.drawable.ic_qs_forced_to_stop)
            }
            else -> {
                tile.label = res!!.getString(R.string.qs_following_run_conditions)
                tile.state = Tile.STATE_INACTIVE
                tile.icon = Icon.createWithResource(context!!, R.drawable.ic_qs_force)
            }
        }
        tile.updateTile()
    }
}
