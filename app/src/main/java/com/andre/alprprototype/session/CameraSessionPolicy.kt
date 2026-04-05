package com.andre.alprprototype.session

internal object CameraSessionPolicy {
    fun shouldRequestOcr(
        cropPath: String,
        lastOcrRequestedPath: String?,
        isProcessingViolation: Boolean,
    ): Boolean {
        return cropPath != lastOcrRequestedPath && !isProcessingViolation
    }

    fun shouldPromptAssistedCapture(
        hasSavedCrop: Boolean,
        isProcessingViolation: Boolean,
        assistedPromptShown: Boolean,
        framesSinceSavedCrop: Int,
        threshold: Int,
    ): Boolean {
        if (hasSavedCrop || isProcessingViolation || assistedPromptShown) {
            return false
        }
        return framesSinceSavedCrop >= threshold
    }

    fun shouldShowSessionChrome(
        operatorDialogVisible: Boolean,
        evidenceFlowActive: Boolean,
    ): Boolean {
        return !operatorDialogVisible && !evidenceFlowActive
    }

    fun shouldAttachAnalyzer(
        isAnalyzerAttached: Boolean,
        operatorDialogVisible: Boolean,
        evidenceFlowActive: Boolean,
        canUseCamera: Boolean,
    ): Boolean {
        return !isAnalyzerAttached && !operatorDialogVisible && !evidenceFlowActive && canUseCamera
    }
}
