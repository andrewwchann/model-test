package com.andre.alprprototype.alpr

import android.content.Context
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import java.util.ArrayDeque
import java.util.Locale
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

data class OcrCandidate(
    val text: String,
    val confidence: Float,
    val engine: String,
)

data class FinalPlateResult(
    val text: String,
    val confidence: Float,
    val supportingFrames: Int,
)

data class PipelineDebugState(
    val frameNumber: Long,
    val inputWidth: Int,
    val inputHeight: Int,
    val scanRan: Boolean,
    val candidateSource: String,
    val candidates: List<PlateCandidate>,
    val detections: List<PlateDetection>,
    val activeTrack: PlateTrack?,
    val quality: PlateQuality?,
    val bestCandidate: OcrCandidate?,
    val emittedResult: FinalPlateResult?,
    val detectorDebugText: String?,
) {
    fun statusText(): String {
        val lines = mutableListOf<String>()
        lines += "Frame $frameNumber | ${inputWidth}x$inputHeight"
        lines += if (scanRan) "Detector: ran via $candidateSource" else "Detector: skipped"
        lines += "Candidates: ${candidates.size} | Plate-like detections: ${detections.size}"
        candidates.firstOrNull()?.let {
            lines += "Top candidate: ${it.boundingBox.asDebugString()} ${it.boundingBox.sizeString()} conf=${format(it.confidence)}"
        } ?: run {
            lines += "Top candidate: none"
        }
        detectorDebugText?.let { lines += it }
        detections.firstOrNull()?.let {
            lines += "Top detection: ${it.boundingBox.asDebugString()} ${it.boundingBox.sizeString()} conf=${format(it.confidence)}"
        } ?: run {
            lines += "Top detection: none"
        }

        val trackText = activeTrack?.let {
            "Track: #${it.trackId} age=${it.ageFrames} src=${it.source} box=${it.boundingBox.asDebugString()} ${it.boundingBox.sizeString()}"
        } ?: "Track: none"
        lines += trackText

        val qualityText = quality?.let {
            val verdict = if (it.passes) "pass" else "reject"
            "Quality: $verdict score=${format(it.totalScore)} blur=${format(it.blurScore)} width=${format(it.pixelWidth)} angle=${format(it.angleScore)}"
        } ?: "Quality: waiting"
        lines += qualityText

        val reasonText = quality?.reasons?.takeIf { it.isNotEmpty() }?.joinToString() ?: "none"
        lines += "Reject reasons: $reasonText"

        val ocrText = bestCandidate?.let {
            "Best OCR: ${it.text} (${format(it.confidence)}) via ${it.engine}"
        } ?: "Best OCR: none"
        lines += ocrText

        val finalText = emittedResult?.let {
            "Final plate: ${it.text} (${format(it.confidence)}) votes=${it.supportingFrames}"
        } ?: "Final plate: not emitted"
        lines += finalText

        return lines.joinToString("\n")
    }
}

