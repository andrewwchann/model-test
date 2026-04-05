package com.andre.alprprototype

import com.andre.alprprototype.session.CameraSessionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionPolicyTest {
    @Test
    fun shouldRequestOcr_only_for_new_crop_when_not_processing_violation() {
        assertTrue(
            CameraSessionPolicy.shouldRequestOcr(
                cropPath = "new.jpg",
                lastOcrRequestedPath = "old.jpg",
                isProcessingViolation = false,
            ),
        )
        assertFalse(
            CameraSessionPolicy.shouldRequestOcr(
                cropPath = "same.jpg",
                lastOcrRequestedPath = "same.jpg",
                isProcessingViolation = false,
            ),
        )
        assertFalse(
            CameraSessionPolicy.shouldRequestOcr(
                cropPath = "new.jpg",
                lastOcrRequestedPath = "old.jpg",
                isProcessingViolation = true,
            ),
        )
    }

    @Test
    fun shouldPromptAssistedCapture_only_after_threshold_without_saved_crop_or_active_flow() {
        assertFalse(
            CameraSessionPolicy.shouldPromptAssistedCapture(
                hasSavedCrop = true,
                isProcessingViolation = false,
                assistedPromptShown = false,
                framesSinceSavedCrop = 99,
                threshold = 30,
            ),
        )
        assertFalse(
            CameraSessionPolicy.shouldPromptAssistedCapture(
                hasSavedCrop = false,
                isProcessingViolation = true,
                assistedPromptShown = false,
                framesSinceSavedCrop = 99,
                threshold = 30,
            ),
        )
        assertFalse(
            CameraSessionPolicy.shouldPromptAssistedCapture(
                hasSavedCrop = false,
                isProcessingViolation = false,
                assistedPromptShown = true,
                framesSinceSavedCrop = 99,
                threshold = 30,
            ),
        )
        assertFalse(
            CameraSessionPolicy.shouldPromptAssistedCapture(
                hasSavedCrop = false,
                isProcessingViolation = false,
                assistedPromptShown = false,
                framesSinceSavedCrop = 29,
                threshold = 30,
            ),
        )
        assertTrue(
            CameraSessionPolicy.shouldPromptAssistedCapture(
                hasSavedCrop = false,
                isProcessingViolation = false,
                assistedPromptShown = false,
                framesSinceSavedCrop = 30,
                threshold = 30,
            ),
        )
    }

    @Test
    fun shouldShowSessionChrome_only_when_no_dialog_and_no_evidence_flow() {
        assertTrue(CameraSessionPolicy.shouldShowSessionChrome(operatorDialogVisible = false, evidenceFlowActive = false))
        assertFalse(CameraSessionPolicy.shouldShowSessionChrome(operatorDialogVisible = true, evidenceFlowActive = false))
        assertFalse(CameraSessionPolicy.shouldShowSessionChrome(operatorDialogVisible = false, evidenceFlowActive = true))
        assertFalse(CameraSessionPolicy.shouldShowSessionChrome(operatorDialogVisible = true, evidenceFlowActive = true))
    }

    @Test
    fun shouldAttachAnalyzer_only_when_camera_session_is_idle_and_usable() {
        assertTrue(
            CameraSessionPolicy.shouldAttachAnalyzer(
                isAnalyzerAttached = false,
                operatorDialogVisible = false,
                evidenceFlowActive = false,
                canUseCamera = true,
            ),
        )
        assertFalse(
            CameraSessionPolicy.shouldAttachAnalyzer(
                isAnalyzerAttached = true,
                operatorDialogVisible = false,
                evidenceFlowActive = false,
                canUseCamera = true,
            ),
        )
        assertFalse(
            CameraSessionPolicy.shouldAttachAnalyzer(
                isAnalyzerAttached = false,
                operatorDialogVisible = true,
                evidenceFlowActive = false,
                canUseCamera = true,
            ),
        )
        assertFalse(
            CameraSessionPolicy.shouldAttachAnalyzer(
                isAnalyzerAttached = false,
                operatorDialogVisible = false,
                evidenceFlowActive = true,
                canUseCamera = true,
            ),
        )
        assertFalse(
            CameraSessionPolicy.shouldAttachAnalyzer(
                isAnalyzerAttached = false,
                operatorDialogVisible = false,
                evidenceFlowActive = false,
                canUseCamera = false,
            ),
        )
    }
}
