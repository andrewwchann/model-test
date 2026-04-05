package com.andre.alprprototype.alpr

import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
class FrameBitmapUtilsTest {
    @Test
    fun toUprightBitmap_returns_null_when_image_has_too_few_planes() {
        val image = FakeYuvImageProxy(
            proxyWidth = 4,
            proxyHeight = 2,
            rotationDegreesValue = 0,
            planes = emptyArray(),
        )

        val bitmap = image.toUprightBitmap()

        assertNull(bitmap)
    }

    @Test
    fun toNv21Bytes_returns_null_when_planes_are_missing() {
        val image = FakeYuvImageProxy(
            proxyWidth = 4,
            proxyHeight = 2,
            rotationDegreesValue = 0,
            planes = emptyArray(),
        )

        val nv21 = invokeToNv21Bytes(image)

        assertNull(nv21)
    }

    @Test
    fun toNv21Bytes_interleaves_y_v_u_planes() {
        val image = validImageProxy(rotationDegreesValue = 0)

        val nv21 = invokeToNv21Bytes(image)

        assertArrayEquals(
            byteArrayOf(
                10, 11, 12, 13,
                14, 15, 16, 17,
                31, 21, 32, 22,
            ),
            nv21,
        )
    }

    @Test
    fun copyPlane_copies_rows_using_pixel_and_output_strides() {
        val output = ByteArray(8)
        val buffer = ByteBuffer.wrap(byteArrayOf(1, 99, 2, 99, 3, 99, 4, 99))

        invokeCopyPlane(
            buffer = buffer,
            rowStride = 4,
            pixelStride = 2,
            planeWidth = 2,
            planeHeight = 2,
            output = output,
            outputOffset = 1,
            outputPixelStride = 2,
        )

        assertArrayEquals(byteArrayOf(0, 1, 0, 2, 0, 3, 0, 4), output)
    }

    private fun validImageProxy(rotationDegreesValue: Int): FakeYuvImageProxy {
        return FakeYuvImageProxy(
            proxyWidth = 4,
            proxyHeight = 2,
            rotationDegreesValue = rotationDegreesValue,
            planes = arrayOf(
                FakePlaneProxy(ByteBuffer.wrap(byteArrayOf(10, 11, 12, 13, 14, 15, 16, 17)), 4, 1),
                FakePlaneProxy(ByteBuffer.wrap(byteArrayOf(21, 22)), 2, 1),
                FakePlaneProxy(ByteBuffer.wrap(byteArrayOf(31, 32)), 2, 1),
            ),
        )
    }

    private fun invokeToNv21Bytes(image: ImageProxy): ByteArray? {
        val method = Class.forName("com.andre.alprprototype.alpr.FrameBitmapUtilsKt")
            .getDeclaredMethod("toNv21Bytes", ImageProxy::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(null, image) as ByteArray?
    }

    private fun invokeCopyPlane(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        planeWidth: Int,
        planeHeight: Int,
        output: ByteArray,
        outputOffset: Int,
        outputPixelStride: Int,
    ) {
        val method = Class.forName("com.andre.alprprototype.alpr.FrameBitmapUtilsKt")
            .getDeclaredMethod(
                "copyPlane",
                ByteBuffer::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        method.isAccessible = true
        method.invoke(
            null,
            buffer,
            rowStride,
            pixelStride,
            planeWidth,
            planeHeight,
            output,
            outputOffset,
            outputPixelStride,
        )
    }

    private class FakeYuvImageProxy(
        private val proxyWidth: Int,
        private val proxyHeight: Int,
        private val rotationDegreesValue: Int,
        private val planes: Array<ImageProxy.PlaneProxy>,
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
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = planes
        override fun close() = Unit
        override fun getImageInfo(): ImageInfo = object : ImageInfo {
            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()
            override fun getTimestamp(): Long = 0L
            override fun getRotationDegrees(): Int = rotationDegreesValue
            override fun getSensorToBufferTransformMatrix(): Matrix = Matrix()
            override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
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
