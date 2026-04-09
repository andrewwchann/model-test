package com.andre.alprprototype

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import com.andre.alprprototype.alpr.FrameCropSaver
import com.andre.alprprototype.alpr.FramePipeline
import com.andre.alprprototype.alpr.PipelineDebugState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
class PlateFrameAnalyzerTest {
    @Test
    fun analyze_skips_and_resets_when_shouldAnalyze_is_false() {
        var callbackFrame: AnalyzedFrame? = null
        val analyzer = PlateFrameAnalyzer(
            pipeline = FakePipeline(),
            cropSaver = FakeCropSaver(),
            shouldAnalyze = { false },
            onFrameAnalyzed = { callbackFrame = it },
        )
        val image = FakeImageProxy(proxyWidth = 6, proxyHeight = 6, lumaBytes = ByteArray(36))

        analyzer.analyze(image)

        assertTrue(image.closed)
        assertNull(callbackFrame)
    }

    @Test
    fun analyze_processes_frame_and_emits_result() {
        val expectedState = debugState(frameNumber = 1L)
        var callbackFrame: AnalyzedFrame? = null
        val analyzer = PlateFrameAnalyzer(
            pipeline = FakePipeline(expectedState = expectedState),
            cropSaver = FakeCropSaver(savedPath = "crop.jpg"),
            onFrameAnalyzed = { callbackFrame = it },
        )
        val image = FakeImageProxy(proxyWidth = 6, proxyHeight = 6, lumaBytes = ByteArray(36))

        analyzer.analyze(image)

        assertTrue(image.closed)
        assertEquals(expectedState, callbackFrame?.state)
        assertEquals("crop.jpg", callbackFrame?.savedCropPath)
    }

    @Test
    fun analyze_pauses_on_large_motion_change() {
        var callbackCount = 0
        val analyzer = PlateFrameAnalyzer(
            pipeline = FakePipeline(),
            cropSaver = FakeCropSaver(),
            onFrameAnalyzed = { callbackCount += 1 },
        )

        analyzer.analyze(FakeImageProxy(proxyWidth = 6, proxyHeight = 6, lumaBytes = ByteArray(36) { 0 }))
        analyzer.analyze(FakeImageProxy(proxyWidth = 6, proxyHeight = 6, lumaBytes = ByteArray(36) { 255.toByte() }))

        assertEquals(1, callbackCount)
    }

    @Test
    fun analyze_continues_when_motion_signature_cannot_be_built() {
        var callbackCount = 0
        val analyzer = PlateFrameAnalyzer(
            pipeline = FakePipeline(),
            cropSaver = FakeCropSaver(),
            onFrameAnalyzed = { callbackCount += 1 },
        )
        val imageWithoutPlanes = FakeImageProxy(proxyWidth = 6, proxyHeight = 6, lumaBytes = ByteArray(0), includePlane = false)

        analyzer.analyze(imageWithoutPlanes)

        assertEquals(1, callbackCount)
        assertTrue(imageWithoutPlanes.closed)
    }

    @Test
    fun analyze_swallows_pipeline_exceptions_and_closes_image() {
        var callbackCount = 0
        val analyzer = PlateFrameAnalyzer(
            pipeline = FakePipeline(shouldThrow = true),
            cropSaver = FakeCropSaver(),
            onFrameAnalyzed = { callbackCount += 1 },
        )
        val image = FakeImageProxy(proxyWidth = 6, proxyHeight = 6, lumaBytes = ByteArray(36))

        analyzer.analyze(image)

        assertEquals(0, callbackCount)
        assertTrue(image.closed)
    }

    private fun debugState(frameNumber: Long): PipelineDebugState {
        return PipelineDebugState(
            frameNumber = frameNumber,
            inputWidth = 6,
            inputHeight = 6,
            candidates = emptyList(),
            detections = emptyList(),
            activeTrack = null,
            quality = null,
        )
    }

    private class FakePipeline(
        private val expectedState: PipelineDebugState = PipelineDebugState(
            frameNumber = 1L,
            inputWidth = 6,
            inputHeight = 6,
            candidates = emptyList(),
            detections = emptyList(),
            activeTrack = null,
            quality = null,
        ),
        private val shouldThrow: Boolean = false,
    ) : FramePipeline {
        override fun process(
            frameNumber: Long,
            image: ImageProxy,
            uprightBitmapProvider: (() -> Bitmap?)?,
        ): PipelineDebugState {
            if (shouldThrow) {
                throw IllegalStateException("boom")
            }
            return expectedState.copy(frameNumber = frameNumber)
        }
    }

    private class FakeCropSaver(
        private val savedPath: String? = null,
    ) : FrameCropSaver {
        override fun saveIfBest(
            image: ImageProxy,
            state: PipelineDebugState,
            uprightBitmapProvider: (() -> Bitmap?)?,
        ): String? = savedPath
    }

    private class FakeImageProxy(
        private val proxyWidth: Int,
        private val proxyHeight: Int,
        lumaBytes: ByteArray,
        private val includePlane: Boolean = true,
    ) : ImageProxy {
        private var cropRect: Rect = Rect(0, 0, proxyWidth, proxyHeight)
        var closed: Boolean = false
            private set

        private val planeArray: Array<ImageProxy.PlaneProxy> =
            if (!includePlane) {
                emptyArray()
            } else {
                arrayOf(
                    FakePlaneProxy(ByteBuffer.wrap(lumaBytes), proxyWidth, 1),
                )
            }

        override fun getWidth(): Int = proxyWidth

        override fun getHeight(): Int = proxyHeight

        override fun getFormat(): Int = 0

        override fun getCropRect(): Rect = cropRect

        override fun setCropRect(rect: Rect?) {
            cropRect = rect ?: Rect(0, 0, proxyWidth, proxyHeight)
        }

        override fun getImage(): Image? = null

        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = planeArray

        override fun getImageInfo(): ImageInfo = object : ImageInfo {
            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()
            override fun getTimestamp(): Long = 0L
            override fun getRotationDegrees(): Int = 0
            override fun getSensorToBufferTransformMatrix(): Matrix = Matrix()
            override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
        }

        override fun close() {
            closed = true
        }
    }

    private class FakePlaneProxy(
        private val byteBuffer: ByteBuffer,
        private val rowStrideValue: Int,
        private val pixelStrideValue: Int,
    ) : ImageProxy.PlaneProxy {
        override fun getBuffer(): ByteBuffer = byteBuffer
        override fun getPixelStride(): Int = pixelStrideValue
        override fun getRowStride(): Int = rowStrideValue
    }
}
