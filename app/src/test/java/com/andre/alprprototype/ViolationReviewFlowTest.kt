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
    fun manualEntryUiDecision_maps_valid_and_violation_actions() {
        val valid = ViolationReviewFlow.manualEntryUiDecision(
            rawText = "abc123",
            cropPath = "crop.jpg",
            shouldProcessConfirmedPlate = { true },
            validator = { RegistryManager.PlateValidationResult.VALID },
        )
        val violation = ViolationReviewFlow.manualEntryUiDecision(
            rawText = "xyz999",
            cropPath = "crop.jpg",
            shouldProcessConfirmedPlate = { true },
            validator = { RegistryManager.PlateValidationResult.EXPIRED },
        )

        assertTrue(valid.accepted)
        assertTrue(valid.shouldResetCenterCapture)
        assertEquals(R.string.plate_valid_message, valid.toastMessageRes)
        assertEquals("ABC123", valid.toastFormatArg)

        assertTrue(violation.accepted)
        assertEquals("XYZ999", violation.violationPlateText)
        assertEquals(null, violation.toastMessageRes)
    }

    @Test
    fun standalonePlateEditUiDecision_finishes_only_for_valid_plate() {
        val valid = ViolationReviewFlow.standalonePlateEditUiDecision(RegistryManager.PlateValidationResult.VALID)
        val expired = ViolationReviewFlow.standalonePlateEditUiDecision(RegistryManager.PlateValidationResult.EXPIRED)

        assertTrue(valid.shouldFinishEvidenceFlow)
        assertEquals(R.string.plate_corrected_valid_message, valid.toastMessageRes)

        assertFalse(expired.shouldFinishEvidenceFlow)
        assertEquals(null, expired.toastMessageRes)
    }

    @Test
    fun registryStatusTextRes_maps_all_validation_results() {
        assertEquals(R.string.registry_status_valid, ViolationReviewFlow.registryStatusTextRes(RegistryManager.PlateValidationResult.VALID))
        assertEquals(R.string.registry_status_expired, ViolationReviewFlow.registryStatusTextRes(RegistryManager.PlateValidationResult.EXPIRED))
        assertEquals(R.string.registry_status_not_found, ViolationReviewFlow.registryStatusTextRes(RegistryManager.PlateValidationResult.NOT_FOUND))
    }

    @Test
    fun plateInputSpecs_match_manual_and_edit_dialogs() {
        val manual = ViolationReviewFlow.manualPlateInputSpec("abc123")
        val edit = ViolationReviewFlow.violationPlateEditSpec("XYZ999")

        assertEquals(R.string.manual_plate_entry_title, manual.titleRes)
        assertEquals(R.string.manual_plate_entry_message, manual.messageRes)
        assertEquals(R.string.manual_plate_entry_hint, manual.hintRes)
        assertEquals(R.string.manual_plate_entry_confirm, manual.positiveButtonRes)
        assertEquals(R.string.dialog_cancel, manual.negativeButtonRes)
        assertEquals("abc123", manual.initialText)
        assertEquals(10, manual.maxLength)
        assertTrue(manual.useCenteredTitle)

        assertEquals(R.string.edit_plate_title, edit.titleRes)
        assertEquals(R.string.edit_plate_message, edit.messageRes)
        assertEquals(R.string.edit_plate_hint, edit.hintRes)
        assertEquals(R.string.edit_plate_confirm, edit.positiveButtonRes)
        assertEquals(R.string.dialog_cancel, edit.negativeButtonRes)
        assertEquals("XYZ999", edit.initialText)
        assertEquals(10, edit.maxLength)
        assertFalse(edit.useCenteredTitle)
    }

    @Test
    fun reviewDisplayState_uses_status_mapping() {
        val state = ViolationReviewFlow.reviewDisplayState(
            plateText = "ABC123",
            validation = RegistryManager.PlateValidationResult.EXPIRED,
        )

        assertEquals("ABC123", state.plateText)
        assertEquals(R.string.registry_status_expired, state.statusTextRes)
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
