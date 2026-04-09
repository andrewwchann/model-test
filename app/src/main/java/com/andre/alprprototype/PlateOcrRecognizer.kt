package com.andre.alprprototype

interface PlateOcrRecognizer : AutoCloseable {
    fun recognize(
        cropPath: String,
        onResult: (OcrDisplayResult?) -> Unit,
    )
}
