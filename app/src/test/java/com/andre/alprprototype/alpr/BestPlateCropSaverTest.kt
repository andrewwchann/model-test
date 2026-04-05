package com.andre.alprprototype.alpr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BestPlateCropSaverTest {
    @Test
    fun saveIfBest_returns_null_when_track_or_quality_is_missing_or_failing() {
        val saver = BestPlateCropSaver(RuntimeEnvironment.getApplication())
        val image = FakeImageProxy(1280, 720, 0)

        assertNull(
            saver.saveIfBest(
                image = image,
                state = state(activeTrack = null, quality = quality(passes = true, totalScore = 0.8f)),
                uprightBitmapProvider = { Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888) },
            ),
        )
        assertNull(
            saver.saveIfBest(
                image = image,
                state = state(activeTrack = track(ageFrames = 5), quality = null),
                uprightBitmapProvider = { Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888) },
            ),
        )
        assertNull(
            saver.saveIfBest(
                image = image,
                state = state(activeTrack = track(ageFrames = 5), quality = quality(passes = false, totalScore = 0.8f)),
                uprightBitmapProvider = { Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888) },
            ),
        )
    }

    @Test
    fun saveIfBest_requires_min_track_age_for_first_save() {
        val saver = BestPlateCropSaver(RuntimeEnvironment.getApplication())

        val path = saver.saveIfBest(
            image = FakeImageProxy(1280, 720, 0),
            state = state(activeTrack = track(ageFrames = 2), quality = quality(passes = true, totalScore = 0.9f)),
            uprightBitmapProvider = { Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888) },
        )

        assertNull(path)
    }

    @Test
    fun saveIfBest_saves_initial_crop_and_requires_improvement_before_cooldown() {
        val saver = BestPlateCropSaver(RuntimeEnvironment.getApplication())
        val initial = saver.saveIfBest(
            image = FakeImageProxy(1280, 720, 0),
            state = state(frameNumber = 1L, activeTrack = track(ageFrames = 3), quality = quality(passes = true, totalScore = 0.60f)),
            uprightBitmapProvider = { Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888) },
        )
        val noImprovement = saver.saveIfBest(
            image = FakeImageProxy(1280, 720, 0),
            state = state(frameNumber = 2L, activeTrack = track(ageFrames = 4), quality = quality(passes = true, totalScore = 0.62f)),
            uprightBitmapProvider = { Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888) },
        )
        val improved = saver.saveIfBest(
            image = FakeImageProxy(1280, 720, 0),
            state = state(frameNumber = 3L, activeTrack = track(ageFrames = 5), quality = quality(passes = true, totalScore = 0.64f)),
            uprightBitmapProvider = { Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888) },
        )

        assertNotNull(initial)
        assertTrue(File(initial!!).exists())
        assertNull(noImprovement)
        assertNotNull(improved)
    }

    @Test
    fun saveIfBest_requires_reacquire_age_and_score_after_cooldown() {
        val saver = BestPlateCropSaver(RuntimeEnvironment.getApplication())
        saver.saveIfBest(
            image = FakeImageProxy(1280, 720, 0),
            state = state(frameNumber = 1L, activeTrack = track(ageFrames = 3), quality = quality(passes = true, totalScore = 0.60f)),
            uprightBitmapProvider = { Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888) },
        )

        val tooYoung = saver.saveIfBest(
            image = FakeImageProxy(1280, 720, 0),
            state = state(frameNumber = 46L, activeTrack = track(ageFrames = 2), quality = quality(passes = true, totalScore = 0.80f)),
            uprightBitmapProvider = { Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888) },
        )
        val lowScore = saver.saveIfBest(
            image = FakeImageProxy(1280, 720, 0),
            state = state(frameNumber = 46L, activeTrack = track(ageFrames = 3), quality = quality(passes = true, totalScore = 0.54f)),
            uprightBitmapProvider = { Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888) },
        )
        val reacquired = saver.saveIfBest(
            image = FakeImageProxy(1280, 720, 90),
            state = state(
                frameNumber = 46L,
                activeTrack = track(ageFrames = 3, source = "other-source", boundingBox = RectF(20f, 30f, 160f, 90f)),
                quality = quality(passes = true, totalScore = 0.70f),
            ),
            uprightBitmapProvider = { Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888) },
        )

        assertNull(tooYoung)
        assertNull(lowScore)
        assertNotNull(reacquired)
        assertTrue(File(reacquired!!).exists())
    }

    @Test
    fun saveIfBest_returns_null_when_no_bitmap_is_available() {
        val saver = BestPlateCropSaver(RuntimeEnvironment.getApplication())

        val path = saver.saveIfBest(
            image = FakeImageProxy(1280, 720, 0),
            state = state(frameNumber = 1L, activeTrack = track(ageFrames = 3), quality = quality(passes = true, totalScore = 0.9f)),
        )

        assertNull(path)
    }

    @Test
    fun mapToUprightRect_handles_all_rotation_cases() {
        val source = RectF(10f, 20f, 30f, 40f)

        assertEquals(Rect(10, 20, 30, 40), invokeMapToUprightRect(source, 100, 80, 0))
        assertEquals(Rect(40, 10, 60, 30), invokeMapToUprightRect(source, 100, 80, 90))
        assertEquals(Rect(70, 40, 90, 60), invokeMapToUprightRect(source, 100, 80, 180))
        assertEquals(Rect(20, 70, 40, 90), invokeMapToUprightRect(source, 100, 80, 270))
    }

    @Test
    fun safeCrop_clamps_rect_and_returns_bitmap() {
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)

        val cropped = invokeSafeCrop(bitmap, Rect(-5, -2, 12, 8))

        assertNotNull(cropped)
        assertEquals(12, cropped!!.width)
        assertEquals(8, cropped.height)
    }

    @Test
    fun expandedForPlate_adds_margins_and_clamps_to_bounds() {
        val expanded = invokeExpandedForPlate(Rect(10, 10, 30, 20), 40, 25)

        assertEquals(Rect(2, 2, 38, 25), expanded)
    }

    private fun state(
        frameNumber: Long = 1L,
        activeTrack: PlateTrack?,
        quality: PlateQuality?,
    ): PipelineDebugState {
        return PipelineDebugState(
            frameNumber = frameNumber,
            inputWidth = 1280,
            inputHeight = 720,
            candidates = emptyList(),
            detections = emptyList(),
            activeTrack = activeTrack,
            quality = quality,
        )
    }

    private fun track(
        ageFrames: Int,
        source: String = "yolo-tflite",
        boundingBox: RectF = RectF(300f, 200f, 520f, 270f),
    ): PlateTrack {
        return PlateTrack(
            trackId = 1,
            boundingBox = boundingBox,
            ageFrames = ageFrames,
            source = source,
        )
    }

    private fun quality(passes: Boolean, totalScore: Float): PlateQuality {
        return PlateQuality(
            blurScore = 0.8f,
            pixelWidth = 220f,
            angleScore = 0.9f,
            passes = passes,
            reasons = if (passes) emptyList() else listOf("blur"),
            totalScore = totalScore,
        )
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
        override fun close() = Unit

        override fun getImageInfo(): ImageInfo = object : ImageInfo {
            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()
            override fun getTimestamp(): Long = 0L
            override fun getRotationDegrees(): Int = rotationDegreesValue
            override fun getSensorToBufferTransformMatrix(): Matrix = Matrix()
            override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
        }
    }

    private fun invokeMapToUprightRect(source: RectF, sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): Rect {
        val method = Class.forName("com.andre.alprprototype.alpr.BestPlateCropSaverKt")
            .getDeclaredMethod(
                "mapToUprightRect",
                RectF::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        method.isAccessible = true
        return method.invoke(null, source, sourceWidth, sourceHeight, rotationDegrees) as Rect
    }

    private fun invokeSafeCrop(bitmap: Bitmap, rect: Rect): Bitmap? {
        val method = Class.forName("com.andre.alprprototype.alpr.BestPlateCropSaverKt")
            .getDeclaredMethod("safeCrop", Bitmap::class.java, Rect::class.java)
        method.isAccessible = true
        return method.invoke(null, bitmap, rect) as Bitmap?
    }

    private fun invokeExpandedForPlate(rect: Rect, imageWidth: Int, imageHeight: Int): Rect {
        val method = Class.forName("com.andre.alprprototype.alpr.BestPlateCropSaverKt")
            .getDeclaredMethod(
                "expandedForPlate",
                Rect::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        method.isAccessible = true
        return method.invoke(null, rect, imageWidth, imageHeight) as Rect
    }
}
