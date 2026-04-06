package com.andre.alprprototype

import android.graphics.Bitmap
import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DisplayBitmapLoaderTest {
    @Test
    fun orientationToRotationDegrees_maps_supported_exif_values() {
        assertEquals(90f, DisplayBitmapLoader.orientationToRotationDegrees(ExifInterface.ORIENTATION_ROTATE_90), 0.0001f)
        assertEquals(180f, DisplayBitmapLoader.orientationToRotationDegrees(ExifInterface.ORIENTATION_ROTATE_180), 0.0001f)
        assertEquals(270f, DisplayBitmapLoader.orientationToRotationDegrees(ExifInterface.ORIENTATION_ROTATE_270), 0.0001f)
        assertEquals(0f, DisplayBitmapLoader.orientationToRotationDegrees(ExifInterface.ORIENTATION_NORMAL), 0.0001f)
        assertEquals(0f, DisplayBitmapLoader.orientationToRotationDegrees(-1), 0.0001f)
    }

    @Test
    fun load_returns_null_when_decode_fails() {
        val result = DisplayBitmapLoader.load(
            imagePath = "missing.jpg",
            targetWidth = 100,
            targetHeight = 100,
            decodeSampledBitmap = { _, _, _ -> null },
            readOrientation = { ExifInterface.ORIENTATION_ROTATE_90 },
        )

        assertNull(result)
    }

    @Test
    fun load_returns_original_bitmap_when_rotation_is_not_needed() {
        val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)

        val result = DisplayBitmapLoader.load(
            imagePath = "normal.jpg",
            targetWidth = 100,
            targetHeight = 100,
            decodeSampledBitmap = { _, _, _ -> bitmap },
            readOrientation = { ExifInterface.ORIENTATION_NORMAL },
        )

        assertSame(bitmap, result)
        assertEquals(false, bitmap.isRecycled)
    }

    @Test
    fun load_rotates_bitmap_and_recycles_original_when_exif_requires_it() {
        val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)

        val result = DisplayBitmapLoader.load(
            imagePath = "rotated.jpg",
            targetWidth = 100,
            targetHeight = 100,
            decodeSampledBitmap = { _, _, _ -> bitmap },
            readOrientation = { ExifInterface.ORIENTATION_ROTATE_90 },
        )

        requireNotNull(result)
        assertNotSame(bitmap, result)
        assertEquals(2, result.width)
        assertEquals(4, result.height)
        assertEquals(true, bitmap.isRecycled)
    }
}
