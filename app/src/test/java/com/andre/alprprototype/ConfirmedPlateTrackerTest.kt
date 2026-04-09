package com.andre.alprprototype

import com.andre.alprprototype.session.ConfirmedPlateTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmedPlateTrackerTest {
    @Test
    fun shouldProcess_rejects_blank_plate() {
        val tracker = ConfirmedPlateTracker(cooldownMs = 4_000L) { 1_000L }

        assertFalse(tracker.shouldProcess(""))
    }

    @Test
    fun shouldProcess_accepts_first_plate_and_blocks_repeat_within_cooldown() {
        var now = 1_000L
        val tracker = ConfirmedPlateTracker(cooldownMs = 4_000L) { now }

        assertTrue(tracker.shouldProcess("ABC123"))
        now = 2_000L
        assertFalse(tracker.shouldProcess("ABC123"))
    }

    @Test
    fun shouldProcess_allows_repeat_after_cooldown() {
        var now = 1_000L
        val tracker = ConfirmedPlateTracker(cooldownMs = 4_000L) { now }

        assertTrue(tracker.shouldProcess("ABC123"))
        now = 5_001L
        assertTrue(tracker.shouldProcess("ABC123"))
    }

    @Test
    fun shouldProcess_allows_different_plate_within_cooldown() {
        var now = 1_000L
        val tracker = ConfirmedPlateTracker(cooldownMs = 4_000L) { now }

        assertTrue(tracker.shouldProcess("ABC123"))
        now = 2_000L
        assertTrue(tracker.shouldProcess("XYZ987"))
    }
}
