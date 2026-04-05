package com.andre.alprprototype

import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