class AlprPipeline(
    private val candidateGenerator: PlateCandidateGenerator = StaticSceneCandidateGenerator(),
    private val candidateFilter: PlateCandidateFilter = PlateLikeCandidateFilter(),
    private val tracker: PlateTracker = SimplePlateTracker(),
    private val qualityScorer: PlateQualityScorer = HeuristicPlateQualityScorer(),
    private val ocrEngine: PlateOcrEngine = NoopPlateOcrEngine(),
    private val voteWindowSize: Int = 4,   // Speed: Smaller window for faster confirmation
    private val voteThreshold: Int = 2,    // Speed/Reliability: 2 matching frames for quick but verified result
    private val scanEveryNFrames: Int = 4, // Speed: Scan more frequently than 6
) : AutoCloseable {
    val detectorName: String
        get() = candidateGenerator.name

    private val voteWindow = ArrayDeque<OcrCandidate>()
    private var bestCandidate: OcrCandidate? = null
    private var bestQualityScore = 0f
    private var lastTrackId: Int? = null

    fun process(frameNumber: Long, image: ImageProxy): PipelineDebugState {
        // Speed: Allow frequent scans if we don't have an active track
        val hasTrack = tracker.hasActiveTrack()
        val effectiveScanInterval = if (hasTrack) scanEveryNFrames else max(1, scanEveryNFrames / 2)
        val runScan = ((frameNumber - 1L) % effectiveScanInterval.toLong()) == 0L
        
        val candidates = if (runScan) candidateGenerator.generate(image) else emptyList()
        val detections = if (runScan) candidateFilter.filter(candidates, image) else emptyList()
        val activeTrack = tracker.update(detections)
        
        if (activeTrack?.trackId != lastTrackId) {
            resetRecognitionState()
            lastTrackId = activeTrack?.trackId
        }
        
        val quality = activeTrack?.let { qualityScorer.score(image, it) }

        // Reliability: Only send to OCR if quality is high enough
        val acceptedCandidate = quality
            ?.takeIf { it.passes }
            ?.let { passingQuality -> ocrEngine.recognize(activeTrack, passingQuality) }

        if (acceptedCandidate != null && quality.totalScore >= bestQualityScore) {
            bestQualityScore = quality.totalScore
            bestCandidate = acceptedCandidate
        }

        if (acceptedCandidate != null) {
            voteWindow.addLast(acceptedCandidate)
            while (voteWindow.size > voteWindowSize) {
                voteWindow.removeFirst()
            }
        }

        val emittedResult = buildFinalResult()

        return PipelineDebugState(
            frameNumber = frameNumber,
            inputWidth = image.width,
            inputHeight = image.height,
            scanRan = runScan,
            candidateSource = candidateGenerator.name,
            candidates = candidates,
            detections = detections,
            activeTrack = activeTrack,
            quality = quality,
            bestCandidate = bestCandidate,
            emittedResult = emittedResult,
            detectorDebugText = candidateGenerator.lastDebugInfo(),
        )
    }

    private fun buildFinalResult(): FinalPlateResult? {
        if (voteWindow.isEmpty()) return null

        val votes = voteWindow.groupingBy { it.text }.eachCount()
        val winner = votes.maxByOrNull { it.value } ?: return null
        
        // Reliability Pillar: Use voting to ensure accuracy
        if (winner.value < voteThreshold) return null

        val confidences = voteWindow
            .filter { it.text == winner.key }
            .map { it.confidence }
        val avgConfidence = confidences.average().toFloat()

        return FinalPlateResult(
            text = winner.key,
            confidence = avgConfidence,
            supportingFrames = winner.value,
        )
    }

    override fun close() {
        candidateGenerator.close()
    }

    private fun resetRecognitionState() {
        voteWindow.clear()
        bestCandidate = null
        bestQualityScore = 0f
    }

    companion object {
        fun create(context: Context): AlprPipeline {
            val candidateGenerator = YoloTflitePlateCandidateGenerator.createOrNull(context)
                ?: StaticSceneCandidateGenerator()
            
            // Speed: Adaptive scan rates based on hardware capability
            val scanEveryNFrames = if (candidateGenerator is YoloTflitePlateCandidateGenerator) 2 else 4
            
            return AlprPipeline(
                candidateGenerator = candidateGenerator,
                scanEveryNFrames = scanEveryNFrames,
            )
        }
    }
}

interface PlateCandidateGenerator {
    val name: String
    fun generate(image: ImageProxy): List<PlateCandidate>
    fun close() = Unit
    fun lastDebugInfo(): String? = null
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

interface PlateOcrEngine {
    val engineName: String
    fun recognize(track: PlateTrack, quality: PlateQuality): OcrCandidate?
}

class StaticSceneCandidateGenerator : PlateCandidateGenerator {
    override val name: String = "static-region-proposals"

