package com.andre.alprprototype.ocr

import com.andre.alprprototype.RegistryManager

internal enum class PlateRecognitionAction {
    IGNORE,
    SHOW_VALID,
    PROMPT_VIOLATION,
}

internal data class PlateRecognitionDecision(
    val normalizedText: String,
    val validationResult: RegistryManager.PlateValidationResult?,
    val action: PlateRecognitionAction,
)

internal object PlateRecognitionFlow {
    fun decide(
        rawText: String,
        shouldProcessConfirmedPlate: (String) -> Boolean,
        validator: (String) -> RegistryManager.PlateValidationResult,
    ): PlateRecognitionDecision {
        val normalizedText = PlateTextNormalizer.normalize(rawText)
        if (normalizedText.isBlank()) {
            return PlateRecognitionDecision(
                normalizedText = normalizedText,
                validationResult = null,
                action = PlateRecognitionAction.IGNORE,
            )
        }
        if (!shouldProcessConfirmedPlate(normalizedText)) {
            return PlateRecognitionDecision(
                normalizedText = normalizedText,
                validationResult = null,
                action = PlateRecognitionAction.IGNORE,
            )
        }

        val validation = validator(normalizedText)
        val action = when (validation) {
            RegistryManager.PlateValidationResult.VALID -> PlateRecognitionAction.SHOW_VALID
            RegistryManager.PlateValidationResult.EXPIRED,
            RegistryManager.PlateValidationResult.NOT_FOUND,
            -> PlateRecognitionAction.PROMPT_VIOLATION
        }

        return PlateRecognitionDecision(
            normalizedText = normalizedText,
            validationResult = validation,
            action = action,
        )
    }
}
