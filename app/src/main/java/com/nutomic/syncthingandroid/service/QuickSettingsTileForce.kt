package com.nutomic.syncthingandroid.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

import androidx.preference.PreferenceManager

import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.util.Util

class QuickSettingsTileForce : TileService() {

    override fun onStartListening() {
        val tile = qsTile
        if (tile != null) {
            // Search through running services to see whether the app is currently running.
            val syncthingRunning = Util.isServiceRunning(application, SyncthingService::class.java)
            // Disable tile if app is not running.
            if (!syncthingRunning) {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.updateTile()
                return
            }

            // Update tile to reflect the forced state.
            val preferences = PreferenceManager.getDefaultSharedPreferences(application)
            updateTileState(
                tile,
                preferences.getInt(
                    Constants.PREF_BTNSTATE_FORCE_START_STOP,
                    Constants.BTNSTATE_NO_FORCE_START_STOP
                )
            )
        }
        super.onStartListening()
    }

    override fun onClick() {
        val tile = qsTile ?: return
        val preferences = PreferenceManager.getDefaultSharedPreferences(application)
        val newState = when (
            preferences.getInt(
                Constants.PREF_BTNSTATE_FORCE_START_STOP,
                Constants.BTNSTATE_NO_FORCE_START_STOP
            )
        ) {
            Constants.BTNSTATE_FORCE_START -> Constants.BTNSTATE_FORCE_STOP
            Constants.BTNSTATE_NO_FORCE_START_STOP -> Constants.BTNSTATE_FORCE_START
            else -> Constants.BTNSTATE_NO_FORCE_START_STOP
        }
        preferences.edit()
            .putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, newState)
            .apply()

        RunConditionEvents.requestUpdateShouldRunDecision()

        updateTileState(tile, newState)
        tile.updateTile()
    }

    private fun updateTileState(tile: Tile, force: Int) {
        when (force) {
            Constants.BTNSTATE_FORCE_START -> {
                tile.label = getString(R.string.qs_forced_to_run)
                tile.state = Tile.STATE_ACTIVE
                tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_forced_to_run)
            }
            Constants.BTNSTATE_FORCE_STOP -> {
                tile.label = getString(R.string.qs_forced_to_stop)
                tile.state = Tile.STATE_ACTIVE
                tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_forced_to_stop)
            }
            else -> {
                tile.label = getString(R.string.qs_following_run_conditions)
                tile.state = Tile.STATE_INACTIVE
                tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_force)
            }
        }
        tile.updateTile()
    }
}