    override fun generate(image: ImageProxy): List<PlateCandidate> {
        val plane = image.planes.firstOrNull() ?: return emptyList()
        val frameWidth = image.width
        val frameHeight = image.height
        val data = plane.buffer.duplicate().apply { rewind() }
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (frameWidth <= 0 || frameHeight <= 0) return emptyList()

        // Speed: Narrow the search area to the "sweet spot" (middle-bottom where plates usually are)
        val searchTop = (frameHeight * 0.40f).toInt()
        val searchBottom = (frameHeight * 0.80f).toInt()
        val windowWidth = max(48, (frameWidth * 0.22f).toInt())
        val windowHeight = max(18, (windowWidth / 4.4f).toInt())
        
        // Speed: Larger steps to cover area faster with fewer samples
        val stepX = max(24, windowWidth / 2) 
        val stepY = max(16, windowHeight / 2)

        val proposals = mutableListOf<PlateCandidate>()

        var top = searchTop
        while (top + windowHeight < searchBottom) {
            var left = (frameWidth * 0.1f).toInt() // Skip edges for speed
            val rightLimit = (frameWidth * 0.9f).toInt()
            while (left + windowWidth < rightLimit) {
                val stats = sampleWindowStats(
                    data = data,
                    rowStride = rowStride,
                    pixelStride = pixelStride,
                    left = left,
                    top = top,
                    width = windowWidth,
                    height = windowHeight,
                )

                // Reliability: Tighter thresholds to reject noise early
                val edgePass = stats.horizontalEdgeDensity in 0.20f..0.75f
                val contrastPass = stats.contrast in 25f..105f
                val stripePass = stats.verticalStripeBalance in 0.20f..0.80f
                val centerBias = centeredness(left.toFloat(), (left + windowWidth).toFloat(), frameWidth.toFloat())

                if (edgePass && contrastPass && stripePass) {
                    val confidence = (
                        normalized(stats.horizontalEdgeDensity, 0.20f, 0.55f) * 0.45f +
                        normalized(stats.contrast, 25f, 80f) * 0.30f +
                        normalized(1f - abs(stats.verticalStripeBalance - 0.5f), 0.2f, 0.5f) * 0.15f +
                        centerBias * 0.10f
                    ).coerceIn(0f, 0.99f)

                    proposals += PlateCandidate(
                        boundingBox = RectF(
                            left.toFloat(),
                            top.toFloat(),
                            (left + windowWidth).toFloat(),
                            (top + windowHeight).toFloat(),
                        ),
                        confidence = confidence,
                        source = "luma-grid",
                    )
                }

                left += stepX
            }
            top += stepY
        }

        return proposals
            .sortedByDescending { it.confidence }
            .fold(mutableListOf<PlateCandidate>()) { accepted, candidate ->
                if (accepted.none { overlaps(it.boundingBox, candidate.boundingBox) > 0.40f }) {
                    accepted += candidate
                }
                accepted
            }
            .take(3) // Speed: Only look at top 3 candidates
    }

    private fun sampleWindowStats(
        data: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): WindowStats {
        var sum = 0f
        var sumSquares = 0f
        var edgeHits = 0
        var totalSamples = 0
        var darkPixels = 0
        var brightPixels = 0

        // Speed Pillar: Sparse sampling. We don't need every pixel for stats.
        val sampleStepX = max(4, width / 12)
        val sampleStepY = max(3, height / 6)

        var y = top
        while (y < top + height - sampleStepY) {
            var x = left
            while (x < left + width - sampleStepX) {
                val value = yValueAt(data, rowStride, pixelStride, x, y)
                val right = yValueAt(data, rowStride, pixelStride, x + sampleStepX, y)
                val down = yValueAt(data, rowStride, pixelStride, x, y + sampleStepY)
                val diffX = abs(value - right)
                val diffY = abs(value - down)

                sum += value
                sumSquares += value * value
                if (diffX > 20f || diffY > 20f) edgeHits += 1
                if (value < 90f) darkPixels += 1
                if (value > 160f) brightPixels += 1
                totalSamples += 1
                x += sampleStepX
            }
            y += sampleStepY
        }

        if (totalSamples == 0) return WindowStats(0f, 0f, 0f)

        val mean = sum / totalSamples
        val variance = max(0f, (sumSquares / totalSamples) - mean * mean)
        val contrast = kotlin.math.sqrt(variance)
        val edgeDensity = edgeHits.toFloat() / totalSamples.toFloat()
        val stripeBalance = min(darkPixels, brightPixels).toFloat() / max(darkPixels + brightPixels, 1).toFloat()

        return WindowStats(
            horizontalEdgeDensity = edgeDensity,
            contrast = contrast,
            verticalStripeBalance = stripeBalance,
        )
    }

