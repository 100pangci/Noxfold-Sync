package com.nutomic.syncthingandroid.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Connects to [SyncthingService] and provides access to it.
 */
abstract class SyncthingActivity : ThemedAppCompatActivity(), ServiceConnection {

    /**
     * Notified whenever the service connection is established or dropped, so
     * UI code can observe the service as state instead of polling getService().
     */
    fun interface OnServiceConnectionChangedListener {
        fun onServiceConnectionChanged(service: SyncthingService?)
    }

    var service: SyncthingService? = null
        private set

    private val serviceConnectionListeners = CopyOnWriteArrayList<OnServiceConnectionChangedListener>()

    fun addOnServiceConnectionChangedListener(listener: OnServiceConnectionChangedListener) {
        serviceConnectionListeners.add(listener)
    }

    fun removeOnServiceConnectionChangedListener(listener: OnServiceConnectionChangedListener) {
        serviceConnectionListeners.remove(listener)
    }

    private fun notifyServiceConnectionChanged() {
        for (listener in serviceConnectionListeners) {
            listener.onServiceConnectionChanged(service)
        }
    }

    override fun onPause() {
        unbindService(this)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Ensure the service is started, not only bound: deep links (e.g. the
        // "device/folder shared" notification accept actions) can launch
        // activities directly without MainActivity ever running. A bound-only
        // service never receives onStartCommand, so RunConditionMonitor would
        // never be created, Syncthing would never be launched by this process
        // and RestApi would stay null - making every config save silently fall
        // back to writing config.xml, which a running Syncthing instance never
        // re-reads.
        val serviceIntent = Intent(this, SyncthingService::class.java)
        startForegroundService(serviceIntent)
        bindService(Intent(this, SyncthingService::class.java), this, Context.BIND_AUTO_CREATE)
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        service = (iBinder as SyncthingServiceBinder).service
        notifyServiceConnectionChanged()
    }

    override fun onServiceDisconnected(componentName: ComponentName) {
        service = null
        notifyServiceConnectionChanged()
    }

    /**
     * Returns RestApi instance, or null if SyncthingService is not yet connected.
     */
    val api: RestApi?
        get() = service?.api
}
