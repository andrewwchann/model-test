package com.andre.alprprototype.session

import com.andre.alprprototype.R
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

internal data class ManualPlateEntryUiDecision(
    val accepted: Boolean,
    val ocrResult: OcrDisplayResult? = null,
    val shouldResetCenterCapture: Boolean = false,
    val toastMessageRes: Int? = null,
    val toastFormatArg: String? = null,
    val violationPlateText: String? = null,
)

internal data class StandalonePlateEditUiDecision(
    val shouldFinishEvidenceFlow: Boolean,
    val toastMessageRes: Int? = null,
)

internal data class PlateInputDialogSpec(
    val titleRes: Int,
    val messageRes: Int,
    val hintRes: Int,
    val positiveButtonRes: Int,
    val negativeButtonRes: Int,
    val initialText: String,
    val maxLength: Int,
    val useCenteredTitle: Boolean = false,
)

internal data class ViolationReviewDisplayState(
    val plateText: String,
    val statusTextRes: Int,
)

internal object ViolationReviewFlow {
    private const val PLATE_INPUT_MAX_LENGTH = 10

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

    fun manualEntryUiDecision(
        rawText: String,
        cropPath: String,
        shouldProcessConfirmedPlate: (String) -> Boolean,
        validator: (String) -> RegistryManager.PlateValidationResult,
    ): ManualPlateEntryUiDecision {
        val decision = handleManualPlateEntry(
            rawText = rawText,
            cropPath = cropPath,
            shouldProcessConfirmedPlate = shouldProcessConfirmedPlate,
            validator = validator,
        )
        if (!decision.accepted) {
            return ManualPlateEntryUiDecision(accepted = false)
        }
        return when (decision.recognitionAction) {
            PlateRecognitionAction.IGNORE -> ManualPlateEntryUiDecision(
                accepted = true,
                ocrResult = decision.ocrResult,
                shouldResetCenterCapture = true,
            )
            PlateRecognitionAction.SHOW_VALID -> ManualPlateEntryUiDecision(
                accepted = true,
                ocrResult = decision.ocrResult,
                shouldResetCenterCapture = true,
                toastMessageRes = R.string.plate_valid_message,
                toastFormatArg = decision.normalizedText,
            )
            PlateRecognitionAction.PROMPT_VIOLATION -> ManualPlateEntryUiDecision(
                accepted = true,
                ocrResult = decision.ocrResult,
                shouldResetCenterCapture = true,
                violationPlateText = decision.normalizedText,
            )
        }
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
        val validation = requireNotNull(recognition.validationResult) {
            "Non-blank violation plate edit should always produce a validation result"
        }
        return ViolationPlateEditDecision(
            accepted = true,
            ocrResult = ocrResult,
            validation = validation,
            normalizedText = recognition.normalizedText,
        )
    }

    fun standalonePlateEditUiDecision(validation: RegistryManager.PlateValidationResult): StandalonePlateEditUiDecision {
        return if (validation == RegistryManager.PlateValidationResult.VALID) {
            StandalonePlateEditUiDecision(
                shouldFinishEvidenceFlow = true,
                toastMessageRes = R.string.plate_corrected_valid_message,
            )
        } else {
            StandalonePlateEditUiDecision(shouldFinishEvidenceFlow = false)
        }
    }

    fun registryStatusTextRes(result: RegistryManager.PlateValidationResult): Int {
        return when (result) {
            RegistryManager.PlateValidationResult.VALID -> R.string.registry_status_valid
            RegistryManager.PlateValidationResult.EXPIRED -> R.string.registry_status_expired
            RegistryManager.PlateValidationResult.NOT_FOUND -> R.string.registry_status_not_found
        }
    }

    fun manualPlateInputSpec(suggestedText: String?): PlateInputDialogSpec {
        return PlateInputDialogSpec(
            titleRes = R.string.manual_plate_entry_title,
            messageRes = R.string.manual_plate_entry_message,
            hintRes = R.string.manual_plate_entry_hint,
            positiveButtonRes = R.string.manual_plate_entry_confirm,
            negativeButtonRes = R.string.dialog_cancel,
            initialText = suggestedText.orEmpty(),
            maxLength = PLATE_INPUT_MAX_LENGTH,
            useCenteredTitle = true,
        )
    }

    fun violationPlateEditSpec(originalPlateText: String): PlateInputDialogSpec {
        return PlateInputDialogSpec(
            titleRes = R.string.edit_plate_title,
            messageRes = R.string.edit_plate_message,
            hintRes = R.string.edit_plate_hint,
            positiveButtonRes = R.string.edit_plate_confirm,
            negativeButtonRes = R.string.dialog_cancel,
            initialText = originalPlateText,
            maxLength = PLATE_INPUT_MAX_LENGTH,
        )
    }

    fun reviewDisplayState(
        plateText: String,
        validation: RegistryManager.PlateValidationResult,
    ): ViolationReviewDisplayState {
        return ViolationReviewDisplayState(
            plateText = plateText,
            statusTextRes = registryStatusTextRes(validation),
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
