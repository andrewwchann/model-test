package com.andre.alprprototype

import com.andre.alprprototype.ocr.PlateRecognitionAction
import com.andre.alprprototype.ocr.PlateRecognitionFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class PlateRecognitionFlowTest {
    @Test
    fun decide_ignores_blank_text() {
        val decision = PlateRecognitionFlow.decide(
            rawText = " - ",
            shouldProcessConfirmedPlate = { true },
            validator = { RegistryManager.PlateValidationResult.VALID },
        )

        assertEquals(PlateRecognitionAction.IGNORE, decision.action)
        assertEquals("", decision.normalizedText)
        assertEquals(null, decision.validationResult)
    }

    @Test
    fun decide_ignores_when_confirmed_plate_should_not_process() {
        val decision = PlateRecognitionFlow.decide(
            rawText = "abc123",
            shouldProcessConfirmedPlate = { false },
            validator = { RegistryManager.PlateValidationResult.VALID },
        )

        assertEquals(PlateRecognitionAction.IGNORE, decision.action)
        assertEquals("ABC123", decision.normalizedText)
        assertEquals(null, decision.validationResult)
    }

    @Test
    fun decide_returns_show_valid_for_valid_plate() {
        val decision = PlateRecognitionFlow.decide(
            rawText = "abc123",
            shouldProcessConfirmedPlate = { true },
            validator = { RegistryManager.PlateValidationResult.VALID },
        )

        assertEquals(PlateRecognitionAction.SHOW_VALID, decision.action)
        assertEquals("ABC123", decision.normalizedText)
        assertEquals(RegistryManager.PlateValidationResult.VALID, decision.validationResult)
    }

    @Test
    fun decide_returns_prompt_violation_for_missing_plate() {
        val decision = PlateRecognitionFlow.decide(
            rawText = "abc123",
            shouldProcessConfirmedPlate = { true },
            validator = { RegistryManager.PlateValidationResult.NOT_FOUND },
        )

        assertEquals(PlateRecognitionAction.PROMPT_VIOLATION, decision.action)
        assertEquals("ABC123", decision.normalizedText)
        assertEquals(RegistryManager.PlateValidationResult.NOT_FOUND, decision.validationResult)
    }

    @Test
    fun decide_returns_prompt_violation_for_expired_plate() {
        val decision = PlateRecognitionFlow.decide(
            rawText = "abc123",
            shouldProcessConfirmedPlate = { true },
            validator = { RegistryManager.PlateValidationResult.EXPIRED },
        )

        assertEquals(PlateRecognitionAction.PROMPT_VIOLATION, decision.action)
        assertEquals("ABC123", decision.normalizedText)
        assertEquals(RegistryManager.PlateValidationResult.EXPIRED, decision.validationResult)
    }
}
