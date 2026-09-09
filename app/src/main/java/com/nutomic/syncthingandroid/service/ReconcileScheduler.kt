package com.nutomic.syncthingandroid.service

import java.util.concurrent.atomic.AtomicBoolean

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel

/**
 * Serializes reconcile requests for one bridge.
 *
 * Observer callbacks are hints rather than synchronization decisions. They only wake this
 * scheduler; the reconcile callback remains the source of truth. The fallback job periodically
 * requests a full reconcile so a lost observer event cannot leave a bridge permanently stale.
 */
internal class ReconcileScheduler(
    private val scope: CoroutineScope,
    private val debounceMs: Long = RECONCILE_DEBOUNCE_MS,
    private val fullIntervalMs: Long = FULL_RECONCILE_INTERVAL_MS,
    private val reconcile: suspend (full: Boolean) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {

    companion object {
        const val RECONCILE_DEBOUNCE_MS = 300L
        const val FULL_RECONCILE_INTERVAL_MS = 5 * 60 * 1000L
    }

    private var worker: Job? = null
    private var fallback: Job? = null
    private var signal: Channel<Unit> = Channel(Channel.CONFLATED)
    private val running = AtomicBoolean(false)
    private val fullRequested = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        val channel = signal
        worker = scope.launch {
            // A bridge must reconcile immediately after its observers are installed.
            runReconcile(full = true)
            for (ignored in channel) {
                // This is a bounded debounce window, not an indefinitely resetting delay. A
                // continuous event burst therefore cannot starve reconciliation forever.
                delay(debounceMs)
                while (channel.tryReceive().isSuccess) {
                    // Drain the conflated wake-up channel before one reconcile.
                }
                runReconcile(fullRequested.getAndSet(false))
            }
        }

        fallback = scope.launch {
            while (isActive) {
                delay(fullIntervalMs)
                requestReconcile(full = true)
            }
        }
    }

    /** Requests a reconcile without running one from the observer thread. */
    fun requestReconcile(full: Boolean = false) {
        if (!running.get()) {
            return
        }
        if (full) {
            fullRequested.set(true)
        }
        signal.trySend(Unit)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        worker?.cancel()
        fallback?.cancel()
        worker = null
        fallback = null
        signal.close()
        fullRequested.set(false)
    }

    private suspend fun runReconcile(full: Boolean) {
        try {
            reconcile(full)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onFailure(e)
        }
    }
}
