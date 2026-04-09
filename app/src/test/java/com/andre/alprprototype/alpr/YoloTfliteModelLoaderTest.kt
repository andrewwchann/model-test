package com.andre.alprprototype.alpr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YoloTfliteModelLoaderTest {
    @Test
    fun interpretInputDimensions_returns_null_for_non_4d_shape() {
        assertNull(YoloTfliteModelLoader.interpretInputDimensions(intArrayOf(1, 2, 3)))
    }

    @Test
    fun interpretInputDimensions_handles_channels_first_shape() {
        val dims = YoloTfliteModelLoader.interpretInputDimensions(intArrayOf(1, 3, 320, 640))

        assertEquals(320 to 640, dims)
    }

    @Test
    fun interpretInputDimensions_handles_channels_last_shape() {
        val dims = YoloTfliteModelLoader.interpretInputDimensions(intArrayOf(1, 640, 320, 3))

        assertEquals(640 to 320, dims)
    }

    @Test
    fun loadModelBuffer_prefers_mapped_buffer() {
        val mapped = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).apply {
            put(byteArrayOf(1, 2, 3, 4))
            rewind()
        }

        val loaded = YoloTfliteModelLoader.loadModelBuffer(
            mapAssetBuffer = { mapped },
            readAssetBytes = { error("should not run") },
        )

        assertEquals(mapped, loaded)
    }

    @Test
    fun loadModelBuffer_falls_back_to_bytes_when_mapping_fails() {
        val loaded = YoloTfliteModelLoader.loadModelBuffer(
            mapAssetBuffer = { throw IllegalStateException("fd unavailable") },
            readAssetBytes = { byteArrayOf(7, 8, 9) },
        )

        assertNotNull(loaded)
        val bytes = ByteArray(3)
        loaded!!.get(bytes)
        assertEquals(listOf<Byte>(7, 8, 9), bytes.toList())
    }

    @Test
    fun loadModelBuffer_returns_null_when_both_sources_fail() {
        val loaded = YoloTfliteModelLoader.loadModelBuffer(
            mapAssetBuffer = { throw IllegalStateException("fd unavailable") },
            readAssetBytes = { throw IllegalStateException("stream unavailable") },
        )

        assertNull(loaded)
    }
}
