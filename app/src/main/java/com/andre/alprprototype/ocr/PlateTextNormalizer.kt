package com.andre.alprprototype.ocr

internal object PlateTextNormalizer {
    fun normalize(rawText: String): String {
        return rawText.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
    }
}
