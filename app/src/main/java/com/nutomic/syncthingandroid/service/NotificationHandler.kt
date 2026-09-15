package com.nutomic.syncthingandroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log

import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat

import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.DeviceActivity
import com.nutomic.syncthingandroid.activities.FolderActivity
import com.nutomic.syncthingandroid.activities.LogActivity
import com.nutomic.syncthingandroid.activities.MainActivity
import com.nutomic.syncthingandroid.activities.OnboardingActivity
import com.nutomic.syncthingandroid.service.SyncthingService.State

class NotificationHandler(context: Context) {

    private val context: Context = context
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val persistentChannel: NotificationChannel
    private val persistentChannelWaiting: NotificationChannel
    private val infoChannel: NotificationChannel

    private var lastNotificationText: String? = null
    private var lastStartForegroundService = false
    private var appShutdownInProgress = false

    init {
        persistentChannel = NotificationChannel(
            CHANNEL_PERSISTENT, context.getString(R.string.notifications_persistent_channel),
            NotificationManager.IMPORTANCE_MIN
        ).also {
            it.enableLights(false)
            it.enableVibration(false)
            it.setSound(null, null)
            it.setShowBadge(false)
            it.lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            notificationManager.createNotificationChannel(it)
        }

        persistentChannelWaiting = NotificationChannel(
            CHANNEL_PERSISTENT_WAITING, context.getString(R.string.notification_persistent_waiting_channel),
            NotificationManager.IMPORTANCE_MIN
        ).also {
            it.enableLights(false)
            it.enableVibration(false)
            it.setSound(null, null)
            it.setShowBadge(false)
            it.lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            notificationManager.createNotificationChannel(it)
        }

        infoChannel = NotificationChannel(
            CHANNEL_INFO, context.getString(R.string.notifications_other_channel),
            NotificationManager.IMPORTANCE_LOW
        ).also {
            it.enableVibration(false)
            it.setSound(null, null)
            it.setShowBadge(true)
            notificationManager.createNotificationChannel(it)
        }
    }

