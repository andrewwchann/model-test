package com.andre.alprprototype

import android.graphics.BitmapFactory

internal object ImageSampling {
    fun decodeSampledBitmap(imagePath: String, targetWidth: Int, targetHeight: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imagePath, bounds)

        val sampleSize = calculateInSampleSize(bounds, targetWidth, targetHeight)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(imagePath, decodeOptions)
    }

    fun calculateInSampleSize(
        options: BitmapFactory.Options,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        val imageHeight = options.outHeight
        val imageWidth = options.outWidth
        var sampleSize = 1

        if (imageHeight <= 0 || imageWidth <= 0) {
            return sampleSize
        }

        while ((imageHeight / (sampleSize * 2)) >= targetHeight &&
            (imageWidth / (sampleSize * 2)) >= targetWidth
        ) {
            sampleSize *= 2
        }

        return sampleSize.coerceAtLeast(1)
    }
}
