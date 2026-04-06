package com.andre.alprprototype

import android.graphics.Bitmap
import android.graphics.Color
import com.andre.alprprototype.ocr.PlateConfig
import com.andre.alprprototype.ocr.PlateOcrMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlateOcrMathTest {
    private val config = PlateConfig(
        maxPlateSlots = 4,
        alphabet = "ABC",
        padChar = '_',
        imgHeight = 2,
        imgWidth = 2,
        keepAspectRatio = false,
        imageColorMode = "rgb",
    )

    @Test
    fun preprocess_resizes_bitmap_and_writes_rgb_uint8_buffer() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.rgb(10, 20, 30))
            setPixel(1, 0, Color.rgb(40, 50, 60))
            setPixel(0, 1, Color.rgb(70, 80, 90))
            setPixel(1, 1, Color.rgb(100, 110, 120))
        }

        val prepared = PlateOcrMath.preprocess(bitmap, config)
        val bytes = ByteArray(prepared.buffer.remaining())
        prepared.buffer.get(bytes)

        assertEquals(2, prepared.width)
        assertEquals(2, prepared.height)
        assertArrayEquals(longArrayOf(1, 2, 2, 3), prepared.shape)
        assertArrayEquals(
            byteArrayOf(
                10, 20, 30,
                40, 50, 60,
                70, 80, 90,
                100, 110, 120,
            ),
            bytes,
        )
    }

    @Test
    fun extractPlateLogits_handles_supported_and_unsupported_shapes() {
        val direct = arrayOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))
        val nested = arrayOf(arrayOf(floatArrayOf(5f, 6f), floatArrayOf(7f, 8f)))
        val emptyOuter = emptyArray<Array<FloatArray>>()
        val nestedBad = arrayOf(arrayOf("bad"))

        assertEquals(2, PlateOcrMath.extractPlateLogits(direct)?.size)
        assertEquals(2, PlateOcrMath.extractPlateLogits(nested)?.size)
        assertNull(PlateOcrMath.extractPlateLogits(emptyOuter))
        assertNull(PlateOcrMath.extractPlateLogits(nestedBad))
        assertNull(PlateOcrMath.extractPlateLogits("bad"))
        assertNull(PlateOcrMath.extractPlateLogits(arrayOf("bad")))
    }

    @Test
    fun decodeFixedSlots_selects_best_chars_and_trims_pad_suffix() {
        val decoded = PlateOcrMath.decodeFixedSlots(
            logits = arrayOf(
                floatArrayOf(0.1f, 0.9f, 0.0f),
                floatArrayOf(0.2f, 0.1f, 0.8f),
                floatArrayOf(0.0f, 0.0f, 0.0f, 0.7f),
            ),
            config = config.copy(alphabet = "ABC", padChar = '_'),
        )

        assertEquals("BC_", decoded.rawText)
        assertEquals("BC", decoded.finalText)
        assertEquals(3, decoded.slots.size)
        assertEquals('B', decoded.slots[0].character)
        assertEquals('C', decoded.slots[1].character)
        assertEquals('_', decoded.slots[2].character)
        assertTrue(decoded.averageConfidence > 0f)
    }

    @Test
    fun decodeFixedSlots_defaults_unknown_indices_and_empty_logits_to_safe_values() {
        val unknown = PlateOcrMath.decodeFixedSlots(
            logits = arrayOf(floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f)),
            config = config.copy(alphabet = "ABC", padChar = '_'),
        )
        val empty = PlateOcrMath.decodeFixedSlots(
            logits = emptyArray(),
            config = config,
        )

        assertEquals("_", unknown.rawText)
        assertEquals("", unknown.finalText)
        assertEquals('_', unknown.slots.single().character)
        assertEquals(0f, empty.averageConfidence, 0.0001f)
        assertTrue(empty.slots.isEmpty())
    }

    @Test
    fun normalizePlateText_trims_pad_and_whitespace() {
        assertEquals("ABC", PlateOcrMath.normalizePlateText(" ABC__ ", ' ').trimEnd('_'))
        assertEquals("ABC", PlateOcrMath.normalizePlateText("ABC__", '_'))
    }

    @Test
    fun scoreCandidate_rewards_longer_text() {
        val shortScore = PlateOcrMath.scoreCandidate("AB", 0.5f)
        val longScore = PlateOcrMath.scoreCandidate("ABCD", 0.5f)

        assertTrue(longScore > shortScore)
    }

    @Test
    fun focusedBandCrop_clamps_inverted_fractions_and_returns_valid_band() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

        val valid = PlateOcrMath.focusedBandCrop(bitmap, 0.2f, 0.8f)
        val clamped = PlateOcrMath.focusedBandCrop(bitmap, 0.9f, 0.1f)

        assertNotNull(valid)
        assertEquals(6, valid!!.height)
        assertNotNull(clamped)
        assertTrue(clamped!!.height >= 1)
    }

    @Test
    fun buildVariants_returns_distinct_size_variants_only() {
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)

        val variants = PlateOcrMath.buildVariants(bitmap)

        assertEquals(2, variants.size)
        assertEquals(20, variants[0].width)
        assertEquals(10, variants[0].height)
        assertEquals(20, variants[1].width)
        assertTrue(variants[1].height < variants[0].height)
    }

    @Test
    fun buildVariants_returns_only_original_when_focused_crop_is_unavailable() {
        val bitmap = Bitmap.createBitmap(20, 1, Bitmap.Config.ARGB_8888)

        val variants = PlateOcrMath.buildVariants(bitmap)

        assertEquals(1, variants.size)
        assertEquals(bitmap, variants.single())
    }
}
