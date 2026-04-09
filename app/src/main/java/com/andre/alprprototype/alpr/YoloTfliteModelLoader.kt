package com.andre.alprprototype.alpr

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object YoloTfliteModelLoader {
    fun interpretInputDimensions(inputShape: IntArray): Pair<Int, Int>? {
        if (inputShape.size != 4) {
            return null
        }
        return if (inputShape[1] == 3) {
            inputShape[2] to inputShape[3]
        } else {
            inputShape[1] to inputShape[2]
        }
    }

    fun loadModelBuffer(
        mapAssetBuffer: () -> ByteBuffer,
        readAssetBytes: () -> ByteArray,
    ): ByteBuffer? {
        return try {
            mapAssetBuffer()
        } catch (_: Exception) {
            try {
                val bytes = readAssetBytes()
                ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                    put(bytes)
                    rewind()
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
