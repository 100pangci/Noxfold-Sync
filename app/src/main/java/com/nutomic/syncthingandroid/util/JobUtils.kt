package com.nutomic.syncthingandroid.util

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log

import com.nutomic.syncthingandroid.service.RunConditionEvents.EXTRA_BEGIN_ACTIVE_TIME_WINDOW
import com.nutomic.syncthingandroid.service.SyncTriggerJobService

object JobUtils {

    private const val TAG = "JobUtils"

    fun scheduleSyncTriggerServiceJob(context: Context, delayInSeconds: Int, startRun: Boolean) {
        val delay = if (delayInSeconds < 0) 0 else delayInSeconds

        val serviceComponent = ComponentName(context, SyncTriggerJobService::class.java)
        val builder = JobInfo.Builder(0, serviceComponent)

        // Wait at least "delayInSeconds".
        builder.setMinimumLatency((delay * 1000).toLong())

        // Syncthing should start after the delay if startRun is true, and otherwise stop
        // The PersistableBundle is used to forward this information to the SyncTriggerJobService
        if (startRun) {
            val extraBundle = PersistableBundle()
            extraBundle.putInt(EXTRA_BEGIN_ACTIVE_TIME_WINDOW, 1) // must be int, because boolean needs API 22
            builder.setExtras(extraBundle)
        }

        // Schedule the start of "SyncTriggerJobService" in "X" seconds.
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        jobScheduler.schedule(builder.build())
        Log.i(TAG, "Scheduled SyncTriggerJobService to run in " +
                delay +
                " seconds.")
    }

    fun cancelAllScheduledJobs(context: Context) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        jobScheduler.cancelAll()
    }
}
