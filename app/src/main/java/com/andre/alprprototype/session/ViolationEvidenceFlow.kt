package com.andre.alprprototype.session

import com.andre.alprprototype.R
import com.andre.alprprototype.RegistryManager

internal data class EvidenceUiDecision(
    val shouldFinishEvidenceFlow: Boolean = false,
    val shouldDismissDialog: Boolean = false,
    val toastMessageRes: Int? = null,
    val toastFormatArg: String? = null,
    val shouldQueueViolation: Boolean = false,
    val shouldRestartCapturePrompt: Boolean = false,
)

internal object ViolationEvidenceFlow {
    fun reviewPlateUpdated(validation: RegistryManager.PlateValidationResult): EvidenceUiDecision {
        return if (validation == RegistryManager.PlateValidationResult.VALID) {
            EvidenceUiDecision(
                shouldFinishEvidenceFlow = true,
                shouldDismissDialog = true,
                toastMessageRes = R.string.plate_corrected_valid_message,
            )
        } else {
            EvidenceUiDecision()
        }
    }

    fun reviewDismissed(): EvidenceUiDecision {
        return EvidenceUiDecision(shouldFinishEvidenceFlow = true)
    }

    fun capturePromptCancelled(): EvidenceUiDecision {
        return EvidenceUiDecision(
            shouldFinishEvidenceFlow = true,
            shouldDismissDialog = true,
        )
    }

    fun vehicleCaptureUnavailable(): EvidenceUiDecision {
        return EvidenceUiDecision(
            shouldFinishEvidenceFlow = true,
            toastMessageRes = R.string.camera_not_ready_message,
        )
    }

    fun vehicleCaptureFailed(): EvidenceUiDecision {
        return EvidenceUiDecision(shouldFinishEvidenceFlow = true)
    }

    fun photoAccepted(): EvidenceUiDecision {
        return EvidenceUiDecision(
            shouldFinishEvidenceFlow = true,
            shouldDismissDialog = true,
            shouldQueueViolation = true,
        )
    }

    fun photoRetake(): EvidenceUiDecision {
        return EvidenceUiDecision(
            shouldDismissDialog = true,
            shouldRestartCapturePrompt = true,
        )
    }

    fun queuedViolationResult(isSuccess: Boolean, plateText: String): EvidenceUiDecision {
        return if (isSuccess) {
            EvidenceUiDecision(
                toastMessageRes = R.string.violation_queued_message,
                toastFormatArg = plateText,
            )
        } else {
            EvidenceUiDecision(toastMessageRes = R.string.violation_queue_failed_message)
        }
    }
}
