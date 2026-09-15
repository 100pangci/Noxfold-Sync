package com.nutomic.syncthingandroid.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the LocalBroadcastManager-compatible delivery semantics of [RunConditionEvents]:
 * events without a collector are dropped, events with a collector arrive in order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunConditionEventsTest {

    @Test
    fun eventWithoutCollector_isDropped() = runTest {
        RunConditionEvents.requestUpdateShouldRunDecision()

        val received = mutableListOf<RunConditionEvents.Event>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            RunConditionEvents.events.collect { received.add(it) }
        }
        advanceUntilIdle()

        assertTrue(received.isEmpty())
        job.cancel()
    }

    @Test
    fun events_reachActiveCollectorInOrder() = runTest {
        val received = mutableListOf<RunConditionEvents.Event>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            RunConditionEvents.events.collect { received.add(it) }
        }
        runCurrent()

        RunConditionEvents.fireSyncTrigger(beginActiveTimeWindow = true)
        RunConditionEvents.fireSyncTrigger(beginActiveTimeWindow = false)
        RunConditionEvents.requestUpdateShouldRunDecision()
        runCurrent()

        assertEquals(
            listOf(
                RunConditionEvents.Event.SyncTriggerFired(true),
                RunConditionEvents.Event.SyncTriggerFired(false),
                RunConditionEvents.Event.UpdateShouldRunDecision,
            ),
            received,
        )
        job.cancel()
    }
}
