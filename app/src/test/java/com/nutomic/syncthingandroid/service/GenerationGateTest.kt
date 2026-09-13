package com.nutomic.syncthingandroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationGateTest {

    @Test
    fun invalidatedGeneration_cannotCommitSnapshot() {
        val gate = GenerationGate(7)
        var committed = false
        gate.invalidate()

        assertFalse(gate.isCurrent(7))
        assertFalse(gate.commitIfCurrent(7) { committed = true })
        assertFalse(committed)
    }

    @Test
    fun wrongGeneration_cannotCommit() {
        val gate = GenerationGate(8)
        var committed = false

        assertFalse(gate.commitIfCurrent(7) { committed = true })
        assertTrue(gate.commitIfCurrent(8) { committed = true })
        assertTrue(committed)
    }
}
