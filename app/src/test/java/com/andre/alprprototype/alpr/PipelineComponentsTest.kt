package com.andre.alprprototype.alpr

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PipelineComponentsTest {
    @Test
    fun plateLikeCandidateFilter_rejects_small_candidate() {
        val filter = PlateLikeCandidateFilter()

        val detections = filter.filter(
            candidates = listOf(PlateCandidate(RectF(10f, 10f, 50f, 25f), 0.8f, "yolo-tflite")),
            image = imageProxy(width = 1000, height = 600, rotationDegrees = 0),
        )

        assertTrue(detections.isEmpty())
    }

    @Test
    fun plateLikeCandidateFilter_rejects_bad_aspect_ratio() {
        val filter = PlateLikeCandidateFilter()

        val detections = filter.filter(
            candidates = listOf(PlateCandidate(RectF(100f, 100f, 220f, 220f), 0.8f, "yolo-tflite")),
            image = imageProxy(width = 1000, height = 600, rotationDegrees = 0),
        )

        assertTrue(detections.isEmpty())
    }

    @Test
    fun plateLikeCandidateFilter_accepts_good_candidate_and_boosts_confidence() {
        val filter = PlateLikeCandidateFilter()

        val detections = filter.filter(
            candidates = listOf(PlateCandidate(RectF(350f, 220f, 550f, 280f), 0.8f, "yolo-tflite")),
            image = imageProxy(width = 1000, height = 600, rotationDegrees = 0),
        )

        assertEquals(1, detections.size)
        assertEquals(0.81f, detections.first().confidence, 0.0001f)
    }

    @Test
    fun simplePlateTracker_returns_null_after_too_many_misses() {
        val tracker = SimplePlateTracker()
        val detection = PlateDetection(RectF(10f, 10f, 110f, 40f), 0.9f, "yolo-tflite")

        assertEquals(1, tracker.update(listOf(detection))?.trackId)
        assertTrue(tracker.hasActiveTrack())
        tracker.update(emptyList())
        tracker.update(emptyList())
        tracker.update(emptyList())
        assertNull(tracker.update(emptyList()))
        assertFalse(tracker.hasActiveTrack())
    }

    @Test
    fun simplePlateTracker_continues_track_when_overlap_and_source_match() {
        val tracker = SimplePlateTracker()

        val first = tracker.update(listOf(PlateDetection(RectF(10f, 10f, 110f, 40f), 0.9f, "yolo-tflite")))!!
        val second = tracker.update(listOf(PlateDetection(RectF(12f, 11f, 108f, 39f), 0.8f, "yolo-tflite")))!!

        assertEquals(first.trackId, second.trackId)
        assertEquals(2, second.ageFrames)
    }

    @Test
    fun simplePlateTracker_starts_new_track_when_source_changes() {
        val tracker = SimplePlateTracker()

        val first = tracker.update(listOf(PlateDetection(RectF(10f, 10f, 110f, 40f), 0.9f, "yolo-tflite")))!!
        val second = tracker.update(listOf(PlateDetection(RectF(15f, 12f, 115f, 42f), 0.8f, "other-source")))!!

        assertEquals(first.trackId + 1, second.trackId)
        assertEquals(1, second.ageFrames)
    }

    @Test
    fun livePlateQualityScorer_returns_pass_for_good_track() {
        val scorer = LivePlateQualityScorer()

        val quality = scorer.score(
            image = imageProxy(width = 1000, height = 600, rotationDegrees = 0),
            track = PlateTrack(1, RectF(350f, 220f, 550f, 265f), 1, "yolo-tflite"),
        )

        assertTrue(quality.passes)
        assertTrue(quality.reasons.isEmpty())
    }

    @Test
    fun livePlateQualityScorer_collects_all_failure_reasons() {
        val scorer = LivePlateQualityScorer()

        val quality = scorer.score(
            image = imageProxy(width = 1000, height = 600, rotationDegrees = 0),
            track = PlateTrack(1, RectF(350f, 220f, 390f, 280f), 1, "yolo-tflite"),
        )

        assertFalse(quality.passes)
        assertTrue(quality.reasons.contains("tiny-box"))
        assertTrue(quality.reasons.contains("blur"))
        assertTrue(quality.reasons.contains("aspect-angle"))
    }

    @Test
    fun alprPipeline_skips_scan_on_non_scan_frame_without_track() {
        var generateCalls = 0
        var filterCalls = 0
        val pipeline = AlprPipeline(
            candidateGenerator = object : PlateCandidateGenerator {
                override val name: String = "test"
                override fun generate(
                    image: ImageProxy,
                    uprightBitmapProvider: (() -> android.graphics.Bitmap?)?,
                ): List<PlateCandidate> {
                    generateCalls += 1
                    return listOf(PlateCandidate(RectF(0f, 0f, 100f, 30f), 0.9f, "test"))
                }
            },
            candidateFilter = object : PlateCandidateFilter {
                override fun filter(candidates: List<PlateCandidate>, image: ImageProxy): List<PlateDetection> {
                    filterCalls += 1
                    return emptyList()
                }
            },
            tracker = object : PlateTracker {
                override fun update(detections: List<PlateDetection>): PlateTrack? = null
                override fun hasActiveTrack(): Boolean = false
            },
            qualityScorer = object : PlateQualityScorer {
                override fun score(image: ImageProxy, track: PlateTrack): PlateQuality {
                    error("should not be called")
                }
            },
            scanEveryNFrames = 4,
        )

        val state = pipeline.process(2L, imageProxy(width = 1000, height = 600, rotationDegrees = 0))

        assertTrue(state.candidates.isEmpty())
        assertTrue(state.detections.isEmpty())
        assertEquals(0, generateCalls)
        assertEquals(0, filterCalls)
    }

    @Test
    fun alprPipeline_skips_scan_on_non_scan_frame_with_active_track() {
        var generateCalls = 0
        val pipeline = AlprPipeline(
            candidateGenerator = object : PlateCandidateGenerator {
                override val name: String = "test"
                override fun generate(
                    image: ImageProxy,
                    uprightBitmapProvider: (() -> android.graphics.Bitmap?)?,
                ): List<PlateCandidate> {
                    generateCalls += 1
                    return emptyList()
                }
            },
            candidateFilter = object : PlateCandidateFilter {
                override fun filter(candidates: List<PlateCandidate>, image: ImageProxy): List<PlateDetection> = emptyList()
            },
            tracker = object : PlateTracker {
                override fun update(detections: List<PlateDetection>): PlateTrack? {
                    return PlateTrack(1, RectF(10f, 10f, 110f, 40f), 3, "yolo-tflite")
                }

                override fun hasActiveTrack(): Boolean = true
            },
            qualityScorer = object : PlateQualityScorer {
                override fun score(image: ImageProxy, track: PlateTrack): PlateQuality {
                    return PlateQuality(1f, 100f, 1f, true, emptyList(), 1f)
                }
            },
            scanEveryNFrames = 4,
        )

        val state = pipeline.process(2L, imageProxy(width = 1000, height = 600, rotationDegrees = 0))

        assertTrue(state.candidates.isEmpty())
        assertTrue(state.detections.isEmpty())
        assertEquals(0, generateCalls)
        assertEquals(1, state.activeTrack?.trackId)
        assertEquals(true, state.quality?.passes)
    }

    @Test
    fun alprPipeline_runs_scan_and_scores_quality_when_track_present() {
        var generateCalls = 0
        var filterCalls = 0
        var scoreCalls = 0
        val pipeline = AlprPipeline(
            candidateGenerator = object : PlateCandidateGenerator {
                override val name: String = "test"
                override fun generate(
                    image: ImageProxy,
                    uprightBitmapProvider: (() -> android.graphics.Bitmap?)?,
                ): List<PlateCandidate> {
                    generateCalls += 1
                    return listOf(PlateCandidate(RectF(350f, 220f, 550f, 280f), 0.9f, "test"))
                }
            },
            candidateFilter = object : PlateCandidateFilter {
                override fun filter(candidates: List<PlateCandidate>, image: ImageProxy): List<PlateDetection> {
                    filterCalls += 1
                    return listOf(PlateDetection(RectF(350f, 220f, 550f, 280f), 0.92f, "test"))
                }
            },
            tracker = object : PlateTracker {
                override fun update(detections: List<PlateDetection>): PlateTrack? {
                    return PlateTrack(7, detections.first().boundingBox, 4, "test")
                }

                override fun hasActiveTrack(): Boolean = false
            },
            qualityScorer = object : PlateQualityScorer {
                override fun score(image: ImageProxy, track: PlateTrack): PlateQuality {
                    scoreCalls += 1
                    return PlateQuality(0.8f, 200f, 0.9f, true, emptyList(), 0.85f)
                }
            },
            scanEveryNFrames = 4,
        )

        val state = pipeline.process(1L, imageProxy(width = 1000, height = 600, rotationDegrees = 0))

        assertEquals(1, generateCalls)
        assertEquals(1, filterCalls)
        assertEquals(1, scoreCalls)
        assertEquals(1, state.candidates.size)
        assertEquals(1, state.detections.size)
        assertEquals(7, state.activeTrack?.trackId)
        assertTrue(state.quality?.passes == true)
    }

    @Test
    fun alprPipeline_close_delegates_to_generator() {
        var closeCalls = 0
        val pipeline = AlprPipeline(
            candidateGenerator = object : PlateCandidateGenerator {
                override val name: String = "test"
                override fun generate(
                    image: ImageProxy,
                    uprightBitmapProvider: (() -> android.graphics.Bitmap?)?,
                ): List<PlateCandidate> = emptyList()

                override fun close() {
                    closeCalls += 1
                }
            },
        )

        pipeline.close()

        assertEquals(1, closeCalls)
    }

    private fun imageProxy(width: Int, height: Int, rotationDegrees: Int): ImageProxy {
        return FakeImageProxy(width, height, rotationDegrees)
    }

    private class FakeImageProxy(
        private val proxyWidth: Int,
        private val proxyHeight: Int,
        private val rotationDegreesValue: Int,
    ) : ImageProxy {
        private var cropRect: Rect = Rect(0, 0, proxyWidth, proxyHeight)

        override fun getWidth(): Int = proxyWidth

        override fun getHeight(): Int = proxyHeight

        override fun getFormat(): Int = 0

        override fun getCropRect(): Rect = cropRect

        override fun setCropRect(rect: Rect?) {
            cropRect = rect ?: Rect(0, 0, proxyWidth, proxyHeight)
        }

        override fun getImage(): Image? = null

        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = emptyArray()

        override fun getImageInfo(): ImageInfo = object : ImageInfo {
            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()

            override fun getTimestamp(): Long = 0L

            override fun getRotationDegrees(): Int = rotationDegreesValue

            override fun getSensorToBufferTransformMatrix(): Matrix = Matrix()

            override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
        }

        override fun close() = Unit
    }
}
