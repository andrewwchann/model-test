package com.andre.alprprototype.session

import com.andre.alprprototype.OcrDisplayResult
import com.andre.alprprototype.RegistryManager
import com.andre.alprprototype.ViolationEvent
import com.andre.alprprototype.ocr.PlateRecognitionAction
import com.andre.alprprototype.ocr.PlateRecognitionFlow
import com.andre.alprprototype.ocr.PlateTextNormalizer

internal data class ManualPlateEntryDecision(
    val accepted: Boolean,
    val ocrResult: OcrDisplayResult? = null,
    val recognitionAction: PlateRecognitionAction = PlateRecognitionAction.IGNORE,
    val normalizedText: String? = null,
)

internal data class ViolationPlateEditDecision(
    val accepted: Boolean,
    val ocrResult: OcrDisplayResult? = null,
    val validation: RegistryManager.PlateValidationResult? = null,
    val normalizedText: String? = null,
)

internal object ViolationReviewFlow {
    fun handleManualPlateEntry(
        rawText: String,
        cropPath: String,
        shouldProcessConfirmedPlate: (String) -> Boolean,
        validator: (String) -> RegistryManager.PlateValidationResult,
    ): ManualPlateEntryDecision {
        val normalizedText = PlateTextNormalizer.normalize(rawText)
        if (normalizedText.isBlank()) {
            return ManualPlateEntryDecision(accepted = false)
        }
        val ocrResult = OcrDisplayResult(
            text = normalizedText,
            sourcePath = cropPath,
            confidence = null,
            agreementCount = 0,
            variantCount = 0,
            scoreMargin = null,
        )
        val recognition = PlateRecognitionFlow.decide(
            rawText = normalizedText,
            shouldProcessConfirmedPlate = shouldProcessConfirmedPlate,
            validator = validator,
        )
        return ManualPlateEntryDecision(
            accepted = true,
            ocrResult = ocrResult,
            recognitionAction = recognition.action,
            normalizedText = recognition.normalizedText,
        )
    }

    fun handleViolationPlateEdit(
        rawText: String,
        confidence: Float,
        cropPath: String,
        previousResult: OcrDisplayResult?,
        validator: (String) -> RegistryManager.PlateValidationResult,
    ): ViolationPlateEditDecision {
        val normalizedText = PlateTextNormalizer.normalize(rawText)
        if (normalizedText.isBlank()) {
            return ViolationPlateEditDecision(accepted = false)
        }

        val ocrResult = OcrDisplayResult(
            text = normalizedText,
            sourcePath = cropPath,
            confidence = confidence,
            agreementCount = previousResult?.agreementCount ?: 0,
            variantCount = previousResult?.variantCount ?: 0,
            scoreMargin = previousResult?.scoreMargin,
        )

        val recognition = PlateRecognitionFlow.decide(
            rawText = normalizedText,
            shouldProcessConfirmedPlate = { true },
            validator = validator,
        )
        return ViolationPlateEditDecision(
            accepted = recognition.validationResult != null,
            ocrResult = ocrResult,
            validation = recognition.validationResult,
            normalizedText = recognition.normalizedText,
        )
    }

    fun buildViolationEvent(
        plateText: String,
        confidence: Float,
        timestamp: String,
        cropPath: String,
        vehiclePath: String,
        operatorId: String = "Device_01",
    ): ViolationEvent {
        return ViolationEvent(
            rawOcrText = plateText,
            confidenceScore = confidence,
            timestamp = timestamp,
            operatorId = operatorId,
            localPlatePath = cropPath,
            localVehiclePath = vehiclePath,
        )
    }
}
