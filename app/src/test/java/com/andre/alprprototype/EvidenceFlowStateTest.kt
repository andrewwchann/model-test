package com.andre.alprprototype

import com.andre.alprprototype.session.EvidenceFlowState
import com.andre.alprprototype.session.EvidenceFlowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceFlowStateTest {
    @Test
    fun starts_idle_and_inactive() {
        val state = EvidenceFlowState()

        assertEquals(EvidenceFlowStep.IDLE, state.step)
        assertFalse(state.isActive)
        assertFalse(state.isProcessingViolation)
    }

    @Test
    fun transitions_through_violation_evidence_steps_and_back_to_idle() {
        val state = EvidenceFlowState()

        state.beginViolationReview()
        assertEquals(EvidenceFlowStep.VIOLATION_REVIEW, state.step)
        assertTrue(state.isActive)
        assertTrue(state.isProcessingViolation)

        state.beginCapturePrompt()
        assertEquals(EvidenceFlowStep.CAPTURE_PROMPT, state.step)
        assertTrue(state.isActive)

        state.beginPhotoConfirmation()
        assertEquals(EvidenceFlowStep.PHOTO_CONFIRMATION, state.step)
        assertTrue(state.isActive)

        state.finish()
        assertEquals(EvidenceFlowStep.IDLE, state.step)
        assertFalse(state.isActive)
        assertFalse(state.isProcessingViolation)
    }
}
