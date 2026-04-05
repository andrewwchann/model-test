package com.andre.alprprototype.session

import android.graphics.Bitmap
import com.andre.alprprototype.alpr.AssistedCropResult
import com.andre.alprprototype.alpr.NormalizedRect

internal sealed class CenterAssistArmDecision {
    data object Ignore : CenterAssistArmDecision()
    data object PreviewNotReady : CenterAssistArmDecision()
    data class Arm(val overlayRect: NormalizedRect) : CenterAssistArmDecision()
}

internal sealed class CenterAssistCaptureDecision {
    data object Ignore : CenterAssistCaptureDecision()
    data object PreviewNotReady : CenterAssistCaptureDecision()
    data object CaptureFailed : CenterAssistCaptureDecision()
    data class Captured(val crop: AssistedCropResult) : CenterAssistCaptureDecision()
}

internal object CenterAssistFlow {
    fun decideArm(
        canUpdateUi: Boolean,
        isProcessingViolation: Boolean,
        overlayRect: NormalizedRect?,
    ): CenterAssistArmDecision {
        if (!canUpdateUi || isProcessingViolation) {
            return CenterAssistArmDecision.Ignore
        }
        return if (overlayRect == null) {
            CenterAssistArmDecision.PreviewNotReady
        } else {
            CenterAssistArmDecision.Arm(overlayRect)
        }
    }

    fun capture(
        canUpdateUi: Boolean,
        isProcessingViolation: Boolean,
        previewBitmap: Bitmap?,
        saveFromCenter: (Bitmap) -> AssistedCropResult?,
    ): CenterAssistCaptureDecision {
        if (!canUpdateUi || isProcessingViolation) {
            return CenterAssistCaptureDecision.Ignore
        }
        if (previewBitmap == null) {
            return CenterAssistCaptureDecision.PreviewNotReady
        }

        return try {
            val assistedCrop = saveFromCenter(previewBitmap)
            if (assistedCrop == null) {
                CenterAssistCaptureDecision.CaptureFailed
            } else {
                CenterAssistCaptureDecision.Captured(assistedCrop)
            }
        } catch (_: Exception) {
            CenterAssistCaptureDecision.CaptureFailed
        } finally {
            if (!previewBitmap.isRecycled) {
                previewBitmap.recycle()
            }
        }
    }
}
