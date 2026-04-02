package com.andre.alprprototype

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.andre.alprprototype.alpr.AlprPipeline
import com.andre.alprprototype.alpr.BestPlateCropSaver
import com.andre.alprprototype.alpr.PipelineDebugState
import com.andre.alprprototype.alpr.toUprightBitmap
import kotlin.math.abs
import java.util.concurrent.atomic.AtomicLong

data class AnalyzedFrame(
    val state: PipelineDebugState,
    val savedCropPath: String?,
)

class PlateFrameAnalyzer(
    private val pipeline: AlprPipeline = AlprPipeline(),
    private val cropSaver: BestPlateCropSaver,
    private val shouldAnalyze: () -> Boolean = { true },
    private val onFrameAnalyzed: (AnalyzedFrame) -> Unit,
) : ImageAnalysis.Analyzer {

    private val frameCounter = AtomicLong(0)
    private var previousMotionSignature: IntArray? = null
    private var stableFrameCount: Int = 0
    private var motionPauseActive: Boolean = false

    override fun analyze(image: ImageProxy) {
        try {
            if (!shouldAnalyze()) {
                return
            }
            val motionSignature = buildMotionSignature(image)
            if (motionSignature != null) {
                val motionScore = previousMotionSignature?.let { computeMotionScore(it, motionSignature) } ?: 0f
                previousMotionSignature = motionSignature
                val stableNow = motionScore <= MOTION_STABLE_THRESHOLD
                stableFrameCount = if (stableNow) stableFrameCount + 1 else 0
                if (!stableNow && motionScore >= MOTION_PAUSE_THRESHOLD) {
                    motionPauseActive = true
                } else if (motionPauseActive && stableFrameCount >= STABLE_FRAMES_TO_RESUME) {
                    motionPauseActive = false
                }
                if (motionPauseActive) {
                    return
                }
            }
            val currentFrame = frameCounter.incrementAndGet()
            var convertDurationMs: Long? = null
            val uprightBitmap by lazy(LazyThreadSafetyMode.NONE) {
                val startNs = System.nanoTime()
                image.toUprightBitmap().also {
                    convertDurationMs = nanosToMillis(System.nanoTime() - startNs)
                }
            }
            val bitmapProvider = { uprightBitmap }
            val pipelineStartNs = System.nanoTime()
            val state = pipeline.process(currentFrame, image, bitmapProvider)
            val pipelineDurationMs = nanosToMillis(System.nanoTime() - pipelineStartNs)
            val cropStartNs = System.nanoTime()
            val savedCropPath = cropSaver.saveIfBest(image, state, bitmapProvider)
            val cropDurationMs = nanosToMillis(System.nanoTime() - cropStartNs)
            if (BuildConfig.ALPR_PERF_LOGS_ENABLED) {
                val totalDurationMs = (convertDurationMs ?: 0L) + pipelineDurationMs + cropDurationMs
                Log.d(
                    "ALPR_PERF",
                    "frame=$currentFrame convertMs=${convertDurationMs ?: 0} " +
                        "pipelineMs=$pipelineDurationMs cropMs=$cropDurationMs saved=${savedCropPath != null} " +
                        "track=${state.activeTrack?.trackId ?: 0} totalMs=$totalDurationMs",
                )
            }
            onFrameAnalyzed(AnalyzedFrame(state = state, savedCropPath = savedCropPath))
        } catch (_: Throwable) {
            // Keep CameraX alive even if OCR/crop plumbing throws for a frame.
        } finally {
            image.close()
        }
    }

    private fun buildMotionSignature(image: ImageProxy): IntArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) {
            return null
        }

        val signature = IntArray(MOTION_GRID_ROWS * MOTION_GRID_COLS)
        for (gridY in 0 until MOTION_GRID_ROWS) {
            val sampleY = (((gridY + 0.5f) / MOTION_GRID_ROWS) * height).toInt().coerceIn(0, height - 1)
            for (gridX in 0 until MOTION_GRID_COLS) {
                val sampleX = (((gridX + 0.5f) / MOTION_GRID_COLS) * width).toInt().coerceIn(0, width - 1)
                val index = sampleY * rowStride + sampleX * pixelStride
                if (index < 0 || index >= buffer.limit()) {
                    return null
                }
                signature[gridY * MOTION_GRID_COLS + gridX] = buffer.get(index).toInt() and 0xFF
            }
        }
        return signature
    }

    private fun computeMotionScore(previous: IntArray, current: IntArray): Float {
        if (previous.size != current.size || previous.isEmpty()) {
            return Float.MAX_VALUE
        }
        var totalDelta = 0f
        for (index in previous.indices) {
            totalDelta += abs(previous[index] - current[index]).toFloat()
        }
        return totalDelta / previous.size
    }

    companion object {
        private const val MOTION_GRID_ROWS = 6
        private const val MOTION_GRID_COLS = 6
        private const val MOTION_STABLE_THRESHOLD = 9f
        private const val MOTION_PAUSE_THRESHOLD = 18f
        private const val STABLE_FRAMES_TO_RESUME = 4
    }
}
