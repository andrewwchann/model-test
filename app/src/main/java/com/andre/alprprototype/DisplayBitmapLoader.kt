package com.andre.alprprototype

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface

internal object DisplayBitmapLoader {
    fun load(
        imagePath: String,
        targetWidth: Int,
        targetHeight: Int,
        decodeSampledBitmap: (String, Int, Int) -> Bitmap? = ImageSampling::decodeSampledBitmap,
        readOrientation: (String) -> Int = ::readExifOrientation,
    ): Bitmap? {
        val bitmap = decodeSampledBitmap(imagePath, targetWidth, targetHeight) ?: return null
        val rotationDegrees = orientationToRotationDegrees(readOrientation(imagePath))
        if (rotationDegrees == 0f) {
            return bitmap
        }

        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotatedBitmap != bitmap) {
            bitmap.recycle()
        }
        return rotatedBitmap
    }

    fun orientationToRotationDegrees(orientation: Int): Float {
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }

    private fun readExifOrientation(imagePath: String): Int {
        return try {
            ExifInterface(imagePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }
}
