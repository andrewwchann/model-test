package com.andre.alprprototype.alpr

import android.graphics.Bitmap

interface AssistedCropController {
    fun previewCenterRect(imageWidth: Int, imageHeight: Int): NormalizedRect?

    fun saveFromCenter(previewBitmap: Bitmap): AssistedCropResult?
}
