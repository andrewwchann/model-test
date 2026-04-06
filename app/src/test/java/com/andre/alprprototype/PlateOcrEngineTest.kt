package com.andre.alprprototype

import android.graphics.Bitmap
import com.andre.alprprototype.ocr.PlateConfig
import com.andre.alprprototype.ocr.ScoredOcrCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class PlateOcrEngineTest {
    @Test
    fun constructor_throws_when_model_has_no_inputs() {
        val error = captureConstructorFailure(
            FakeOcrSession(
                inputInfo = emptyMap(),
                outputInfo = mapOf("plate" to OcrValueInfo(isTensor = true)),
            ),
        )

        assertEquals("OCR model has no inputs", error.message)
    }

    @Test
    fun constructor_throws_when_input_is_not_tensor() {
        val error = captureConstructorFailure(
            FakeOcrSession(
                inputInfo = mapOf("input" to OcrValueInfo(isTensor = false)),
                outputInfo = mapOf("plate" to OcrValueInfo(isTensor = true)),
            ),
        )

        assertEquals("OCR model input is not a tensor", error.message)
    }

    @Test
    fun constructor_throws_when_plate_output_is_missing() {
        val error = captureConstructorFailure(
            FakeOcrSession(
                inputInfo = mapOf("input" to OcrValueInfo(isTensor = true)),
                outputInfo = emptyMap(),
            ),
        )

        assertEquals("OCR model missing 'plate' output", error.message)
    }

    @Test
    fun constructor_throws_when_plate_output_is_not_tensor() {
        val error = captureConstructorFailure(
            FakeOcrSession(
                inputInfo = mapOf("input" to OcrValueInfo(isTensor = true)),
                outputInfo = mapOf("plate" to OcrValueInfo(isTensor = false)),
            ),
        )

        assertEquals("OCR model 'plate' output is not a tensor", error.message)
    }

    @Test
    fun recognize_returns_null_immediately_when_closed() {
        val session = FakeOcrSession()
        val engine = createEngine(session = session)
        var result: OcrDisplayResult? = sentinelResult()

        engine.close()
        engine.recognize("missing.png") { result = it }

        assertNull(result)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun recognize_returns_null_when_closed_after_work_is_queued() {
        val session = FakeOcrSession()
        val executor = QueuedOcrExecutor()
        val engine = createEngine(session = session, executor = executor)
        var result: OcrDisplayResult? = sentinelResult()

        engine.recognize("later.png") { result = it }
        engine.close()
        executor.runNext()

        assertNull(result)
        assertEquals(1, executor.shutdownNowCalls)
        assertEquals(1, session.closeCalls)
        assertEquals(0, session.runCalls)
    }

    @Test
    fun recognize_returns_null_for_missing_file() {
        val engine = createEngine()
        var result: OcrDisplayResult? = sentinelResult()

        engine.recognize("missing-file.png") { result = it }

        assertNull(result)
    }

    @Test
    fun recognize_returns_null_when_bitmap_decode_fails_for_existing_file() {
        val invalidFile = File.createTempFile("plate-ocr-invalid", ".png").apply {
            writeText("not an image")
            deleteOnExit()
        }
        val engine = createEngine()
        var result: OcrDisplayResult? = sentinelResult()

        engine.recognize(invalidFile.absolutePath) { result = it }

        assertNull(result)
    }

    @Test
    fun recognize_returns_best_scored_result_for_valid_image() {
        val output = arrayOf(
            floatArrayOf(0.1f, 0.95f, 0.2f, 0f),
            floatArrayOf(0.2f, 0.1f, 0.96f, 0f),
            floatArrayOf(0f, 0f, 0f, 0.99f),
        )
        val session = FakeOcrSession(outputValue = output)
        val engine = createEngine(session = session)
        val imageFile = writeBitmapFile(Bitmap.createBitmap(12, 6, Bitmap.Config.ARGB_8888))
        var result: OcrDisplayResult? = null

        engine.recognize(imageFile.absolutePath) { result = it }

        assertNotNull(result)
        assertEquals("BC", result?.text)
        assertEquals(imageFile.absolutePath, result?.sourcePath)
        assertEquals(1, result?.variantCount)
        assertEquals(1, result?.agreementCount)
        assertEquals(1, session.runCalls)
    }

    @Test
    fun recognize_returns_null_when_inference_throws() {
        val session = FakeOcrSession(runError = IllegalStateException("boom"))
        val engine = createEngine(session = session)
        val imageFile = writeBitmapFile(Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888))
        var result: OcrDisplayResult? = sentinelResult()

        engine.recognize(imageFile.absolutePath) { result = it }

        assertNull(result)
    }

    @Test
    fun runInference_returns_null_when_plate_output_cannot_be_decoded() {
        val session = FakeOcrSession(outputValue = "bad-output")
        val engine = createEngine(session = session)

        val result = invokeRunInference(engine, Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888))

        assertNull(result)
    }

    @Test
    fun runInference_returns_null_when_normalized_text_is_blank() {
        val output = arrayOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            floatArrayOf(0f, 0f, 0f, 1f),
            floatArrayOf(0f, 0f, 0f, 1f),
        )
        val session = FakeOcrSession(outputValue = output)
        val engine = createEngine(session = session)

        val result = invokeRunInference(engine, Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888))

        assertNull(result)
    }

    @Test
    fun runInference_returns_scored_candidate_for_valid_logits() {
        val output = arrayOf(
            floatArrayOf(0.1f, 0.95f, 0.2f, 0f),
            floatArrayOf(0.2f, 0.1f, 0.96f, 0f),
            floatArrayOf(0f, 0f, 0f, 0.99f),
        )
        val session = FakeOcrSession(outputValue = output)
        val runtime = FakeOcrRuntime()
        val engine = createEngine(session = session, runtime = runtime)

        val result = invokeRunInference(engine, Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888))

        assertNotNull(result)
        assertEquals("BC", result?.text)
        assertTrue((result?.score ?: 0f) > (result?.confidence ?: 0f))
        assertEquals(1, runtime.createdTensors.size)
        assertTrue(runtime.createdTensors.first().closed)
    }

    @Test
    fun runInference_supports_non_auto_closeable_output() {
        val output = arrayOf(
            floatArrayOf(0.1f, 0.95f, 0.2f, 0f),
            floatArrayOf(0.2f, 0.1f, 0.96f, 0f),
            floatArrayOf(0f, 0f, 0f, 0.99f),
        )
        val session = FakeOcrSession(
            outputValue = output,
            outputFactory = { value -> PlainOcrOutput(mapOf("plate" to value)) },
        )
        val engine = createEngine(session = session)

        val result = invokeRunInference(engine, Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888))

        assertNotNull(result)
        assertEquals("BC", result?.text)
    }

    @Test
    fun close_is_idempotent() {
        val session = FakeOcrSession()
        val executor = QueuedOcrExecutor()
        val engine = createEngine(session = session, executor = executor)

        engine.close()
        engine.close()

        assertEquals(1, executor.shutdownNowCalls)
        assertEquals(1, executor.awaitCalls)
        assertEquals(1, session.closeCalls)
    }

    private fun createEngine(
        session: FakeOcrSession = FakeOcrSession(),
        runtime: FakeOcrRuntime = FakeOcrRuntime(),
        executor: OcrExecutor = ImmediateOcrExecutor(),
    ): PlateOcrEngine {
        return PlateOcrEngine(
            config = testConfig(),
            runtime = runtime,
            session = session,
            executor = executor,
        )
    }

    private fun captureConstructorFailure(session: FakeOcrSession): IllegalStateException {
        return try {
            createEngine(session = session)
            error("Expected constructor to fail")
        } catch (error: IllegalStateException) {
            error
        }
    }

    private fun invokeRunInference(engine: PlateOcrEngine, bitmap: Bitmap): ScoredOcrCandidate? {
        val method = PlateOcrEngine::class.java.getDeclaredMethod(
            "runInference",
            Bitmap::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(engine, bitmap, "source.png") as ScoredOcrCandidate?
    }

    private fun sentinelResult(): OcrDisplayResult = OcrDisplayResult(
        text = "sentinel",
        sourcePath = "sentinel",
        confidence = 1f,
        agreementCount = 1,
        variantCount = 1,
        scoreMargin = 0f,
    )

    private fun testConfig(): PlateConfig = PlateConfig(
        maxPlateSlots = 3,
        alphabet = "ABC_",
        padChar = '_',
        imgHeight = 2,
        imgWidth = 2,
        keepAspectRatio = false,
        imageColorMode = "rgb",
    )

    private fun writeBitmapFile(bitmap: Bitmap): File {
        val file = File.createTempFile("plate-ocr", ".png")
        file.deleteOnExit()
        FileOutputStream(file).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        return file
    }

    private class ImmediateOcrExecutor : OcrExecutor {
        override fun execute(task: () -> Unit) {
            task()
        }

        override fun shutdownNow() = Unit

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private class QueuedOcrExecutor : OcrExecutor {
        private val tasks = ArrayDeque<() -> Unit>()
        var shutdownNowCalls = 0
        var awaitCalls = 0

        override fun execute(task: () -> Unit) {
            tasks += task
        }

        override fun shutdownNow() {
            shutdownNowCalls += 1
        }

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
            awaitCalls += 1
            return true
        }

        fun runNext() {
            tasks.removeFirst().invoke()
        }
    }

    private class FakeOcrTensor : OcrTensor {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    private class FakeOcrRuntime : OcrRuntime {
        val createdTensors = mutableListOf<FakeOcrTensor>()

        override fun createTensor(buffer: ByteBuffer, shape: LongArray): OcrTensor {
            val tensor = FakeOcrTensor()
            createdTensors += tensor
            return tensor
        }
    }

    private class FakeOcrOutput(
        private val valueByName: Map<String, Any?>,
    ) : OcrOutput, AutoCloseable {
        var closed = false

        override fun value(name: String): Any? = valueByName[name]

        override fun close() {
            closed = true
        }
    }

    private class PlainOcrOutput(
        private val valueByName: Map<String, Any?>,
    ) : OcrOutput {
        override fun value(name: String): Any? = valueByName[name]
    }

    private class FakeOcrSession(
        private val inputInfo: Map<String, OcrValueInfo> = mapOf("input" to OcrValueInfo(isTensor = true)),
        private val outputInfo: Map<String, OcrValueInfo> = mapOf("plate" to OcrValueInfo(isTensor = true)),
        private val outputValue: Any? = null,
        private val runError: Throwable? = null,
        private val outputFactory: (Any?) -> OcrOutput = { value -> FakeOcrOutput(mapOf("plate" to value)) },
    ) : OcrSession {
        var closeCalls = 0
        var runCalls = 0
        var lastOutput: FakeOcrOutput? = null

        override fun inputInfo(): Map<String, OcrValueInfo> = inputInfo

        override fun outputInfo(): Map<String, OcrValueInfo> = outputInfo

        override fun run(inputName: String, tensor: OcrTensor): OcrOutput {
            runCalls += 1
            runError?.let { throw it }
            val output = outputFactory(outputValue)
            if (output is FakeOcrOutput) {
                lastOutput = output
            }
            return output
        }

        override fun close() {
            closeCalls += 1
        }
    }
}
