package com.andre.alprprototype.alpr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

class AssistedPlateCropSaver(context: Context) : AssistedCropController {
    private val outputDir = File(context.filesDir, "alpr-crops")

    override fun previewCenterRect(imageWidth: Int, imageHeight: Int): NormalizedRect? {
        if (imageWidth <= 0 || imageHeight <= 0) {
            return null
        }
        val cropRect = buildCropRect(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            tapX = imageWidth / 2f,
            tapY = imageHeight / 2f,
        )
        return NormalizedRect(
            left = cropRect.left / imageWidth.toFloat(),
            top = cropRect.top / imageHeight.toFloat(),
            right = cropRect.right / imageWidth.toFloat(),
            bottom = cropRect.bottom / imageHeight.toFloat(),
        )
    }

    override fun saveFromCenter(previewBitmap: Bitmap): AssistedCropResult? {
        if (previewBitmap.width <= 0 || previewBitmap.height <= 0) {
            return null
        }
        return saveFromPoint(
            previewBitmap = previewBitmap,
            targetX = previewBitmap.width / 2f,
            targetY = previewBitmap.height / 2f,
        )
    }

    fun saveFromTap(
        previewBitmap: Bitmap,
        tapX: Float,
        tapY: Float,
    ): AssistedCropResult? = saveFromPoint(previewBitmap, tapX, tapY)

    private fun saveFromPoint(
        previewBitmap: Bitmap,
        targetX: Float,
        targetY: Float,
    ): AssistedCropResult? {
        if (previewBitmap.width <= 0 || previewBitmap.height <= 0) {
            return null
        }

        if (!ensureOutputDir()) {
            return null
        }

        val cropRect = buildCropRect(previewBitmap.width, previewBitmap.height, targetX, targetY)
        val cropped = try {
            Bitmap.createBitmap(
                previewBitmap,
                cropRect.left,
                cropRect.top,
                cropRect.width(),
                cropRect.height(),
            )
        } catch (_: IllegalArgumentException) {
            return null
        }

        try {
            val filename = String.format(Locale.US, "assisted_plate_%d.jpg", System.currentTimeMillis())
            val file = File(outputDir, filename)
            try {
                FileOutputStream(file).use { stream ->
                    val compressed = cropped.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                    if (!compressed) {
                        throw IOException("Bitmap compression failed")
                    }
                }
            } catch (_: IOException) {
                file.delete()
                return null
            }

            return AssistedCropResult(
                path = file.absolutePath,
                normalizedRect = NormalizedRect(
                    left = cropRect.left / previewBitmap.width.toFloat(),
                    top = cropRect.top / previewBitmap.height.toFloat(),
                    right = cropRect.right / previewBitmap.width.toFloat(),
                    bottom = cropRect.bottom / previewBitmap.height.toFloat(),
                ),
            )
        } finally {
            if (!cropped.isRecycled) {
                cropped.recycle()
            }
        }
    }

    private fun ensureOutputDir(): Boolean {
        return outputDir.isDirectory || outputDir.mkdirs()
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
