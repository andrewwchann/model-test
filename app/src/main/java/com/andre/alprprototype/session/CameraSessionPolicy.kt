package com.andre.alprprototype.session

import android.view.View

internal data class SessionChromeDecision(
    val topActionVisibility: Int,
    val centerAssistVisibility: Int,
    val guideVisibility: Int,
    val debugOverlayVisibility: Int,
    val shouldClearDebugState: Boolean,
    val shouldAttachAnalyzer: Boolean,
    val shouldClearAnalyzer: Boolean,
)

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

    fun chromeDecision(
        operatorDialogVisible: Boolean,
        evidenceFlowActive: Boolean,
        isAnalyzerAttached: Boolean,
        canUseCamera: Boolean,
    ): SessionChromeDecision {
        val showSessionChrome = shouldShowSessionChrome(
            operatorDialogVisible = operatorDialogVisible,
            evidenceFlowActive = evidenceFlowActive,
        )
        return SessionChromeDecision(
            topActionVisibility = if (showSessionChrome) View.VISIBLE else View.GONE,
            centerAssistVisibility = if (showSessionChrome) View.VISIBLE else View.GONE,
            guideVisibility = if (showSessionChrome) View.VISIBLE else View.GONE,
            debugOverlayVisibility = if (showSessionChrome) View.VISIBLE else View.INVISIBLE,
            shouldClearDebugState = !showSessionChrome,
            shouldAttachAnalyzer = showSessionChrome && shouldAttachAnalyzer(
                isAnalyzerAttached = isAnalyzerAttached,
                operatorDialogVisible = operatorDialogVisible,
                evidenceFlowActive = evidenceFlowActive,
                canUseCamera = canUseCamera,
            ),
            shouldClearAnalyzer = !showSessionChrome && isAnalyzerAttached,
        )
    }
}
