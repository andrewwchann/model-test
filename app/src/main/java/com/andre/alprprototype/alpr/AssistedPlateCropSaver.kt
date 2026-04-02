package com.andre.alprprototype.alpr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class AssistedPlateCropSaver(context: Context) {
    private val outputDir = File(context.filesDir, "assisted-alpr-crops").apply { mkdirs() }

    fun saveFromTap(
        previewBitmap: Bitmap,
        tapX: Float,
        tapY: Float,
    ): AssistedCropResult? {
        if (previewBitmap.width <= 0 || previewBitmap.height <= 0) {
            return null
        }

        val cropRect = buildCropRect(previewBitmap.width, previewBitmap.height, tapX, tapY)
        val cropped = Bitmap.createBitmap(
            previewBitmap,
            cropRect.left,
            cropRect.top,
            cropRect.width(),
            cropRect.height(),
        )

        val filename = String.format(
            Locale.US,
            "assisted_plate_%d.jpg",
            System.currentTimeMillis(),
        )
        val file = File(outputDir, filename)
        FileOutputStream(file).use { stream ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        }
        cropped.recycle()

        return AssistedCropResult(
            path = file.absolutePath,
            normalizedRect = NormalizedRect(
                left = cropRect.left / previewBitmap.width.toFloat(),
                top = cropRect.top / previewBitmap.height.toFloat(),
                right = cropRect.right / previewBitmap.width.toFloat(),
                bottom = cropRect.bottom / previewBitmap.height.toFloat(),
            ),
        )
    }

    private fun buildCropRect(
        imageWidth: Int,
        imageHeight: Int,
        tapX: Float,
        tapY: Float,
    ): Rect {
        val cropWidth = (imageWidth * 0.42f).toInt().coerceAtLeast(220).coerceAtMost(imageWidth)
        val cropHeight = (cropWidth / 2.15f).toInt().coerceAtLeast(110).coerceAtMost(imageHeight)
        val left = (tapX - cropWidth / 2f).toInt()
            .coerceIn(0, (imageWidth - cropWidth).coerceAtLeast(0))
        val top = (tapY - cropHeight / 2f).toInt()
            .coerceIn(0, (imageHeight - cropHeight).coerceAtLeast(0))
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }
}

data class AssistedCropResult(
    val path: String,
    val normalizedRect: NormalizedRect,
)

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)
