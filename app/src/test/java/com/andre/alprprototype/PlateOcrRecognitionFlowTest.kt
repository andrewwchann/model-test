package com.andre.alprprototype

import android.graphics.Bitmap
import com.andre.alprprototype.ocr.PlateOcrRecognitionFlow
import com.andre.alprprototype.ocr.ScoredOcrCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlateOcrRecognitionFlowTest {
    @Test
    fun recognize_returns_null_when_file_missing() {
        val result = PlateOcrRecognitionFlow.recognize(
            fileExists = false,
            filePath = "missing.jpg",
            bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
            buildVariants = { listOf(it) },
            runInference = { error("should not run") },
        )

        assertNull(result)
    }

    @Test
    fun recognize_returns_null_when_bitmap_missing() {
        val result = PlateOcrRecognitionFlow.recognize(
            fileExists = true,
            filePath = "missing.jpg",
            bitmap = null,
            buildVariants = { error("should not run") },
            runInference = { error("should not run") },
        )

        assertNull(result)
    }

    @Test
    fun recognize_returns_null_when_all_variants_fail() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        val result = PlateOcrRecognitionFlow.recognize(
            fileExists = true,
            filePath = "crop.jpg",
            bitmap = bitmap,
            buildVariants = { listOf(it, it) },
            runInference = { null },
        )

        assertNull(result)
    }

    @Test
    fun recognize_chooses_best_result_and_counts_agreement() {
        val base = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val variants = listOf(
            base,
            Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888),
        )

        val result = PlateOcrRecognitionFlow.recognize(
            fileExists = true,
            filePath = "crop.jpg",
            bitmap = base,
            buildVariants = { variants },
            runInference = { variant ->
                when (variant.width) {
                    2 -> ScoredOcrCandidate("ABC123", 0.80f, 0.70f)
                    3 -> ScoredOcrCandidate("XYZ999", 0.75f, 0.65f)
                    4 -> ScoredOcrCandidate("ABC123", 0.90f, 0.72f)
                    else -> null
                }
            },
        )

        assertEquals("ABC123", result?.text)
        assertEquals(2, result?.agreementCount)
        assertEquals(3, result?.variantCount)
        assertEquals(0.10f, result?.scoreMargin ?: 0f, 0.0001f)
    }

    @Test
    fun recognize_stops_early_for_high_confidence_result() {
        val base = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val variants = listOf(
            base,
            Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888),
        )
        var calls = 0

        val result = PlateOcrRecognitionFlow.recognize(
            fileExists = true,
            filePath = "crop.jpg",
            bitmap = base,
            buildVariants = { variants },
            runInference = { variant ->
                calls += 1
                if (variant.width == 3) {
                    ScoredOcrCandidate("FAST1", 0.95f, 0.92f)
                } else {
                    ScoredOcrCandidate("SLOW1", 0.60f, 0.50f)
                }
            },
        )

        assertEquals("FAST1", result?.text)
        assertEquals(2, result?.variantCount)
        assertEquals(2, calls)
        assertTrue((result?.scoreMargin ?: 0f) > 0f)
    }
}
