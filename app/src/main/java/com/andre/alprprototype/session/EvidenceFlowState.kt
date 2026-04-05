package com.andre.alprprototype.session

internal enum class EvidenceFlowStep {
    IDLE,
    VIOLATION_REVIEW,
    CAPTURE_PROMPT,
    PHOTO_CONFIRMATION,
}

internal class EvidenceFlowState(
    initialStep: EvidenceFlowStep = EvidenceFlowStep.IDLE,
) {
    var step: EvidenceFlowStep = initialStep
        private set

    val isActive: Boolean
        get() = step != EvidenceFlowStep.IDLE

    val isProcessingViolation: Boolean
        get() = isActive

    fun beginViolationReview() {
        step = EvidenceFlowStep.VIOLATION_REVIEW
    }

    fun beginCapturePrompt() {
        step = EvidenceFlowStep.CAPTURE_PROMPT
    }

    fun beginPhotoConfirmation() {
        step = EvidenceFlowStep.PHOTO_CONFIRMATION
    }

    fun finish() {
        step = EvidenceFlowStep.IDLE
    }
}
