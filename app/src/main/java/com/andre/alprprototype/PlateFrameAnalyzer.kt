package com.andre.alprprototype

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.andre.alprprototype.alpr.AlprPipeline
import com.andre.alprprototype.alpr.BestPlateCropSaver
import com.andre.alprprototype.alpr.PipelineDebugState
import com.andre.alprprototype.alpr.toUprightBitmap
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
    override fun analyze(image: ImageProxy) {
        try {
            if (!shouldAnalyze()) {
                return
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
                    "frame=$currentFrame scan=${state.scanRan} convertMs=${convertDurationMs ?: 0} " +
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
}
