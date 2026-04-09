package com.andre.alprprototype.alpr

import android.graphics.Bitmap
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AssistedPlateCropSaverTest {
    @Test
    fun previewCenterRect_returns_null_for_invalid_dimensions() {
        val saver = AssistedPlateCropSaver(RuntimeEnvironment.getApplication())

        assertNull(saver.previewCenterRect(0, 720))
        assertNull(saver.previewCenterRect(1280, 0))
    }

    @Test
    fun previewCenterRect_returns_normalized_center_crop() {
        val saver = AssistedPlateCropSaver(RuntimeEnvironment.getApplication())

        val rect = saver.previewCenterRect(1280, 720)

        assertNotNull(rect)
        assertTrue(rect!!.left in 0f..1f)
        assertTrue(rect.top in 0f..1f)
        assertTrue(rect.right in 0f..1f)
        assertTrue(rect.bottom in 0f..1f)
        assertTrue(rect.left < rect.right)
        assertTrue(rect.top < rect.bottom)
    }

    @Test
    fun saveFromCenter_writes_crop_file() {
        val saver = AssistedPlateCropSaver(RuntimeEnvironment.getApplication())
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)

        val result = saver.saveFromCenter(bitmap)

        assertNotNull(result)
        val saved = requireNotNull(result)
        assertTrue(File(saved.path).exists())
        assertTrue(saved.normalizedRect.left < saved.normalizedRect.right)
        assertTrue(saved.normalizedRect.top < saved.normalizedRect.bottom)
    }

    @Test
    fun saveFromTap_clamps_crop_near_edge() {
        val saver = AssistedPlateCropSaver(RuntimeEnvironment.getApplication())
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)

        val result = saver.saveFromTap(bitmap, 0f, 0f)

        assertNotNull(result)
        val saved = requireNotNull(result)
        assertTrue(saved.normalizedRect.left >= 0f)
        assertTrue(saved.normalizedRect.top >= 0f)
        assertTrue(saved.normalizedRect.right <= 1f)
        assertTrue(saved.normalizedRect.bottom <= 1f)
    }
}
