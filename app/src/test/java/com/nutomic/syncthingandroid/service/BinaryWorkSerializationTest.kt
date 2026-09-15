package com.nutomic.syncthingandroid.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the serialization contract of [runBinaryWorkSerialized], the shared gate used by
 * both the startup stale-binary cleanup and the shutdown kill/join. It replaces the FIFO
 * guarantee that the previous single-thread `Executors.newSingleThreadExecutor()` provided.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BinaryWorkSerializationTest {

    @Test
    fun concurrentRequests_neverOverlap_andKeepSubmissionOrder() = runTest {
        val mutex = Mutex()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = mutableListOf<String>()
        var inCriticalSection = false
        var overlaps = 0

        val jobs = (1..3).map { i ->
            launch(dispatcher) {
                runBinaryWorkSerialized(mutex, dispatcher) {
                    if (inCriticalSection) {
                        overlaps++
                    }
                    inCriticalSection = true
                    events.add("start-$i")
                    delay(10)
                    events.add("end-$i")
                    inCriticalSection = false
                }
            }
        }
        jobs.forEach { it.join() }

        assertEquals("binary lifecycle operations must never overlap", 0, overlaps)
        assertEquals(
            listOf("start-1", "end-1", "start-2", "end-2", "start-3", "end-3"),
            events,
        )
    }

    @Test
    fun requestSubmittedWhileOneIsRunning_waitsForCompletion() = runTest {
        val mutex = Mutex()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = mutableListOf<String>()

        val first = launch(dispatcher) {
            runBinaryWorkSerialized(mutex, dispatcher) {
                events.add("first-start")
                delay(100)
                events.add("first-end")
            }
        }
        // Let the first request enter its critical section and suspend in delay().
        testScheduler.runCurrent()
        assertEquals(listOf("first-start"), events)

        val second = launch(dispatcher) {
            runBinaryWorkSerialized(mutex, dispatcher) {
                events.add("second-start")
            }
        }
        // The second request must not start while the first holds the gate.
        testScheduler.runCurrent()
        assertEquals(listOf("first-start"), events)

        testScheduler.advanceUntilIdle()
        first.join()
        second.join()
        assertEquals(
            listOf("first-start", "first-end", "second-start"),
            events,
        )
    }
}
