package com.nutomic.syncthingandroid

import android.app.Application
import android.content.SharedPreferences
import android.os.StrictMode
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.service.NotificationHandler
import com.nutomic.syncthingandroid.service.SafBridge

class SyncthingApp : Application() {

    val preferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
    }

    val notificationHandler: NotificationHandler by lazy {
        NotificationHandler(this)
    }

    val safBridge: SafBridge by lazy {
        SafBridge(this)
    }

    override fun onCreate() {
        super.onCreate()

        // Set VM policy to avoid crash when sending folder URI to file manager.
        val vmPolicy = StrictMode.VmPolicy.Builder()
            .detectAll()
            .penaltyLog()
            .build()
        StrictMode.setVmPolicy(vmPolicy)
    }
}
