package com.andre.alprprototype

import com.andre.alprprototype.ocr.PlateTextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class PlateTextNormalizerTest {
    @Test
    fun normalize_removes_non_alphanumeric_and_uppercases() {
        assertEquals("AB123CD", PlateTextNormalizer.normalize("ab-123 cd"))
    }

    @Test
    fun normalize_returns_empty_when_no_valid_characters() {
        assertEquals("", PlateTextNormalizer.normalize(" -_* "))
    }
}
