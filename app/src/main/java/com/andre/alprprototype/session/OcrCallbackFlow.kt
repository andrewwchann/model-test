package com.andre.alprprototype.session

import com.andre.alprprototype.OcrDisplayResult
import com.andre.alprprototype.RegistryManager
import com.andre.alprprototype.ocr.PlateRecognitionAction
import com.andre.alprprototype.ocr.PlateRecognitionFlow

internal enum class OcrResultUiState {
    READY,
    UNAVAILABLE,
}

internal data class OcrCallbackDecision(
    val shouldApply: Boolean,
    val uiState: OcrResultUiState = OcrResultUiState.UNAVAILABLE,
    val shouldResetCenterCapture: Boolean = false,
    val promptManualEntry: Boolean = false,
    val manualEntrySuggestion: String? = null,
    val recognitionAction: PlateRecognitionAction = PlateRecognitionAction.IGNORE,
    val normalizedText: String? = null,
)

internal object OcrCallbackFlow {
    fun decide(
        canUpdateUi: Boolean,
        cropMatchesLatestRequest: Boolean,
        isProcessingViolation: Boolean,
        cropSource: CameraCropSource,
        result: OcrDisplayResult?,
        shouldEscalateAssistedCropToManual: (OcrDisplayResult?) -> Boolean,
        shouldProcessConfirmedPlate: (String) -> Boolean,
        validator: (String) -> RegistryManager.PlateValidationResult,
    ): OcrCallbackDecision {
        if (!canUpdateUi || !cropMatchesLatestRequest || isProcessingViolation) {
            return OcrCallbackDecision(shouldApply = false)
        }

        val text = result?.text.orEmpty()
        val uiState = if (text.isBlank()) OcrResultUiState.UNAVAILABLE else OcrResultUiState.READY
        val assistedCrop = cropSource == CameraCropSource.ASSISTED
        if (assistedCrop && shouldEscalateAssistedCropToManual(result)) {
            return OcrCallbackDecision(
                shouldApply = true,
                uiState = uiState,
                shouldResetCenterCapture = true,
                promptManualEntry = true,
                manualEntrySuggestion = result?.text,
            )
        }

        val shouldResetCenterCapture = assistedCrop
        if (!shouldProcessConfirmedPlate(text)) {
            return OcrCallbackDecision(
                shouldApply = true,
                uiState = uiState,
                shouldResetCenterCapture = shouldResetCenterCapture,
            )
        }

        val plateDecision = PlateRecognitionFlow.decide(
            rawText = text,
            shouldProcessConfirmedPlate = { true },
            validator = validator,
        )
        return OcrCallbackDecision(
            shouldApply = true,
            uiState = uiState,
            shouldResetCenterCapture = shouldResetCenterCapture,
            recognitionAction = plateDecision.action,
            normalizedText = plateDecision.normalizedText,
        )
    }
}

internal enum class CameraCropSource {
    AUTO,
    ASSISTED,
}
