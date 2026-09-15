package com.nutomic.syncthingandroid.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager

import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RunConditionEvents
import com.nutomic.syncthingandroid.service.SyncthingService

/**
 * Broadcast-receiver to control and configure Syncthing remotely.
 */
class AppConfigReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val intentAction = intent.action
            ?.replaceFirst(Regex.fromLiteral(context.packageName), "")
            ?: return
        if (!getPrefBroadcastServiceControl(context)) {
            when (intentAction) {
                ACTION_FOLLOW, ACTION_START, ACTION_STOP ->
                    Log.w(TAG, "Ignored intent action \"$intentAction\"" +
                            ". Enable Settings > Experimental > Service Control by Broadcast if you like to control syncthing remotely.")
            }
            return
        }

        when (intentAction) {
            ACTION_FOLLOW -> {
                Log.d(TAG, "followRunConditions by intent")
                setPrefBtnStateForceStartStopAndNotify(context, Constants.BTNSTATE_NO_FORCE_START_STOP)
                BootReceiver.startServiceCompat(context)
            }
            ACTION_START -> {
                Log.d(TAG, "forceStart by intent")
                setPrefBtnStateForceStartStopAndNotify(context, Constants.BTNSTATE_FORCE_START)
                BootReceiver.startServiceCompat(context)
            }
            ACTION_STOP -> {
                Log.d(TAG, "forceStop by intent")
                setPrefBtnStateForceStartStopAndNotify(context, Constants.BTNSTATE_FORCE_STOP)
            }
            else -> Log.w(TAG, "invalid intent action: $intentAction")
        }
    }

    private fun getPrefBroadcastServiceControl(context: Context): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return sp.getBoolean(Constants.PREF_BROADCAST_SERVICE_CONTROL, false)
    }

    private fun setPrefBtnStateForceStartStopAndNotify(context: Context, newState: Int) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = sharedPreferences.edit()
        editor.putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, newState)
        editor.apply()

        // Notify RunConditionMonitor that the button's state changed.
        RunConditionEvents.requestUpdateShouldRunDecision()
    }

    companion object {
        private const val TAG = "AppConfigReceiver"

        /**
         * Let Syncthing-Service follow run conditions
         */
        private const val ACTION_FOLLOW = ".action.FOLLOW"

        /**
         * Start the Syncthing-Service
         */
        private const val ACTION_START = ".action.START"

        /**
         * Stop the Syncthing-Service
         * If startServiceOnBoot is enabled the service must not be stopped. Instead a
         * notification is presented to the user.
         */
        private const val ACTION_STOP = ".action.STOP"
    }
}
