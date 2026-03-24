package com.andre.alprprototype.alpr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal fun ImageProxy.toUprightBitmap(): Bitmap? {
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
    buffer: ByteBuffer,
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
