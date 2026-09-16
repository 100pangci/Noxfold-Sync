package com.nutomic.syncthingandroid.service

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * SyncTriggerJobService to be scheduled by the JobScheduler.
 * See [com.nutomic.syncthingandroid.util.JobUtils.scheduleSyncTriggerServiceJob] for more details.
 */
class SyncTriggerJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        // If Syncthing should start, forward this information to the RunConditionMonitor
        // event bus, otherwise Syncthing will stop.
        val beginActiveTimeWindow =
            params.extras?.getInt(RunConditionEvents.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, 0) == 1
        RunConditionEvents.fireSyncTrigger(beginActiveTimeWindow)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        return true
    }
}
