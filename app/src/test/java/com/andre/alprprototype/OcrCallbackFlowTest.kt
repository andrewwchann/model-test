package com.andre.alprprototype

import com.andre.alprprototype.ocr.PlateRecognitionAction
import com.andre.alprprototype.session.CameraCropSource
import com.andre.alprprototype.session.OcrCallbackFlow
import com.andre.alprprototype.session.OcrResultUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCallbackFlowTest {
    @Test
    fun decide_ignores_when_ui_cannot_update() {
        val decision = OcrCallbackFlow.decide(
            canUpdateUi = false,
            cropMatchesLatestRequest = true,
            isProcessingViolation = false,
            cropSource = CameraCropSource.AUTO,
            result = OcrDisplayResult("ABC123", "crop.jpg", 0.9f, 2, 3, 0.2f),
            shouldEscalateAssistedCropToManual = { false },
            shouldProcessConfirmedPlate = { true },
            validator = { RegistryManager.PlateValidationResult.EXPIRED },
        )

        assertFalse(decision.shouldApply)
    }

    @Test
    fun decide_escalates_assisted_crop_to_manual() {
        val decision = OcrCallbackFlow.decide(
            canUpdateUi = true,
            cropMatchesLatestRequest = true,
            isProcessingViolation = false,
            cropSource = CameraCropSource.ASSISTED,
            result = OcrDisplayResult("ABC123", "crop.jpg", 0.5f, 1, 2, 0.01f),
            shouldEscalateAssistedCropToManual = { true },
            shouldProcessConfirmedPlate = { true },
            validator = { RegistryManager.PlateValidationResult.EXPIRED },
        )

        assertTrue(decision.shouldApply)
        assertEquals(OcrResultUiState.READY, decision.uiState)
        assertTrue(decision.shouldResetCenterCapture)
        assertTrue(decision.promptManualEntry)
        assertEquals("ABC123", decision.manualEntrySuggestion)
        assertEquals(PlateRecognitionAction.IGNORE, decision.recognitionAction)
    }

    @Test
    fun decide_marks_blank_text_as_unavailable() {
        val decision = OcrCallbackFlow.decide(
            canUpdateUi = true,
            cropMatchesLatestRequest = true,
            isProcessingViolation = false,
            cropSource = CameraCropSource.AUTO,
            result = OcrDisplayResult("", "crop.jpg", 0.2f, 0, 1, null),
            shouldEscalateAssistedCropToManual = { false },
            shouldProcessConfirmedPlate = { false },
            validator = { RegistryManager.PlateValidationResult.NOT_FOUND },
        )

        assertTrue(decision.shouldApply)
        assertEquals(OcrResultUiState.UNAVAILABLE, decision.uiState)
        assertEquals(PlateRecognitionAction.IGNORE, decision.recognitionAction)
    }

    @Test
    fun decide_returns_valid_action_for_confirmed_text() {
        val decision = OcrCallbackFlow.decide(
            canUpdateUi = true,
            cropMatchesLatestRequest = true,
            isProcessingViolation = false,
            cropSource = CameraCropSource.ASSISTED,
            result = OcrDisplayResult("abc 123", "crop.jpg", 0.95f, 2, 3, 0.2f),
            shouldEscalateAssistedCropToManual = { false },
            shouldProcessConfirmedPlate = { true },
            validator = { RegistryManager.PlateValidationResult.VALID },
        )

        assertTrue(decision.shouldApply)
        assertTrue(decision.shouldResetCenterCapture)
        assertEquals(PlateRecognitionAction.SHOW_VALID, decision.recognitionAction)
        assertEquals("ABC123", decision.normalizedText)
    }

    @Test
    fun decide_returns_violation_action_for_invalid_plate() {
        val decision = OcrCallbackFlow.decide(
            canUpdateUi = true,
            cropMatchesLatestRequest = true,
            isProcessingViolation = false,
            cropSource = CameraCropSource.AUTO,
            result = OcrDisplayResult("ABC123", "crop.jpg", 0.95f, 2, 3, 0.2f),
            shouldEscalateAssistedCropToManual = { false },
            shouldProcessConfirmedPlate = { true },
            validator = { RegistryManager.PlateValidationResult.EXPIRED },
        )

        assertEquals(PlateRecognitionAction.PROMPT_VIOLATION, decision.recognitionAction)
        assertEquals("ABC123", decision.normalizedText)
    }

    @Test
    fun decide_ignores_when_crop_no_longer_matches_latest_request() {
        val decision = OcrCallbackFlow.decide(
            canUpdateUi = true,
            cropMatchesLatestRequest = false,
            isProcessingViolation = false,
            cropSource = CameraCropSource.AUTO,
            result = OcrDisplayResult("ABC123", "crop.jpg", 0.9f, 2, 3, 0.2f),
            shouldEscalateAssistedCropToManual = { false },
            shouldProcessConfirmedPlate = { true },
            validator = { RegistryManager.PlateValidationResult.VALID },
        )

        assertFalse(decision.shouldApply)
    }

    @Test
    fun decide_ignores_when_violation_processing_is_active() {
        val decision = OcrCallbackFlow.decide(
            canUpdateUi = true,
            cropMatchesLatestRequest = true,
            isProcessingViolation = true,
            cropSource = CameraCropSource.AUTO,
            result = OcrDisplayResult("ABC123", "crop.jpg", 0.9f, 2, 3, 0.2f),
            shouldEscalateAssistedCropToManual = { false },
            shouldProcessConfirmedPlate = { true },
            validator = { RegistryManager.PlateValidationResult.VALID },
        )

        assertFalse(decision.shouldApply)
    }

    @Test
    fun decide_escalates_assisted_crop_with_null_result_suggestion() {
        val decision = OcrCallbackFlow.decide(
            canUpdateUi = true,
            cropMatchesLatestRequest = true,
            isProcessingViolation = false,
            cropSource = CameraCropSource.ASSISTED,
            result = null,
            shouldEscalateAssistedCropToManual = { true },
            shouldProcessConfirmedPlate = { true },
            validator = { RegistryManager.PlateValidationResult.NOT_FOUND },
        )

        assertTrue(decision.shouldApply)
        assertTrue(decision.promptManualEntry)
        assertNull(decision.manualEntrySuggestion)
    }
}
