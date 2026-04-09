package com.andre.alprprototype

import com.andre.alprprototype.session.ViolationEvidenceFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViolationEvidenceFlowTest {
    @Test
    fun reviewPlateUpdated_only_finishes_for_valid_plate() {
        val valid = ViolationEvidenceFlow.reviewPlateUpdated(RegistryManager.PlateValidationResult.VALID)
        val expired = ViolationEvidenceFlow.reviewPlateUpdated(RegistryManager.PlateValidationResult.EXPIRED)

        assertTrue(valid.shouldFinishEvidenceFlow)
        assertTrue(valid.shouldDismissDialog)
        assertEquals(R.string.plate_corrected_valid_message, valid.toastMessageRes)

        assertFalse(expired.shouldFinishEvidenceFlow)
        assertFalse(expired.shouldDismissDialog)
        assertEquals(null, expired.toastMessageRes)
    }

    @Test
    fun reviewDismissed_and_captureCancelled_finish_evidence_flow() {
        val reviewDismissed = ViolationEvidenceFlow.reviewDismissed()
        val captureCancelled = ViolationEvidenceFlow.capturePromptCancelled()

        assertTrue(reviewDismissed.shouldFinishEvidenceFlow)
        assertFalse(reviewDismissed.shouldDismissDialog)

        assertTrue(captureCancelled.shouldFinishEvidenceFlow)
        assertTrue(captureCancelled.shouldDismissDialog)
    }

    @Test
    fun vehicleCapture_outcomes_expose_expected_ui_actions() {
        val unavailable = ViolationEvidenceFlow.vehicleCaptureUnavailable()
        val failed = ViolationEvidenceFlow.vehicleCaptureFailed()
        val accepted = ViolationEvidenceFlow.photoAccepted()
        val retake = ViolationEvidenceFlow.photoRetake()
        val queued = ViolationEvidenceFlow.queuedViolationResult(isSuccess = true, plateText = "ABC123")
        val failedQueue = ViolationEvidenceFlow.queuedViolationResult(isSuccess = false, plateText = "ABC123")

        assertTrue(unavailable.shouldFinishEvidenceFlow)
        assertEquals(R.string.camera_not_ready_message, unavailable.toastMessageRes)

        assertTrue(failed.shouldFinishEvidenceFlow)
        assertEquals(null, failed.toastMessageRes)

        assertTrue(accepted.shouldFinishEvidenceFlow)
        assertTrue(accepted.shouldDismissDialog)
        assertTrue(accepted.shouldQueueViolation)

        assertTrue(retake.shouldDismissDialog)
        assertTrue(retake.shouldRestartCapturePrompt)

        assertEquals(R.string.violation_queued_message, queued.toastMessageRes)
        assertEquals("ABC123", queued.toastFormatArg)
        assertEquals(R.string.violation_queue_failed_message, failedQueue.toastMessageRes)
    }
}
