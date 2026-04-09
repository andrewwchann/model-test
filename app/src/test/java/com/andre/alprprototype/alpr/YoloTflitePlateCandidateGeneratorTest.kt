package com.andre.alprprototype.alpr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class YoloTflitePlateCandidateGeneratorTest {
    @After
    fun tearDown() {
        YoloTfliteGeneratorEnvironment.reset()
    }

    @Test
    fun generate_returns_empty_when_no_bitmap_source_is_available() {
        val generator = YoloTflitePlateCandidateGenerator(
            interpreter = FakeTfliteInterpreter(
                inputShape = intArrayOf(1, 32, 32, 3),
                inputType = DataType.FLOAT32,
                outputShape = intArrayOf(1, 1, 5),
                outputType = DataType.FLOAT32,
            ),
            inputWidth = 32,
            inputHeight = 32,
            inputDataType = DataType.FLOAT32,
        )

        val candidates = generator.generate(FakeImageProxy(8, 4), uprightBitmapProvider = null)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun generate_prefers_upright_bitmap_provider_and_maps_detection_to_plate_candidate() {
        val generator = YoloTflitePlateCandidateGenerator(
            interpreter = FakeTfliteInterpreter(
                inputShape = intArrayOf(1, 32, 32, 3),
                inputType = DataType.FLOAT32,
                outputShape = intArrayOf(1, 6, 5),
                outputType = DataType.FLOAT32,
                outputValues = floatArrayOf(
                    16f, 16f, 24f, 12f, 0.9f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                ),
            ),
            inputWidth = 32,
            inputHeight = 32,
            inputDataType = DataType.FLOAT32,
        )
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)

        val candidates = generator.generate(
            image = FakeImageProxy(8, 4),
            uprightBitmapProvider = { bitmap },
        )

        assertEquals(1, candidates.size)
        assertEquals("yolo-tflite", candidates.first().source)
        assertEquals(0.9f, candidates.first().confidence, 0.0001f)
    }

    @Test
    fun generate_falls_back_to_image_bitmap_when_provider_returns_null() {
        val generator = YoloTflitePlateCandidateGenerator(
            interpreter = FakeTfliteInterpreter(
                inputShape = intArrayOf(1, 4, 2, 3),
                inputType = DataType.FLOAT32,
                outputShape = intArrayOf(1, 6, 5),
                outputType = DataType.FLOAT32,
                outputValues = floatArrayOf(
                    1f, 2f, 2f, 2f, 0.95f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                ),
            ),
            inputWidth = 2,
            inputHeight = 4,
            inputDataType = DataType.FLOAT32,
        )

        val candidates = generator.generate(
            image = validImageProxy(proxyWidth = 4, proxyHeight = 2, rotationDegreesValue = 90),
            uprightBitmapProvider = { null },
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun generate_returns_empty_when_detection_cannot_map_back_to_upright_bitmap() {
        val generator = YoloTflitePlateCandidateGenerator(
            interpreter = FakeTfliteInterpreter(
                inputShape = intArrayOf(1, 32, 32, 3),
                inputType = DataType.FLOAT32,
                outputShape = intArrayOf(1, 6, 5),
                outputType = DataType.FLOAT32,
                outputValues = floatArrayOf(
                    0f, 0f, 0f, 0f, 0.9f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                ),
            ),
            inputWidth = 32,
            inputHeight = 32,
            inputDataType = DataType.FLOAT32,
        )

        val candidates = generator.generate(
            image = FakeImageProxy(8, 4),
            uprightBitmapProvider = { Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888) },
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun detectBitmap_returns_empty_when_preprocess_output_is_unsupported() {
        val generator = YoloTflitePlateCandidateGenerator(
            interpreter = FakeTfliteInterpreter(
                inputShape = intArrayOf(1, 16, 16, 3),
                inputType = DataType.INT32,
                outputShape = intArrayOf(1, 1, 5),
                outputType = DataType.FLOAT32,
            ),
            inputWidth = 16,
            inputHeight = 16,
            inputDataType = DataType.INT32,
        )

        val candidates = generator.detectBitmap(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun detectBitmap_maps_successful_detection_to_plate_candidate() {
        val generator = YoloTflitePlateCandidateGenerator(
            interpreter = FakeTfliteInterpreter(
                inputShape = intArrayOf(1, 32, 32, 3),
                inputType = DataType.FLOAT32,
                outputShape = intArrayOf(1, 6, 5),
                outputType = DataType.FLOAT32,
                outputValues = floatArrayOf(
                    16f, 16f, 24f, 12f, 0.8f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                ),
            ),
            inputWidth = 32,
            inputHeight = 32,
            inputDataType = DataType.FLOAT32,
        )

        val candidates = generator.detectBitmap(Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888))

        assertEquals(1, candidates.size)
        assertEquals("yolo-tflite", candidates.first().source)
        assertEquals(0.8f, candidates.first().confidence, 0.0001f)
    }

    @Test
    fun detectBitmap_returns_empty_when_output_shape_is_invalid() {
        val generator = YoloTflitePlateCandidateGenerator(
            interpreter = FakeTfliteInterpreter(
                inputShape = intArrayOf(1, 32, 32, 3),
                inputType = DataType.FLOAT32,
                outputShape = intArrayOf(1, 5),
                outputType = DataType.FLOAT32,
            ),
            inputWidth = 32,
            inputHeight = 32,
            inputDataType = DataType.FLOAT32,
        )

        val candidates = generator.detectBitmap(Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888))

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun detectBitmap_returns_empty_when_output_type_is_not_float32() {
        val generator = YoloTflitePlateCandidateGenerator(
            interpreter = FakeTfliteInterpreter(
                inputShape = intArrayOf(1, 32, 32, 3),
                inputType = DataType.FLOAT32,
                outputShape = intArrayOf(1, 6, 5),
                outputType = DataType.UINT8,
            ),
            inputWidth = 32,
            inputHeight = 32,
            inputDataType = DataType.FLOAT32,
        )

        val candidates = generator.detectBitmap(Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888))

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun close_delegates_to_interpreter() {
        val interpreter = FakeTfliteInterpreter(
            inputShape = intArrayOf(1, 32, 32, 3),
            inputType = DataType.FLOAT32,
            outputShape = intArrayOf(1, 1, 5),
            outputType = DataType.FLOAT32,
        )
        val generator = YoloTflitePlateCandidateGenerator(
            interpreter = interpreter,
            inputWidth = 32,
            inputHeight = 32,
            inputDataType = DataType.FLOAT32,
        )

        generator.close()

        assertTrue(interpreter.closed)
    }

    @Test
    fun createOrNull_returns_null_when_model_buffer_cannot_be_loaded() {
        YoloTfliteGeneratorEnvironment.modelBufferLoader = { _, _ -> null }

        val generator = YoloTflitePlateCandidateGenerator.createOrNull(appContext())

        assertNull(generator)
    }

    @Test
    fun createOrNull_closes_interpreter_and_returns_null_for_unrecognized_input_shape() {
        val interpreter = FakeTfliteInterpreter(
            inputShape = intArrayOf(1, 2, 3),
            inputType = DataType.FLOAT32,
            outputShape = intArrayOf(1, 1, 5),
            outputType = DataType.FLOAT32,
        )
        YoloTfliteGeneratorEnvironment.modelBufferLoader = { _, _ -> ByteBuffer.allocateDirect(4) }
        YoloTfliteGeneratorEnvironment.interpreterFactory = { interpreter }

        val generator = YoloTflitePlateCandidateGenerator.createOrNull(appContext())

        assertNull(generator)
        assertTrue(interpreter.closed)
    }

    @Test
    fun createOrNull_builds_generator_when_model_and_shape_are_valid() {
        val interpreter = FakeTfliteInterpreter(
            inputShape = intArrayOf(1, 64, 32, 3),
            inputType = DataType.FLOAT32,
            outputShape = intArrayOf(1, 1, 5),
            outputType = DataType.FLOAT32,
        )
        YoloTfliteGeneratorEnvironment.modelBufferLoader = { _, _ -> ByteBuffer.allocateDirect(4) }
        YoloTfliteGeneratorEnvironment.interpreterFactory = { interpreter }

        val generator = YoloTflitePlateCandidateGenerator.createOrNull(appContext())

        assertNotNull(generator)
    }

    private fun appContext(): Application = RuntimeEnvironment.getApplication()

    private fun validImageProxy(
        proxyWidth: Int,
        proxyHeight: Int,
        rotationDegreesValue: Int,
    ): FakeImageProxy {
        return FakeImageProxy(
            proxyWidth = proxyWidth,
            proxyHeight = proxyHeight,
            rotationDegreesValue = rotationDegreesValue,
            planes = arrayOf(
                FakePlaneProxy(
                    ByteBuffer.wrap(
                        byteArrayOf(
                            10, 11, 12, 13,
                            14, 15, 16, 17,
                        ),
                    ),
                    4,
                    1,
                ),
                FakePlaneProxy(ByteBuffer.wrap(byteArrayOf(21, 22)), 2, 1),
                FakePlaneProxy(ByteBuffer.wrap(byteArrayOf(31, 32)), 2, 1),
            ),
        )
    }

    private class FakeTfliteInterpreter(
        inputShape: IntArray,
        inputType: DataType,
        outputShape: IntArray,
        outputType: DataType,
        private val outputValues: FloatArray = floatArrayOf(),
    ) : TfliteInterpreter {
        private val inputTensor = FakeTfliteTensorInfo(inputShape, inputType)
        private val outputTensor = FakeTfliteTensorInfo(outputShape, outputType)
        var closed = false

        override fun getInputTensor(index: Int): TfliteTensorInfo = inputTensor

        override fun getOutputTensor(index: Int): TfliteTensorInfo = outputTensor

        override fun run(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer) {
            val floatBuffer = outputBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            floatBuffer.put(outputValues)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeTfliteTensorInfo(
        private val shapeValue: IntArray,
        private val type: DataType,
    ) : TfliteTensorInfo {
        override fun shape(): IntArray = shapeValue

        override fun dataType(): DataType = type
    }

    private class FakeImageProxy(
        private val proxyWidth: Int,
        private val proxyHeight: Int,
        private val rotationDegreesValue: Int = 0,
        private val planes: Array<ImageProxy.PlaneProxy> = emptyArray(),
    ) : ImageProxy {
        private var cropRect: Rect = Rect(0, 0, proxyWidth, proxyHeight)

        override fun getWidth(): Int = proxyWidth

        override fun getHeight(): Int = proxyHeight

        override fun getFormat(): Int = 0

        override fun getCropRect(): Rect = cropRect

        override fun setCropRect(rect: Rect?) {
            cropRect = rect ?: Rect(0, 0, proxyWidth, proxyHeight)
        }

        override fun getImage(): Image? = null

        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = planes

        override fun close() = Unit

        override fun getImageInfo(): ImageInfo = object : ImageInfo {
            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()

            override fun getTimestamp(): Long = 0L

            override fun getRotationDegrees(): Int = rotationDegreesValue

            override fun getSensorToBufferTransformMatrix(): Matrix = Matrix()

            override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
        }
    }

    private class FakePlaneProxy(
        private val byteBuffer: ByteBuffer,
        private val rowStrideValue: Int,
        private val pixelStrideValue: Int,
    ) : ImageProxy.PlaneProxy {
        override fun getBuffer(): ByteBuffer = byteBuffer

        override fun getPixelStride(): Int = pixelStrideValue

        override fun getRowStride(): Int = rowStrideValue
    }
}
