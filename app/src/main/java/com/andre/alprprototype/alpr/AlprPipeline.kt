package com.andre.alprprototype.alpr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PlateCandidate(
    val boundingBox: RectF,
    val confidence: Float,
    val source: String,
)

data class PlateDetection(
    val boundingBox: RectF,
    val confidence: Float,
    val source: String,
)

data class PlateTrack(
    val trackId: Int,
    val boundingBox: RectF,
    val ageFrames: Int,
    val source: String,
)

data class PlateQuality(
    val blurScore: Float,
    val pixelWidth: Float,
    val angleScore: Float,
    val passes: Boolean,
    val reasons: List<String>,
    val totalScore: Float,
)

data class PipelineDebugState(
    val frameNumber: Long,
    val inputWidth: Int,
    val inputHeight: Int,
    val candidates: List<PlateCandidate>,
    val detections: List<PlateDetection>,
    val activeTrack: PlateTrack?,
    val quality: PlateQuality?,
)

class AlprPipeline(
    private val candidateGenerator: PlateCandidateGenerator,
    private val candidateFilter: PlateCandidateFilter = PlateLikeCandidateFilter(),
    private val tracker: PlateTracker = SimplePlateTracker(),
    private val qualityScorer: PlateQualityScorer = LivePlateQualityScorer(),
    private val scanEveryNFrames: Int = 4,
) : AutoCloseable {
    private var lastTrackId: Int? = null

    fun process(
        frameNumber: Long,
        image: ImageProxy,
        uprightBitmapProvider: (() -> Bitmap?)? = null,
    ): PipelineDebugState {
        val (displayWidth, displayHeight) = uprightFrameSize(image)
        // Speed: Allow frequent scans if we don't have an active track
        val hasTrack = tracker.hasActiveTrack()
        val effectiveScanInterval = if (hasTrack) scanEveryNFrames else max(1, scanEveryNFrames / 2)
        val runScan = ((frameNumber - 1L) % effectiveScanInterval.toLong()) == 0L
        
        val candidates = if (runScan) candidateGenerator.generate(image, uprightBitmapProvider) else emptyList()
        val detections = if (runScan) candidateFilter.filter(candidates, image) else emptyList()
        val activeTrack = tracker.update(detections)
        
        if (activeTrack?.trackId != lastTrackId) {
            resetRecognitionState()
            lastTrackId = activeTrack?.trackId
        }
        
        val quality = activeTrack?.let { qualityScorer.score(image, it) }

        return PipelineDebugState(
            frameNumber = frameNumber,
            inputWidth = displayWidth,
            inputHeight = displayHeight,
            candidates = candidates,
            detections = detections,
            activeTrack = activeTrack,
            quality = quality,
        )
    }

    override fun close() {
        candidateGenerator.close()
    }

    private fun resetRecognitionState() {
        // The live OCR flow runs on saved crops in CameraActivity, so the frame pipeline
        // only needs to reset track-local state here.
    }

    companion object {
        fun create(context: Context): AlprPipeline {
            val candidateGenerator = YoloTflitePlateCandidateGenerator.createOrNull(context)
                ?: throw IllegalStateException("Required detector model could not be loaded")
            
            return AlprPipeline(
                candidateGenerator = candidateGenerator,
                scanEveryNFrames = 4,
            )
        }
    }
}

interface PlateCandidateGenerator {
    val name: String
    fun generate(image: ImageProxy, uprightBitmapProvider: (() -> Bitmap?)? = null): List<PlateCandidate>
    fun close() = Unit
}

interface PlateCandidateFilter {
    fun filter(candidates: List<PlateCandidate>, image: ImageProxy): List<PlateDetection>
}

interface PlateTracker {
    fun update(detections: List<PlateDetection>): PlateTrack?
    fun hasActiveTrack(): Boolean
}

interface PlateQualityScorer {
    fun score(image: ImageProxy, track: PlateTrack): PlateQuality
}

