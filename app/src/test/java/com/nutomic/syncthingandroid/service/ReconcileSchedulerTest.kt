package com.nutomic.syncthingandroid.service

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.FileObserver

import java.io.File

import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ReconcileSchedulerTest {

    @Test
    fun startupAndBurstEvents_areSerializedAndCoalesced() = runTest {
        val calls = ArrayList<Boolean>()
        var running = 0
        var maxRunning = 0
        val scheduler = ReconcileScheduler(
            scope = this,
            debounceMs = 100,
            fullIntervalMs = 10_000,
            reconcile = { full ->
                running++
                maxRunning = maxOf(maxRunning, running)
                calls += full
                delay(50)
                running--
            },
            onFailure = { throw AssertionError(it) },
        )

        scheduler.start()
        runCurrent()
        assertEquals(listOf(true), calls)

        scheduler.requestReconcile()
        scheduler.requestReconcile()
        scheduler.requestReconcile()
        advanceTimeBy(99)
        runCurrent()
        assertEquals(1, calls.size)
        advanceTimeBy(1)
        runCurrent()
        advanceTimeBy(50)
        runCurrent()

        assertEquals(listOf(true, false), calls)
        assertEquals("one scheduler worker must own reconcile execution", 1, maxRunning)
        scheduler.stop()
    }

    @Test
    fun periodicFallback_requestsFullReconcile() = runTest {
        val calls = ArrayList<Boolean>()
        val scheduler = ReconcileScheduler(
            scope = this,
            debounceMs = 10,
            fullIntervalMs = 1_000,
            reconcile = { calls += it },
            onFailure = { throw AssertionError(it) },
        )
        scheduler.start()
        runCurrent()
        assertEquals(listOf(true), calls)

        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, calls.size)
        advanceTimeBy(1)
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        assertEquals(listOf(true, true), calls)
        scheduler.stop()
    }

    @Test
    fun stopDuringReconcile_doesNotStartAnotherPass() = runTest {
        var calls = 0
        val scheduler = ReconcileScheduler(
            scope = this,
            debounceMs = 10,
            fullIntervalMs = 1_000,
            reconcile = {
                calls++
                delay(500)
            },
            onFailure = { throw AssertionError(it) },
        )
        scheduler.start()
        runCurrent()
        scheduler.requestReconcile()
        scheduler.stop()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, calls)
    }

    @Test
    fun providerObserver_turnsCallbackIntoHint() {
        val resolver = mock(ContentResolver::class.java)
        val uri = Uri.parse("content://provider/tree/root")
        var registered: ContentObserver? = null
        var changes = 0
        doAnswer { invocation ->
            registered = invocation.getArgument(2)
            null
        }.`when`(resolver).registerContentObserver(
            org.mockito.ArgumentMatchers.eq(uri),
            anyBoolean(),
            org.mockito.kotlin.any(),
        )

        val observer = ProviderObserver(resolver, uri) { changes++ }
        observer.start()
        val capturedObserver = registered ?: error("provider observer was not registered")
        capturedObserver.onChange(false, uri)
        capturedObserver.onChange(false)

        assertEquals(2, changes)
        observer.stop()
        verify(resolver).unregisterContentObserver(capturedObserver)
    }

    @Test
    fun forwardedObserver_refreshesDirectoryRegistryAndEmitsHint() {
        val root = File.createTempFile("saf-bridge", "observer")
        assertTrue(root.delete())
        assertTrue(root.mkdirs())
        val factory = FakeDirectoryWatchFactory()
        var changes = 0
        val observer = ForwardedTreeObserver(root, { _ -> changes++ }, factory)

        observer.start()
        val rootWatch = factory.watchFor(root) ?: error("root watch was not registered")
        rootWatch.emit(FileObserver.CREATE, "new.txt")
        assertEquals(1, changes)

        val child = File(root, "child")
        assertTrue(child.mkdirs())
        rootWatch.emit(FileObserver.CREATE, "child")
        assertTrue(factory.watchFor(child) != null)
        assertEquals(2, changes)

        observer.stop()
        root.deleteRecursively()
    }

    private class FakeDirectoryWatchFactory : DirectoryWatchFactory {
        private val watches = LinkedHashMap<String, FakeDirectoryWatch>()

        override fun create(
            directory: File,
            onEvent: (event: Int, path: String?) -> Unit,
        ): DirectoryWatch {
            return FakeDirectoryWatch(directory, onEvent).also {
                watches[directory.absolutePath] = it
            }
        }

        fun watchFor(directory: File): FakeDirectoryWatch? = watches[directory.absolutePath]
    }

    private class FakeDirectoryWatch(
        val directory: File,
        private val onEvent: (event: Int, path: String?) -> Unit,
    ) : DirectoryWatch {
        override fun start() = Unit

        override fun stop() = Unit

        fun emit(event: Int, path: String?) = onEvent(event, path)
    }
}
