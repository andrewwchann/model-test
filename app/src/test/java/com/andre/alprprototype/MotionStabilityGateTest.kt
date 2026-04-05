package com.andre.alprprototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionStabilityGateTest {
    @Test
    fun shouldProcess_returns_true_for_null_signature() {
        val gate = MotionStabilityGate()

        assertTrue(gate.shouldProcess(null))
    }

    @Test
    fun shouldProcess_returns_true_for_first_signature() {
        val gate = MotionStabilityGate()

        assertTrue(gate.shouldProcess(intArrayOf(10, 10, 10)))
    }

    @Test
    fun shouldProcess_pauses_when_motion_exceeds_threshold() {
        val gate = MotionStabilityGate()

        assertTrue(gate.shouldProcess(intArrayOf(10, 10, 10)))
        assertFalse(gate.shouldProcess(intArrayOf(40, 40, 40)))
    }

    @Test
    fun shouldProcess_resumes_after_enough_stable_frames() {
        val gate = MotionStabilityGate()

        assertTrue(gate.shouldProcess(intArrayOf(10, 10, 10)))
        assertFalse(gate.shouldProcess(intArrayOf(40, 40, 40)))
        assertFalse(gate.shouldProcess(intArrayOf(40, 40, 40)))
        assertFalse(gate.shouldProcess(intArrayOf(40, 40, 40)))
        assertFalse(gate.shouldProcess(intArrayOf(40, 40, 40)))
        assertTrue(gate.shouldProcess(intArrayOf(40, 40, 40)))
    }

    @Test
    fun shouldProcess_handles_mismatched_signature_sizes_as_motion() {
        val gate = MotionStabilityGate()

        assertTrue(gate.shouldProcess(intArrayOf(10, 10, 10)))
        assertFalse(gate.shouldProcess(intArrayOf(10, 10)))
    }

    @Test
    fun reset_clears_pause_state() {
        val gate = MotionStabilityGate()

        assertTrue(gate.shouldProcess(intArrayOf(10, 10, 10)))
        assertFalse(gate.shouldProcess(intArrayOf(40, 40, 40)))
        gate.reset()

        assertTrue(gate.shouldProcess(intArrayOf(10, 10, 10)))
    }
}