    private fun getNotificationBuilder(channel: NotificationChannel): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, channel.id)
    }

    /**
     * Shows, updates or hides the notification.
     */
    fun updatePersistentNotification(service: SyncthingService) {
        // Persist previous notification details.
        updatePersistentNotification(service, true, 0, 0)
    }

    fun updatePersistentNotification(service: SyncthingService,
                                     persistNotificationDetails: Boolean,
                                     onlineDeviceCount: Int,
                                     totalSyncCompletion: Int) {
        val currentServiceState = service.currentState
        val syncthingRunning = currentServiceState == SyncthingService.State.ACTIVE ||
                currentServiceState == SyncthingService.State.STARTING
        var startForegroundService = false
        if (!appShutdownInProgress) {
            /**
             * Always use startForeground.
             * This makes sure the app is not killed, and we don't miss run condition events.
             * On Android 8+, this behaviour is mandatory to receive broadcasts.
             * https://stackoverflow.com/a/44505719/1837158
             * Foreground priority requires a notification so this ensures that we either have a
             * "default" or "low_priority" notification, but not "none".
             */
            startForegroundService = true
        }

        // Check if we have to stopForeground.
        if (!startForegroundService && startForegroundService != lastStartForegroundService) {
            Log.v(TAG, "Stopping foreground service")
            service.stopForeground(false)
        }

        // Prepare notification builder.
        val text: String = when (currentServiceState) {
            State.ERROR, State.INIT -> context.getString(R.string.syncthing_terminated)
            State.DISABLED -> context.getString(R.string.syncthing_disabled)
            State.STARTING -> context.getString(R.string.syncthing_starting)
            State.ACTIVE -> {
                if (lastNotificationText == null || !persistNotificationDetails) {
                    lastNotificationText = if (totalSyncCompletion == -1) {
                        context.getString(
                            R.string.syncthing_active_details,
                            context.getString(R.string.no_remote_devices_connected)
                        )
                    } else if (totalSyncCompletion == 100) {
                        context.getString(
                            R.string.syncthing_active_details,
                            context.resources.getQuantityString(
                                R.plurals.device_online_up_to_date,
                                onlineDeviceCount,
                                onlineDeviceCount
                            )
                        )
                    } else {
                        context.resources.getQuantityString(
                            R.plurals.syncthing_active_syncing_device_online,
                            onlineDeviceCount,
                            totalSyncCompletion,
                            onlineDeviceCount
                        )
                    }
                }
                lastNotificationText!!
            }
        }

        /**
         * Reason for two separate IDs: if one of the notification channels is hidden then
         * the startForeground() below won't update the notification but use the old one.
         */
        val idToShow = if (syncthingRunning) ID_PERSISTENT else ID_PERSISTENT_WAITING
        val idToCancel = if (syncthingRunning) ID_PERSISTENT_WAITING else ID_PERSISTENT

        val openAppIntent = Intent(context, MainActivity::class.java)

        val exitIntent = Intent(context, MainActivity::class.java)
        exitIntent.action = MainActivity.ACTION_EXIT
        val exitPendingIntent = PendingIntent.getActivity(
            context,
            0,
            exitIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val channel = if (syncthingRunning) persistentChannel else persistentChannelWaiting
        val builder = getNotificationBuilder(channel)
            .setContentTitle(text)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(PendingIntent.getActivity(context, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(R.drawable.baseline_close_24, context.getString(R.string.exit), exitPendingIntent)
        if (!appShutdownInProgress) {
            if (startForegroundService) {
                Log.v(TAG, "Starting foreground service or updating notification")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    service.startForeground(idToShow, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    service.startForeground(idToShow, builder.build())
                }
            } else {
                Log.v(TAG, "Updating notification")
                notificationManager.notify(idToShow, builder.build())
            }
        } else {
            notificationManager.cancel(idToShow)
        }
        notificationManager.cancel(idToCancel)

        // Remember last notification visibility.
        lastStartForegroundService = startForegroundService
    }

    /**
     * Called by [SyncthingService.onStart] [SyncthingService.onDestroy]
     * to indicate app startup and shutdown.
     */
    fun setAppShutdownInProgress(newValue: Boolean) {
        appShutdownInProgress = newValue
    }

    fun showCrashedNotification(@StringRes title: Int, extraInfo: String) {
        val intent = Intent(context, LogActivity::class.java)
        val n = getNotificationBuilder(infoChannel)
            .setContentTitle(context.getString(title, extraInfo))
            .setContentText(context.getString(R.string.notification_crash_text, extraInfo))
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentIntent(PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        notificationManager.notify(ID_CRASH, n)
    }

    /**
     * Calculate a deterministic ID between 1000 and 2000 to avoid duplicate
     * notification ids for different device, folder consent popups triggered
     * by [com.nutomic.syncthingandroid.service.EventPoller].
     */
    fun getNotificationIdFromText(text: String): Int {
        return CONSENT_NOTIFICATION_ID_BASE + Math.floorMod(text.hashCode(), CONSENT_NOTIFICATION_ID_RANGE)
    }

    /**
     * Closes a notification. Required after the user hit an action button.
     */
    fun cancelConsentNotification(notificationId: Int) {
        if (notificationId == 0) {
            return
        }
        Log.v(TAG, "Cancelling notification with id $notificationId")
        notificationManager.cancel(notificationId)
    }

    /**
     * Used by [com.nutomic.syncthingandroid.service.EventPoller]
     */
    fun showConsentNotification(notificationId: Int,
                                text: String,
                                piAccept: PendingIntent,
                                piIgnore: PendingIntent) {
        /**
         * As we know the id for a specific notification text,
         * we'll dismiss this notification as it may be outdated.
         * This is also valid if the notification does not exist.
         */
        notificationManager.cancel(notificationId)
        val n = getNotificationBuilder(infoChannel)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(text))
            .setContentIntent(piAccept)
            .addAction(R.drawable.ic_stat_notify, context.getString(R.string.accept), piAccept)
            .addAction(R.drawable.ic_stat_notify, context.getString(R.string.ignore), piIgnore)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationId, n)
    }

    fun showStoragePermissionRevokedNotification() {
        val intent = Intent(context, OnboardingActivity::class.java)
        val n = getNotificationBuilder(infoChannel)
            .setContentTitle(context.getString(R.string.syncthing_terminated))
            .setContentText(context.getString(R.string.toast_write_storage_permission_required))
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentIntent(PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        notificationManager.notify(ID_MISSING_PERM, n)
    }

    fun cancelRestartNotification() {
        notificationManager.cancel(ID_RESTART)
    }

    fun showDeviceConnectNotification(deviceId: String?,
                                      deviceName: String?,
                                      deviceAddress: String?) {
        if (deviceId == null) {
            Log.e(TAG, "showDeviceConnectNotification: deviceId == null")
            return
        }
        val title = context.getString(R.string.device_rejected,
            if (deviceName.isNullOrEmpty()) deviceId.substring(0, 7) else deviceName)
        val notificationId = getNotificationIdFromText(title)

        // Prepare "accept" action.
        val intentAccept = Intent(context, DeviceActivity::class.java)
            .putExtra(DeviceActivity.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(DeviceActivity.EXTRA_IS_CREATE, true)
            .putExtra(DeviceActivity.EXTRA_DEVICE_ID, deviceId)
            .putExtra(DeviceActivity.EXTRA_DEVICE_NAME, deviceName)
        val piAccept = PendingIntent.getActivity(context, notificationId,
            intentAccept, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Prepare "ignore" action.
        val intentIgnore = Intent(context, SyncthingService::class.java)
            .putExtra(SyncthingService.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(SyncthingService.EXTRA_DEVICE_ID, deviceId)
            .putExtra(SyncthingService.EXTRA_DEVICE_NAME, deviceName)
            .putExtra(SyncthingService.EXTRA_DEVICE_ADDRESS, deviceAddress)
        intentIgnore.action = SyncthingService.ACTION_IGNORE_DEVICE
        val piIgnore = PendingIntent.getService(context, 0,
            intentIgnore, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Show notification.
        showConsentNotification(notificationId, title, piAccept, piIgnore)
    }

    fun showFolderShareNotification(deviceId: String?,
                                    deviceName: String?,
                                    folderId: String?,
                                    folderLabel: String?,
                                    receiveEncrypted: Boolean?,
                                    isNewFolder: Boolean?) {
        if (deviceId == null) {
            Log.e(TAG, "showFolderShareNotification: deviceId == null")
            return
        }
        if (folderId == null) {
            Log.e(TAG, "showFolderShareNotification: folderId == null")
            return
        }
        val title = context.getString(R.string.folder_rejected, deviceName,
            if (folderLabel.isNullOrEmpty()) folderId else "$folderLabel ($folderId)")
        val notificationId = getNotificationIdFromText(title)

        // Prepare "accept" action.
        val intentAccept = Intent(context, FolderActivity::class.java)
            .putExtra(FolderActivity.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(FolderActivity.EXTRA_IS_CREATE, isNewFolder ?: false)
            .putExtra(FolderActivity.EXTRA_DEVICE_ID, deviceId)
            .putExtra(FolderActivity.EXTRA_FOLDER_ID, folderId)
            .putExtra(FolderActivity.EXTRA_FOLDER_LABEL, folderLabel)
            .putExtra(FolderActivity.EXTRA_RECEIVE_ENCRYPTED, receiveEncrypted ?: false)
        val piAccept = PendingIntent.getActivity(context, notificationId,
            intentAccept, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Prepare "ignore" action.
        val intentIgnore = Intent(context, SyncthingService::class.java)
            .putExtra(SyncthingService.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(SyncthingService.EXTRA_DEVICE_ID, deviceId)
            .putExtra(SyncthingService.EXTRA_FOLDER_ID, folderId)
            .putExtra(SyncthingService.EXTRA_FOLDER_LABEL, folderLabel)
        intentIgnore.action = SyncthingService.ACTION_IGNORE_FOLDER
        val piIgnore = PendingIntent.getService(context, 0,
            intentIgnore, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Show notification.
        showConsentNotification(notificationId, title, piAccept, piIgnore)
    }

    companion object {
        private const val TAG = "NotificationHandler"
        private const val ID_PERSISTENT = 1
        private const val ID_PERSISTENT_WAITING = 4
        private const val ID_RESTART = 2
        private const val ID_CRASH = 9
        private const val ID_MISSING_PERM = 10
        private const val CHANNEL_PERSISTENT = "01_syncthing_persistent"
        private const val CHANNEL_INFO = "02_syncthing_notifications"
        private const val CHANNEL_PERSISTENT_WAITING = "03_syncthing_persistent_waiting"

        // Consent notification ids are deterministic within this range to avoid
        // duplicates for different device, folder consent popups triggered by [EventPoller].
        private const val CONSENT_NOTIFICATION_ID_BASE = 1000
        private const val CONSENT_NOTIFICATION_ID_RANGE = 1000
    }
}