    private fun yValueAt(data: java.nio.ByteBuffer, rowStride: Int, pixelStride: Int, x: Int, y: Int): Float {
        val index = y * rowStride + x * pixelStride
        return (data.get(index).toInt() and 0xFF).toFloat()
    }

    private fun centeredness(left: Float, right: Float, frameWidth: Float): Float {
        val centerX = (left + right) / 2f
        val normalizedOffset = abs(centerX - frameWidth / 2f) / (frameWidth / 2f)
        return 1f - normalizedOffset.coerceIn(0f, 1f)
    }
}

class PlateLikeCandidateFilter : PlateCandidateFilter {
    override fun filter(candidates: List<PlateCandidate>, image: ImageProxy): List<PlateDetection> {
        val minWidth = image.width * 0.10f
        return candidates.mapNotNull { candidate ->
            val width = candidate.boundingBox.width()
            val height = candidate.boundingBox.height()
            val aspectRatio = width / max(height, 1f)
            val centeredness = centeredness(candidate.boundingBox, image.width.toFloat())

            if (candidate.source == "yolo-tflite") {
                val yoloMinWidth = image.width * 0.07f
                val aspectPass = aspectRatio in 1.4f..8.0f
                if (width < yoloMinWidth || !aspectPass) null
                else PlateDetection(candidate.boundingBox, candidate.confidence, candidate.source)
            } else {
                // Reliability: Stricter aspect ratios for plates (usually ~4:1)
                val aspectPass = aspectRatio in 2.2f..6.8f
                val sizePass = width >= minWidth
                val centeredPass = centeredness >= 0.25f

                if (!aspectPass || !sizePass || !centeredPass) null
                else PlateDetection(candidate.boundingBox, candidate.confidence, candidate.source)
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
        private const val MAX_MISSED_DETECTIONS = 3 // Reliability: Keep track alive slightly longer
    }
}

class HeuristicPlateQualityScorer : PlateQualityScorer {
    override fun score(image: ImageProxy, track: PlateTrack): PlateQuality {
        val plateWidth = track.boundingBox.width()
        val plateHeight = track.boundingBox.height()
        val minRequiredWidth = if (track.source == "yolo-tflite") image.width * 0.07f else image.width * 0.14f
        val blurTargetWidth = if (track.source == "yolo-tflite") image.width * 0.15f else image.width * 0.25f
        val blurScore = min(1f, plateWidth / blurTargetWidth)
        val targetAspect = 4.4f
        val actualAspect = plateWidth / max(plateHeight, 1f)
        val angleScore = max(0f, 1f - abs(actualAspect - targetAspect) / targetAspect)

        val reasons = mutableListOf<String>()
        if (plateWidth < minRequiredWidth) reasons += "tiny-box"
        // Reliability: Only reject if severely blurred or wrong shape
        if (blurScore < 0.25f) reasons += "blur"
        if (angleScore < 0.30f) reasons += "aspect-angle"

        val totalScore = (blurScore * 0.40f) + ((plateWidth / image.width) * 0.40f) + (angleScore * 0.20f)

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

class PaddleOcrEngine : PlateOcrEngine {
    override val engineName: String = "PaddleOCR placeholder"

    override fun recognize(track: PlateTrack, quality: PlateQuality): OcrCandidate {
        val text = if (track.ageFrames % 5 == 0) "ABC128" else "ABC123"
        val confidence = min(0.98f, 0.52f + quality.totalScore)
        return OcrCandidate(text = text, confidence = confidence, engine = engineName)
    }
}

class NoopPlateOcrEngine : PlateOcrEngine {
    override val engineName: String = "external-ocr"
    override fun recognize(track: PlateTrack, quality: PlateQuality): OcrCandidate? = null
}

private fun normalized(value: Float, minValue: Float, maxValue: Float): Float {
    if (maxValue <= minValue) return 0f
    return ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
}

private data class WindowStats(
    val horizontalEdgeDensity: Float,
    val contrast: Float,
    val verticalStripeBalance: Float,
)

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

private fun RectF.asDebugString(): String = String.format(Locale.US, "[%.0f, %.0f, %.0f, %.0f]", left, top, right, bottom)

private fun RectF.sizeString(): String = String.format(Locale.US, "(%.0fx%.0f)", width(), height())

private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)
