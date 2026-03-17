package com.andre.alprprototype.alpr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class BestPlateCropSaver(context: Context) {
    private val outputDir = File(context.filesDir, "alpr-crops").apply { mkdirs() }
    private var bestSavedScore = 0f
    private var savedCount = 0

    fun saveIfBest(image: ImageProxy, state: PipelineDebugState): String? {
        val track = state.activeTrack ?: return null
        val quality = state.quality ?: return null
        if (!quality.passes) {
            return null
        }
        if (quality.totalScore <= bestSavedScore + 0.02f) {
            return null
        }

        val frameBitmap = image.toUprightBitmap() ?: return null
        val cropRect = if (track.source == "yolo-tflite") {
            Rect(
                track.boundingBox.left.toInt(),
                track.boundingBox.top.toInt(),
                track.boundingBox.right.toInt(),
                track.boundingBox.bottom.toInt(),
            )
        } else {
            mapToUprightRect(
                source = track.boundingBox,
                sourceWidth = image.width,
                sourceHeight = image.height,
                rotationDegrees = image.imageInfo.rotationDegrees,
            )
        }
        val cropped = frameBitmap.safeCrop(cropRect.expandedForPlate(frameBitmap.width, frameBitmap.height)) ?: return null

        val filename = String.format(
            Locale.US,
            "plate_frame_%06d_score_%03d.jpg",
            state.frameNumber,
            (quality.totalScore * 100).toInt(),
        )
        val file = File(outputDir, filename)
        FileOutputStream(file).use { stream ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        }

        bestSavedScore = quality.totalScore
        savedCount += 1
        pruneOldFiles(keep = 8)
        return file.absolutePath
    }

    private fun pruneOldFiles(keep: Int) {
        outputDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep)
            ?.forEach { it.delete() }
    }
}

private fun ImageProxy.toUprightBitmap(): Bitmap? {
    val nv21 = toNv21Bytes() ?: return null
    val yuvImage = YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
    val jpegOutput = ByteArrayOutputStream()
    if (!yuvImage.compressToJpeg(Rect(0, 0, width, height), 92, jpegOutput)) {
        return null
    }

    val decoded = BitmapFactory.decodeByteArray(
        jpegOutput.toByteArray(),
        0,
        jpegOutput.size(),
    ) ?: return null

    val matrix = Matrix().apply {
        postRotate(imageInfo.rotationDegrees.toFloat())
    }

    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}

private fun ImageProxy.toNv21Bytes(): ByteArray? {
    if (planes.size < 3) {
        return null
    }

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yBuffer = yPlane.buffer.duplicate().apply { rewind() }
    val uBuffer = uPlane.buffer.duplicate().apply { rewind() }
    val vBuffer = vPlane.buffer.duplicate().apply { rewind() }

    val nv21 = ByteArray(width * height * 3 / 2)
    var outputOffset = 0

    copyPlane(
        buffer = yBuffer,
        rowStride = yPlane.rowStride,
        pixelStride = yPlane.pixelStride,
        planeWidth = width,
        planeHeight = height,
        output = nv21,
        outputOffset = outputOffset,
        outputPixelStride = 1,
    )
    outputOffset += width * height

    val chromaWidth = width / 2
    val chromaHeight = height / 2
    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
            val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
            nv21[outputOffset++] = vBuffer.get(vIndex)
            nv21[outputOffset++] = uBuffer.get(uIndex)
        }
    }

    return nv21
}

private fun copyPlane(
    buffer: java.nio.ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    planeWidth: Int,
    planeHeight: Int,
    output: ByteArray,
    outputOffset: Int,
    outputPixelStride: Int,
) {
    var outOffset = outputOffset
    for (row in 0 until planeHeight) {
        for (col in 0 until planeWidth) {
            val index = row * rowStride + col * pixelStride
            output[outOffset] = buffer.get(index)
            outOffset += outputPixelStride
        }
    }
}

private fun mapToUprightRect(
    source: RectF,
    sourceWidth: Int,
    sourceHeight: Int,
    rotationDegrees: Int,
): Rect {
    val mapped = when ((rotationDegrees % 360 + 360) % 360) {
        90 -> RectF(
            sourceHeight - source.bottom,
            source.left,
            sourceHeight - source.top,
            source.right,
        )
        180 -> RectF(
            sourceWidth - source.right,
            sourceHeight - source.bottom,
            sourceWidth - source.left,
            sourceHeight - source.top,
        )
        270 -> RectF(
            source.top,
            sourceWidth - source.right,
            source.bottom,
            sourceWidth - source.left,
        )
        else -> RectF(source)
    }

    return Rect(
        mapped.left.toInt(),
        mapped.top.toInt(),
        mapped.right.toInt(),
        mapped.bottom.toInt(),
    )
}

private fun Bitmap.safeCrop(rect: Rect): Bitmap? {
    val left = rect.left.coerceIn(0, width - 1)
    val top = rect.top.coerceIn(0, height - 1)
    val right = rect.right.coerceIn(left + 1, width)
    val bottom = rect.bottom.coerceIn(top + 1, height)
    if (right <= left || bottom <= top) {
        return null
    }
    return Bitmap.createBitmap(this, left, top, right - left, bottom - top)
}

private fun Rect.expandedForPlate(imageWidth: Int, imageHeight: Int): Rect {
    val marginX = (width() * 0.18f).toInt().coerceAtLeast(8)
    val marginTop = (height() * 0.40f).toInt().coerceAtLeast(8)
    val marginBottom = (height() * 0.28f).toInt().coerceAtLeast(6)

    return Rect(
        (left - marginX).coerceAtLeast(0),
        (top - marginTop).coerceAtLeast(0),
        (right + marginX).coerceAtMost(imageWidth),
        (bottom + marginBottom).coerceAtMost(imageHeight),
    )
}
