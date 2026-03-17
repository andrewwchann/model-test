package com.andre.alprprototype

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.andre.alprprototype.alpr.AlprPipeline
import com.andre.alprprototype.alpr.PipelineDebugState
import com.andre.alprprototype.alpr.BestPlateCropSaver
import java.util.concurrent.atomic.AtomicLong

data class AnalyzedFrame(
    val state: PipelineDebugState,
    val savedCropPath: String?,
)

class PlateFrameAnalyzer(
    private val pipeline: AlprPipeline = AlprPipeline(),
    private val cropSaver: BestPlateCropSaver,
    private val onFrameAnalyzed: (AnalyzedFrame) -> Unit,
) : ImageAnalysis.Analyzer {

    private val frameCounter = AtomicLong(0)

    override fun analyze(image: ImageProxy) {
        try {
            val currentFrame = frameCounter.incrementAndGet()
            val state = pipeline.process(currentFrame, image)
            val savedCropPath = cropSaver.saveIfBest(image, state)
            onFrameAnalyzed(AnalyzedFrame(state = state, savedCropPath = savedCropPath))
        } catch (_: Throwable) {
            // Keep CameraX alive even if OCR/crop plumbing throws for a frame.
        } finally {
            image.close()
        }
    }
}
