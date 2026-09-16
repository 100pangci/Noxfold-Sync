package com.nutomic.syncthingandroid.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process event bus replacing the deprecated LocalBroadcastManager intents that
 * connected [RunConditionMonitor] with the quick-settings tiles, the sync trigger job,
 * the app config receiver and the compose UI.
 *
 * Delivery semantics match LocalBroadcastManager: only currently collecting monitors
 * receive an event; emissions without a collector are dropped (`replay = 0`).
 */
object RunConditionEvents {

    /**
     * PersistableBundle extra of [com.nutomic.syncthingandroid.util.JobUtils.scheduleSyncTriggerServiceJob].
     * The value is an int because JobScheduler only supports int/bool extras. The key is kept
     * from the previous implementation so jobs scheduled before an app upgrade keep working.
     */
    const val EXTRA_BEGIN_ACTIVE_TIME_WINDOW = ".service.RunConditionMonitor.BEGIN_ACTIVE_TIME_WINDOW"

    sealed interface Event {
        /**
         * A sync time window was requested by the JobScheduler, a quick-settings tile or the UI.
         */
        data class SyncTriggerFired(val beginActiveTimeWindow: Boolean) : Event

        /**
         * The force start/stop preference or another run condition changed; the monitor must
         * re-evaluate whether syncthing should run.
         */
        data object UpdateShouldRunDecision : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun fireSyncTrigger(beginActiveTimeWindow: Boolean) {
        _events.tryEmit(Event.SyncTriggerFired(beginActiveTimeWindow))
    }

    fun requestUpdateShouldRunDecision() {
        _events.tryEmit(Event.UpdateShouldRunDecision)
    }
}
