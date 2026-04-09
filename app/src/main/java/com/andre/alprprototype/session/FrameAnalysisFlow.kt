package com.andre.alprprototype.session

internal data class FrameAnalysisDecision(
    val savedCropPath: String?,
    val framesSinceSavedCrop: Int,
    val assistedPromptShown: Boolean,
    val shouldResetCenterCapture: Boolean,
    val ocrCropPath: String? = null,
    val shouldPromptAssistedCapture: Boolean = false,
)

internal object FrameAnalysisFlow {
    fun decide(
        savedCropPath: String?,
        previousFramesSinceSavedCrop: Int,
        assistedPromptShown: Boolean,
        isProcessingViolation: Boolean,
        assistedPromptThreshold: Int,
    ): FrameAnalysisDecision {
        return if (savedCropPath != null) {
            FrameAnalysisDecision(
                savedCropPath = savedCropPath,
                framesSinceSavedCrop = 0,
                assistedPromptShown = false,
                shouldResetCenterCapture = true,
                ocrCropPath = savedCropPath,
            )
        } else {
            val nextFramesSinceSavedCrop = previousFramesSinceSavedCrop + 1
            val shouldPromptAssistedCapture = CameraSessionPolicy.shouldPromptAssistedCapture(
                hasSavedCrop = false,
                isProcessingViolation = isProcessingViolation,
                assistedPromptShown = assistedPromptShown,
                framesSinceSavedCrop = nextFramesSinceSavedCrop,
                threshold = assistedPromptThreshold,
            )
            FrameAnalysisDecision(
                savedCropPath = null,
                framesSinceSavedCrop = nextFramesSinceSavedCrop,
                assistedPromptShown = if (shouldPromptAssistedCapture) true else assistedPromptShown,
                shouldResetCenterCapture = false,
                shouldPromptAssistedCapture = shouldPromptAssistedCapture,
            )
        }
    }
}
