package com.andre.alprprototype

import com.andre.alprprototype.session.FrameAnalysisFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAnalysisFlowTest {
    @Test
    fun decide_resets_state_when_frame_has_saved_crop() {
        val decision = FrameAnalysisFlow.decide(
            savedCropPath = "crop.jpg",
            previousFramesSinceSavedCrop = 12,
            assistedPromptShown = true,
            isProcessingViolation = false,
            assistedPromptThreshold = 24,
        )

        assertEquals("crop.jpg", decision.savedCropPath)
        assertEquals(0, decision.framesSinceSavedCrop)
        assertFalse(decision.assistedPromptShown)
        assertTrue(decision.shouldResetCenterCapture)
        assertEquals("crop.jpg", decision.ocrCropPath)
        assertFalse(decision.shouldPromptAssistedCapture)
    }

    @Test
    fun decide_increments_frame_count_without_prompt_before_threshold() {
        val decision = FrameAnalysisFlow.decide(
            savedCropPath = null,
            previousFramesSinceSavedCrop = 22,
            assistedPromptShown = false,
            isProcessingViolation = false,
            assistedPromptThreshold = 24,
        )

        assertNull(decision.savedCropPath)
        assertEquals(23, decision.framesSinceSavedCrop)
        assertFalse(decision.assistedPromptShown)
        assertFalse(decision.shouldResetCenterCapture)
        assertNull(decision.ocrCropPath)
        assertFalse(decision.shouldPromptAssistedCapture)
    }

    @Test
    fun decide_marks_prompt_as_shown_when_threshold_is_reached() {
        val decision = FrameAnalysisFlow.decide(
            savedCropPath = null,
            previousFramesSinceSavedCrop = 23,
            assistedPromptShown = false,
            isProcessingViolation = false,
            assistedPromptThreshold = 24,
        )

        assertEquals(24, decision.framesSinceSavedCrop)
        assertTrue(decision.assistedPromptShown)
        assertTrue(decision.shouldPromptAssistedCapture)
    }

    @Test
    fun decide_does_not_prompt_when_violation_flow_is_active() {
        val decision = FrameAnalysisFlow.decide(
            savedCropPath = null,
            previousFramesSinceSavedCrop = 23,
            assistedPromptShown = false,
            isProcessingViolation = true,
            assistedPromptThreshold = 24,
        )

        assertEquals(24, decision.framesSinceSavedCrop)
        assertFalse(decision.assistedPromptShown)
        assertFalse(decision.shouldPromptAssistedCapture)
    }
}
