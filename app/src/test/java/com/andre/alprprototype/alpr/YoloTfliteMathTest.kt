package com.andre.alprprototype.alpr

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.tensorflow.lite.DataType

@RunWith(RobolectricTestRunner::class)
class YoloTfliteMathTest {
    @Test
    fun preprocess_builds_float32_letterboxed_buffer() {
        val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0x00FF00)
        }

        val frame = YoloTfliteMath.preprocess(bitmap, 8, 8, DataType.FLOAT32)

        assertEquals(8 * 8 * 3 * 4, frame.inputBuffer.capacity())
        assertEquals(2f, frame.scale, 0.0001f)
        assertEquals(0f, frame.dx, 0.0001f)
        assertEquals(2f, frame.dy, 0.0001f)
    }

    @Test
    fun preprocess_returns_empty_buffer_for_unsupported_type() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        val frame = YoloTfliteMath.preprocess(bitmap, 4, 4, DataType.INT32)

        assertEquals(0, frame.inputBuffer.capacity())
        assertEquals(2f, frame.scale, 0.0001f)
    }

    @Test
    fun preprocess_builds_uint8_buffer() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, android.graphics.Color.rgb(12, 34, 56))
        }

        val frame = YoloTfliteMath.preprocess(bitmap, 1, 1, DataType.UINT8)
        val bytes = ByteArray(3)
        frame.inputBuffer.get(bytes)

        assertEquals(3, frame.inputBuffer.capacity())
        assertEquals(12.toByte(), bytes[0])
        assertEquals(34.toByte(), bytes[1])
        assertEquals(56.toByte(), bytes[2])
    }

    @Test
    fun decodeDetections_reads_channels_first_and_applies_nms() {
        val values = floatArrayOf(
            0.5f, 0.52f, 0.8f, 0.1f, 0.1f,
            0.5f, 0.5f, 0.2f, 0.1f, 0.1f,
            0.4f, 0.4f, 0.1f, 0.1f, 0.1f,
            0.2f, 0.2f, 0.1f, 0.1f, 0.1f,
            0.9f, 0.7f, 0.1f, 0.1f, 0.1f,
        )

        val detections = YoloTfliteMath.decodeDetections(values, intArrayOf(1, 5, 5))

        assertEquals(1, detections.size)
        assertEquals(0.5f, detections.first().cx, 0.0001f)
        assertEquals(0.9f, detections.first().confidence, 0.0001f)
    }

    @Test
    fun decodeDetections_reads_channels_last_and_combines_objectness_and_class_scores() {
        val values = floatArrayOf(
            50f, 20f, 30f, 10f, 0.5f, 0.8f,
            20f, 20f, 10f, 10f, 0.2f, 0.9f,
            0f, 0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f,
        )

        val detections = YoloTfliteMath.decodeDetections(values, intArrayOf(1, 7, 6))

        assertEquals(1, detections.size)
        assertEquals(0.4f, detections.first().confidence, 0.0001f)
        assertEquals(50f, detections.first().cx, 0.0001f)
    }

    @Test
    fun decodeDetections_uses_objectness_when_class_scores_absent() {
        val values = floatArrayOf(
            10f, 20f, 0f, 0f, 0f, 0f,
            10f, 20f, 0f, 0f, 0f, 0f,
            12f, 10f, 0f, 0f, 0f, 0f,
            12f, 10f, 0f, 0f, 0f, 0f,
            0.6f, 0.2f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f,
        )

        val detections = YoloTfliteMath.decodeDetections(values, intArrayOf(1, 6, 6))

        assertEquals(1, detections.size)
        assertEquals(0.6f, detections.first().confidence, 0.0001f)
    }

    @Test
    fun decodeDetections_returns_empty_when_too_few_channels() {
        val detections = YoloTfliteMath.decodeDetections(floatArrayOf(1f, 2f, 3f, 4f), intArrayOf(1, 2, 2))

        assertTrue(detections.isEmpty())
    }

    @Test
    fun decodeDetections_filters_low_confidence_boxes() {
        val values = floatArrayOf(
            0.5f, 0.2f,
            0.5f, 0.2f,
            0.3f, 0.1f,
            0.2f, 0.1f,
            0.34f, 0.1f,
        )

        val detections = YoloTfliteMath.decodeDetections(values, intArrayOf(1, 5, 2))

        assertTrue(detections.isEmpty())
    }

    @Test
    fun decodeDetections_uses_max_score_when_values_are_not_probabilities() {
        val values = floatArrayOf(
            20f, 10f, 8f, 4f, 2.5f, 1.2f, 1.8f,
            0f, 0f, 0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f, 0f,
        )

        val detections = YoloTfliteMath.decodeDetections(values, intArrayOf(1, 8, 7))

        assertEquals(1, detections.size)
        assertEquals(2.5f, detections.first().confidence, 0.0001f)
    }

    @Test
    fun decodeDetections_limits_results_to_max_detections() {
        val values = floatArrayOf(
            0.05f, 0.15f, 0.25f, 0.35f, 0.45f, 0.55f, 0.65f,
            0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f,
            0.05f, 0.05f, 0.05f, 0.05f, 0.05f, 0.05f, 0.05f,
            0.05f, 0.05f, 0.05f, 0.05f, 0.05f, 0.05f, 0.05f,
            0.95f, 0.9f, 0.85f, 0.8f, 0.75f, 0.7f, 0.65f,
        )

        val detections = YoloTfliteMath.decodeDetections(values, intArrayOf(1, 5, 7))

        assertEquals(6, detections.size)
        assertEquals(0.95f, detections.first().confidence, 0.0001f)
        assertEquals(0.55f, detections.last().cx, 0.0001f)
    }

    @Test
    fun mapDetectionToUprightRect_maps_normalized_coordinates() {
        val rect = YoloTfliteMath.mapDetectionToUprightRect(
            detection = RawDetection(0.5f, 0.5f, 0.25f, 0.2f, 0.9f),
            frame = PreprocessedFrame(java.nio.ByteBuffer.allocateDirect(0), 2f, 10f, 20f),
            inputWidth = 100,
            inputHeight = 80,
            uprightWidth = 200,
            uprightHeight = 120,
        )

        assertNotNull(rect)
        assertEquals(13.75f, rect!!.left, 0.0001f)
        assertEquals(6f, rect.top, 0.0001f)
        assertEquals(12.5f, rect.width(), 0.0001f)
        assertEquals(8.0f, rect.height(), 0.0001f)
    }

    @Test
    fun mapDetectionToUprightRect_clamps_absolute_coordinates() {
        val rect = YoloTfliteMath.mapDetectionToUprightRect(
            detection = RawDetection(90f, 50f, 40f, 20f, 0.9f),
            frame = PreprocessedFrame(java.nio.ByteBuffer.allocateDirect(0), 1f, 0f, 0f),
            inputWidth = 100,
            inputHeight = 100,
            uprightWidth = 100,
            uprightHeight = 60,
        )

        assertNotNull(rect)
        assertEquals(70f, rect!!.left, 0.0001f)
        assertEquals(100f, rect.right, 0.0001f)
        assertEquals(30f, rect.width(), 0.0001f)
    }

    @Test
    fun mapDetectionToUprightRect_rejects_tiny_boxes() {
        val rect = YoloTfliteMath.mapDetectionToUprightRect(
            detection = RawDetection(0.5f, 0.5f, 0.05f, 0.05f, 0.9f),
            frame = PreprocessedFrame(java.nio.ByteBuffer.allocateDirect(0), 1f, 0f, 0f),
            inputWidth = 100,
            inputHeight = 100,
            uprightWidth = 100,
            uprightHeight = 100,
        )

        assertNull(rect)
    }

    @Test
    fun iou_returns_zero_for_disjoint_boxes() {
        val iou = YoloTfliteMath.iou(
            RawDetection(10f, 10f, 4f, 4f, 0.9f),
            RawDetection(30f, 30f, 4f, 4f, 0.8f),
        )

        assertEquals(0f, iou, 0.0001f)
    }

    @Test
    fun iou_returns_fraction_for_overlapping_boxes() {
        val iou = YoloTfliteMath.iou(
            RawDetection(10f, 10f, 10f, 10f, 0.9f),
            RawDetection(12f, 10f, 10f, 10f, 0.8f),
        )

        assertTrue(iou > 0f)
        assertTrue(iou < 1f)
    }
}
