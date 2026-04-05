package com.andre.alprprototype

import com.andre.alprprototype.ocr.PlateRecognitionAction
import com.andre.alprprototype.session.ViolationReviewFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViolationReviewFlowTest {
    @Test
    fun handleManualPlateEntry_rejects_blank_text() {
        val decision = ViolationReviewFlow.handleManualPlateEntry(
            rawText = " - ",
            cropPath = "crop.jpg",
            shouldProcessConfirmedPlate = { true },
            validator = { RegistryManager.PlateValidationResult.VALID },
        )

        assertFalse(decision.accepted)
        assertNull(decision.ocrResult)
    }

    @Test
    fun handleManualPlateEntry_builds_result_and_valid_action() {
        val decision = ViolationReviewFlow.handleManualPlateEntry(
            rawText = "abc 123",
            cropPath = "crop.jpg",
            shouldProcessConfirmedPlate = { true },
            validator = { RegistryManager.PlateValidationResult.VALID },
        )

        assertTrue(decision.accepted)
        assertEquals("ABC123", decision.ocrResult?.text)
        assertEquals(PlateRecognitionAction.SHOW_VALID, decision.recognitionAction)
        assertEquals("ABC123", decision.normalizedText)
    }

    @Test
    fun handleManualPlateEntry_respects_ignore_decision() {
        val decision = ViolationReviewFlow.handleManualPlateEntry(
            rawText = "abc 123",
            cropPath = "crop.jpg",
            shouldProcessConfirmedPlate = { false },
            validator = { RegistryManager.PlateValidationResult.EXPIRED },
        )

        assertTrue(decision.accepted)
        assertEquals(PlateRecognitionAction.IGNORE, decision.recognitionAction)
    }

    @Test
    fun handleViolationPlateEdit_rejects_blank_text() {
        val decision = ViolationReviewFlow.handleViolationPlateEdit(
            rawText = "",
            confidence = 0.8f,
            cropPath = "crop.jpg",
            previousResult = null,
            validator = { RegistryManager.PlateValidationResult.VALID },
        )

        assertFalse(decision.accepted)
        assertNull(decision.validation)
    }

    @Test
    fun handleViolationPlateEdit_preserves_previous_metrics() {
        val previous = OcrDisplayResult(
            text = "ABC123",
            sourcePath = "old.jpg",
            confidence = 0.5f,
            agreementCount = 2,
            variantCount = 3,
            scoreMargin = 0.12f,
        )

        val decision = ViolationReviewFlow.handleViolationPlateEdit(
            rawText = "xyz999",
            confidence = 0.8f,
            cropPath = "crop.jpg",
            previousResult = previous,
            validator = { RegistryManager.PlateValidationResult.EXPIRED },
        )

        assertTrue(decision.accepted)
        assertEquals(RegistryManager.PlateValidationResult.EXPIRED, decision.validation)
        assertEquals("XYZ999", decision.ocrResult?.text)
        assertEquals(2, decision.ocrResult?.agreementCount)
        assertEquals(3, decision.ocrResult?.variantCount)
        assertEquals(0.12f, decision.ocrResult?.scoreMargin ?: 0f, 0.0001f)
    }

    @Test
    fun buildViolationEvent_sets_expected_fields() {
        val event = ViolationReviewFlow.buildViolationEvent(
            plateText = "ABC123",
            confidence = 0.91f,
            timestamp = "2026-04-05T00:00:00Z",
            cropPath = "plate.jpg",
            vehiclePath = "vehicle.jpg",
        )

        assertEquals("ABC123", event.rawOcrText)
        assertEquals(0.91f, event.confidenceScore, 0.0001f)
        assertEquals("2026-04-05T00:00:00Z", event.timestamp)
        assertEquals("Device_01", event.operatorId)
        assertEquals("plate.jpg", event.localPlatePath)
        assertEquals("vehicle.jpg", event.localVehiclePath)
    }
}
