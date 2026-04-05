package com.andre.alprprototype

import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageSamplingTest {
    @Test
    fun calculateInSampleSize_returns_one_for_invalid_bounds() {
        val options = BitmapFactory.Options().apply {
            outWidth = 0
            outHeight = 100
        }

        assertEquals(1, ImageSampling.calculateInSampleSize(options, 100, 100))
    }

    @Test
    fun calculateInSampleSize_keeps_full_size_when_image_already_small() {
        val options = BitmapFactory.Options().apply {
            outWidth = 200
            outHeight = 100
        }

        assertEquals(1, ImageSampling.calculateInSampleSize(options, 400, 300))
    }

    @Test
    fun calculateInSampleSize_scales_down_by_power_of_two() {
        val options = BitmapFactory.Options().apply {
            outWidth = 4000
            outHeight = 3000
        }

        assertEquals(4, ImageSampling.calculateInSampleSize(options, 900, 600))
    }
}
