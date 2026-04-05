package com.andre.alprprototype

import android.graphics.Bitmap
import com.andre.alprprototype.alpr.AssistedCropResult
import com.andre.alprprototype.alpr.NormalizedRect
import com.andre.alprprototype.session.CenterAssistArmDecision
import com.andre.alprprototype.session.CenterAssistCaptureDecision
import com.andre.alprprototype.session.CenterAssistFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CenterAssistFlowTest {
    @Test
    fun decideArm_ignores_when_ui_blocked() {
        val decision = CenterAssistFlow.decideArm(
            canUpdateUi = false,
            isProcessingViolation = false,
            overlayRect = NormalizedRect(0f, 0f, 1f, 1f),
        )

        assertTrue(decision is CenterAssistArmDecision.Ignore)
    }

    @Test
    fun decideArm_returns_preview_not_ready_when_overlay_missing() {
        val decision = CenterAssistFlow.decideArm(
            canUpdateUi = true,
            isProcessingViolation = false,
            overlayRect = null,
        )

        assertTrue(decision is CenterAssistArmDecision.PreviewNotReady)
    }

    @Test
    fun decideArm_returns_overlay_when_ready() {
        val rect = NormalizedRect(0.1f, 0.2f, 0.8f, 0.6f)
        val decision = CenterAssistFlow.decideArm(
            canUpdateUi = true,
            isProcessingViolation = false,
            overlayRect = rect,
        )

        assertEquals(rect, (decision as CenterAssistArmDecision.Arm).overlayRect)
    }

    @Test
    fun capture_returns_preview_not_ready_when_bitmap_missing() {
        val decision = CenterAssistFlow.capture(
            canUpdateUi = true,
            isProcessingViolation = false,
            previewBitmap = null,
            saveFromCenter = { error("should not run") },
        )

        assertTrue(decision is CenterAssistCaptureDecision.PreviewNotReady)
    }

    @Test
    fun capture_returns_failure_when_saver_returns_null_and_recycles_bitmap() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        val decision = CenterAssistFlow.capture(
            canUpdateUi = true,
            isProcessingViolation = false,
            previewBitmap = bitmap,
            saveFromCenter = { null },
        )

        assertTrue(decision is CenterAssistCaptureDecision.CaptureFailed)
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun capture_returns_failure_when_saver_throws() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        val decision = CenterAssistFlow.capture(
            canUpdateUi = true,
            isProcessingViolation = false,
            previewBitmap = bitmap,
            saveFromCenter = { throw IllegalStateException("boom") },
        )

        assertTrue(decision is CenterAssistCaptureDecision.CaptureFailed)
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun capture_returns_captured_result() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val crop = AssistedCropResult(
            path = "crop.jpg",
            normalizedRect = NormalizedRect(0.1f, 0.2f, 0.7f, 0.5f),
        )

        val decision = CenterAssistFlow.capture(
            canUpdateUi = true,
            isProcessingViolation = false,
            previewBitmap = bitmap,
            saveFromCenter = { crop },
        )

        assertEquals(crop, (decision as CenterAssistCaptureDecision.Captured).crop)
        assertTrue(bitmap.isRecycled)
    }
}
