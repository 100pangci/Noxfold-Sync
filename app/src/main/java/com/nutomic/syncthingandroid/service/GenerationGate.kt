package com.nutomic.syncthingandroid.service

/**
 * Small commit gate for one bridge session. It does not cancel blocking I/O; it only makes an
 * invalidated generation unable to publish state after unregister/reauthorize/stop.
 */
internal class GenerationGate(private val generation: Long) {

    private val lock = Any()
    @Volatile
    private var active = true

    fun invalidate() {
        synchronized(lock) {
            active = false
        }
    }

    fun isCurrent(expectedGeneration: Long): Boolean {
        return active && expectedGeneration == generation
    }

    fun commitIfCurrent(expectedGeneration: Long, commit: () -> Unit): Boolean {
        synchronized(lock) {
            if (!active || expectedGeneration != generation) {
                return false
            }
            commit()
            return true
        }
    }
}
