package com.andre.alprprototype.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.andre.alprprototype.alpr.NormalizedRect
import com.andre.alprprototype.alpr.PipelineDebugState
import com.andre.alprprototype.alpr.PlateCandidate
import com.andre.alprprototype.alpr.PlateDetection
import com.andre.alprprototype.alpr.PlateQuality
import com.andre.alprprototype.alpr.PlateTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlateDebugOverlayViewTest {
    @Test
    fun render_and_clearDebugState_update_current_state() {
        val view = PlateDebugOverlayView(RuntimeEnvironment.getApplication())
        val state = debugState(inputWidth = 1280, inputHeight = 720)

        assertNull(view.currentStateOrNull())

        view.render(state)

        assertEquals(state, view.currentStateOrNull())

        view.clearDebugState()

        assertNull(view.currentStateOrNull())
    }

    @Test
    fun onDraw_handles_missing_and_invalid_state_without_crashing() {
        val view = PlateDebugOverlayView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 1000, 600)
        val canvas = Canvas(Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888))

        view.draw(canvas)

        view.render(debugState(inputWidth = 0, inputHeight = 0))
        view.draw(canvas)
    }

    @Test
    fun onDraw_handles_candidates_detections_active_track_and_assisted_target() {
        val view = PlateDebugOverlayView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 1000, 600)
        view.render(debugState(inputWidth = 1280, inputHeight = 720))
        view.showAssistedTarget(NormalizedRect(0.2f, 0.3f, 0.6f, 0.5f))
        val canvas = Canvas(Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888))

        view.draw(canvas)

        assertNotNull(view.currentStateOrNull())
    }

    @Test
    fun onTouchEvent_delegates_to_listener_when_present() {
        val view = PlateDebugOverlayView(RuntimeEnvironment.getApplication())
        var tappedX = -1f
        var tappedY = -1f
        view.setOnTapTargetRequested { x, y ->
            tappedX = x
            tappedY = y
        }

        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 12f, 18f, 0)
        val up = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, 12f, 18f, 0)

        assertTrue(view.onTouchEvent(down))
        assertTrue(view.onTouchEvent(up))
        assertEquals(12f, tappedX, 0.001f)
        assertEquals(18f, tappedY, 0.001f)
    }

    @Test
    fun onTouchEvent_without_listener_falls_back_to_super() {
        val view = PlateDebugOverlayView(RuntimeEnvironment.getApplication())
        val move = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 3f, 4f, 0)

        assertFalse(view.onTouchEvent(move))
    }

    private fun debugState(inputWidth: Int, inputHeight: Int): PipelineDebugState {
        return PipelineDebugState(
            frameNumber = 5L,
            inputWidth = inputWidth,
            inputHeight = inputHeight,
            candidates = listOf(PlateCandidate(RectF(100f, 120f, 260f, 180f), 0.8f, "yolo-tflite")),
            detections = listOf(PlateDetection(RectF(110f, 125f, 255f, 178f), 0.88f, "yolo-tflite")),
            activeTrack = PlateTrack(1, RectF(110f, 125f, 255f, 178f), 4, "yolo-tflite"),
            quality = PlateQuality(
                blurScore = 0.8f,
                pixelWidth = 145f,
                angleScore = 0.9f,
                passes = true,
                reasons = emptyList(),
                totalScore = 0.85f,
            ),
        )
    }
}