class PlateLikeCandidateFilter : PlateCandidateFilter {
    override fun filter(candidates: List<PlateCandidate>, image: ImageProxy): List<PlateDetection> {
        val uprightFrameWidth = uprightFrameSize(image).first.toFloat()
        return candidates.mapNotNull { candidate ->
            val width = candidate.boundingBox.width()
            val height = candidate.boundingBox.height()
            val aspectRatio = width / max(height, 1f)
            val centeredness = centeredness(candidate.boundingBox, uprightFrameWidth)
            val minWidth = uprightFrameWidth * 0.07f
            val aspectPass = aspectRatio in 1.4f..8.0f
            val centeredPass = centeredness >= 0.01f
            if (width < minWidth || !aspectPass || !centeredPass) {
                null
            } else {
                PlateDetection(
                    boundingBox = candidate.boundingBox,
                    confidence = min(0.99f, candidate.confidence * 0.9f + 0.09f),
                    source = candidate.source,
                )
            }
        }
    }

    private fun centeredness(box: RectF, frameWidth: Float): Float {
        val boxCenterX = (box.left + box.right) / 2f
        val normalizedOffset = abs(boxCenterX - frameWidth / 2f) / (frameWidth / 2f)
        return 1f - min(1f, normalizedOffset)
    }
}

class SimplePlateTracker : PlateTracker {
    private var activeTrack: PlateTrack? = null
    private var nextTrackId = 1
    private var missedDetections = 0

    override fun hasActiveTrack(): Boolean = activeTrack != null

    override fun update(detections: List<PlateDetection>): PlateTrack? {
        val best = detections.maxByOrNull { it.confidence }
        activeTrack = if (best == null) {
            missedDetections += 1
            if (missedDetections > MAX_MISSED_DETECTIONS) null else activeTrack
        } else {
            missedDetections = 0
            val previousTrack = activeTrack
            val continuesTrack = previousTrack != null &&
                previousTrack.source == best.source &&
                overlaps(previousTrack.boundingBox, best.boundingBox) >= IOU_CONTINUITY_THRESHOLD
            
            if (continuesTrack) {
                previousTrack!!.copy(
                    boundingBox = best.boundingBox,
                    ageFrames = previousTrack.ageFrames + 1,
                    source = best.source,
                )
            } else {
                PlateTrack(
                    trackId = nextTrackId++,
                    boundingBox = best.boundingBox,
                    ageFrames = 1,
                    source = best.source,
                )
            }
        }
        return activeTrack
    }

    companion object {
        private const val IOU_CONTINUITY_THRESHOLD = 0.25f
        private const val MAX_MISSED_DETECTIONS = 3
    }
}

class LivePlateQualityScorer : PlateQualityScorer {
    override fun score(image: ImageProxy, track: PlateTrack): PlateQuality {
        val plateWidth = track.boundingBox.width()
        val plateHeight = track.boundingBox.height()
        val uprightFrameWidth = uprightFrameSize(image).first.toFloat()
        val frameWidth = uprightFrameWidth
        val minRequiredWidth = frameWidth * 0.08f
        val blurTargetWidth = frameWidth * 0.18f
        val blurScore = min(1f, plateWidth / blurTargetWidth)
        val targetAspect = 4.4f
        val actualAspect = plateWidth / max(plateHeight, 1f)
        val angleScore = max(0f, 1f - abs(actualAspect - targetAspect) / targetAspect)

        val reasons = mutableListOf<String>()
        if (plateWidth < minRequiredWidth) {
            reasons += "tiny-box"
        }
        if (blurScore < 0.30f) {
            reasons += "blur"
        }
        if (angleScore < 0.35f) {
            reasons += "aspect-angle"
        }

        val totalScore = (blurScore * 0.45f) + ((plateWidth / frameWidth) * 0.35f) + (angleScore * 0.20f)

        return PlateQuality(
            blurScore = blurScore,
            pixelWidth = plateWidth,
            angleScore = angleScore,
            passes = reasons.isEmpty(),
            reasons = reasons,
            totalScore = totalScore,
        )
    }
}

private fun overlaps(a: RectF, b: RectF): Float {
    val left = max(a.left, b.left)
    val top = max(a.top, b.top)
    val right = min(a.right, b.right)
    val bottom = min(a.bottom, b.bottom)
    if (right <= left || bottom <= top) return 0f

    val intersection = (right - left) * (bottom - top)
    val union = a.width() * a.height() + b.width() * b.height() - intersection
    return if (union <= 0f) 0f else intersection / union
}

private fun uprightFrameSize(image: ImageProxy): Pair<Int, Int> {
    val normalizedRotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
    return if (normalizedRotation == 90 || normalizedRotation == 270) {
        image.height to image.width
    } else {
        image.width to image.height
    }
}

