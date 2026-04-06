package com.andre.alprprototype

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.andre.alprprototype.ocr.DecodedPlate
import com.andre.alprprototype.ocr.ModelValidation
import com.andre.alprprototype.ocr.PlateConfig
import com.andre.alprprototype.ocr.PlateOcrMath
import com.andre.alprprototype.ocr.PlateOcrRecognitionFlow
import com.andre.alprprototype.ocr.PreparedInput
import com.andre.alprprototype.ocr.ScoredOcrCandidate
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class OcrDisplayResult(
    val text: String,
    val sourcePath: String,
    val confidence: Float?,
    val agreementCount: Int,
    val variantCount: Int,
    val scoreMargin: Float?,
)

internal interface OcrExecutor {
    fun execute(task: () -> Unit)

    fun shutdownNow()

    fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean
}

internal interface OcrTensor : AutoCloseable

internal interface OcrOutput {
    fun value(name: String): Any?
}

internal data class OcrValueInfo(
    val isTensor: Boolean,
)

internal interface OcrSession : AutoCloseable {
    fun inputInfo(): Map<String, OcrValueInfo>

    fun outputInfo(): Map<String, OcrValueInfo>

    fun run(inputName: String, tensor: OcrTensor): OcrOutput
}

internal interface OcrRuntime {
    fun createTensor(buffer: ByteBuffer, shape: LongArray): OcrTensor
}

internal class ExecutorServiceOcrExecutor(
    private val executor: java.util.concurrent.ExecutorService,
) : OcrExecutor {
    override fun execute(task: () -> Unit) {
        executor.execute(task)
    }

    override fun shutdownNow() {
        executor.shutdownNow()
    }

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = executor.awaitTermination(timeout, unit)
}

internal class OrtOcrTensor(
    private val tensor: OnnxTensor,
) : OcrTensor {
    override fun close() {
        tensor.close()
    }

    internal fun raw(): OnnxTensor = tensor
}

internal class OrtOcrOutput(
    private val output: OrtSession.Result,
) : OcrOutput, AutoCloseable {
    override fun value(name: String): Any? = output.get(name).orElse(null)?.value

    override fun close() {
        output.close()
    }
}

internal class OrtOcrSession(
    private val session: OrtSession,
) : OcrSession {
    override fun inputInfo(): Map<String, OcrValueInfo> = session.inputInfo.mapValues { (_, value) ->
        OcrValueInfo(isTensor = value.info is TensorInfo)
    }

    override fun outputInfo(): Map<String, OcrValueInfo> = session.outputInfo.mapValues { (_, value) ->
        OcrValueInfo(isTensor = value.info is TensorInfo)
    }

    override fun run(inputName: String, tensor: OcrTensor): OcrOutput {
        val ortTensor = tensor as? OrtOcrTensor ?: error("Unexpected tensor type: ${tensor::class.java.name}")
        return OrtOcrOutput(session.run(mapOf(inputName to ortTensor.raw())))
    }

    override fun close() {
        session.close()
    }
}

internal class OrtOcrRuntime(
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : OcrRuntime {
    override fun createTensor(buffer: ByteBuffer, shape: LongArray): OcrTensor =
        OrtOcrTensor(OnnxTensor.createTensor(env, buffer, shape, OnnxJavaType.UINT8))
}

internal object PlateOcrEnvironment {
    var configLoader: (Context) -> PlateConfig = { context -> loadPlateConfig(context, "ocr/plate_config.yaml") }

    var sessionFactory: (Context) -> OcrSession = { context ->
        val session = OrtEnvironment.getEnvironment().createSession(
            copyAssetToCache(context, "ocr/plate_ocr.onnx").absolutePath,
            OrtSession.SessionOptions(),
        )
        OrtOcrSession(session)
    }

    var runtimeFactory: () -> OcrRuntime = { OrtOcrRuntime() }

    var executorFactory: () -> OcrExecutor = {
        ExecutorServiceOcrExecutor(Executors.newSingleThreadExecutor())
    }

    fun reset() {
        configLoader = { context -> loadPlateConfig(context, "ocr/plate_config.yaml") }
        sessionFactory = { context ->
            val session = OrtEnvironment.getEnvironment().createSession(
                copyAssetToCache(context, "ocr/plate_ocr.onnx").absolutePath,
                OrtSession.SessionOptions(),
            )
            OrtOcrSession(session)
        }
        runtimeFactory = { OrtOcrRuntime() }
        executorFactory = { ExecutorServiceOcrExecutor(Executors.newSingleThreadExecutor()) }
    }
}

class PlateOcrEngine internal constructor(
    private val config: PlateConfig,
    private val runtime: OcrRuntime,
    private val session: OcrSession,
    private val executor: OcrExecutor,
) : PlateOcrRecognizer {
    private val tag = "PlateOcrEngine"
    private val isClosed = AtomicBoolean(false)
    private val inputName: String
    private val plateOutputName: String

    init {
        val validation = validateModelContract()
        inputName = validation.inputName
        plateOutputName = validation.plateOutputName
    }

    constructor(context: Context) : this(
        config = PlateOcrEnvironment.configLoader(context),
        runtime = PlateOcrEnvironment.runtimeFactory(),
        session = PlateOcrEnvironment.sessionFactory(context),
        executor = PlateOcrEnvironment.executorFactory(),
    )

    override fun recognize(
        cropPath: String,
        onResult: (OcrDisplayResult?) -> Unit,
    ) {
        if (isClosed.get()) {
            onResult(null)
            return
        }
        executor.execute {
            if (isClosed.get()) {
                onResult(null)
                return@execute
            }
            val totalStartNs = System.nanoTime()
            val result = try {
                val file = File(cropPath)
                val decodeStartNs = System.nanoTime()
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                val decodeDurationMs = nanosToMillis(System.nanoTime() - decodeStartNs)
                if (!file.exists() || bitmap == null) {
                    if (BuildConfig.ALPR_PERF_LOGS_ENABLED) {
                        Log.d("ALPR_PERF", "ocr file=${file.name} decodeMs=$decodeDurationMs result=missing")
                    }
                    null
                } else {
                    var inferenceDurationMs = 0L
                    var variantsTried = 0
                    val recognition = PlateOcrRecognitionFlow.recognize(
                        fileExists = true,
                        filePath = file.absolutePath,
                        bitmap = bitmap,
                        buildVariants = ::buildVariants,
                    ) { variant ->
                        variantsTried += 1
                        val inferenceStartNs = System.nanoTime()
                        runInference(variant, file.absolutePath).also {
                            inferenceDurationMs += nanosToMillis(System.nanoTime() - inferenceStartNs)
                        }
                    }

                    if (BuildConfig.ALPR_PERF_LOGS_ENABLED) {
                        val totalDurationMs = nanosToMillis(System.nanoTime() - totalStartNs)
                        Log.d(
                            "ALPR_PERF",
                            "ocr file=${file.name} decodeMs=$decodeDurationMs inferMs=$inferenceDurationMs " +
                                "variants=$variantsTried agree=${recognition?.agreementCount ?: 0} margin=${recognition?.scoreMargin ?: -1f} " +
                                "text='${recognition?.text ?: ""}' totalMs=$totalDurationMs",
                        )
                    }

                    recognition
                }
            } catch (t: Throwable) {
                Log.e(tag, "ocr failed for path=$cropPath", t)
                null
            }
            onResult(result)
        }
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) {
            return
        }
        executor.shutdownNow()
        executor.awaitTermination(300, TimeUnit.MILLISECONDS)
        session.close()
    }

    private fun runInference(bitmap: Bitmap, sourcePath: String): ScoredOcrCandidate? {
        val prepared = preprocess(bitmap)
        runtime.createTensor(prepared.buffer, prepared.shape).use { inputTensor ->
            val output = session.run(inputName, inputTensor)
            (output as? AutoCloseable).use {
                val logits = extractPlateLogits(output.value(plateOutputName)) ?: return null
                val decoded = decodeFixedSlots(logits)
                val finalText = normalizePlateText(decoded.rawText)
                if (finalText.isBlank()) return null

                return ScoredOcrCandidate(
                    text = finalText,
                    score = scoreCandidate(finalText, decoded.averageConfidence),
                    confidence = decoded.averageConfidence,
                )
            }
        }
    }

    private fun preprocess(bitmap: Bitmap): PreparedInput = PlateOcrMath.preprocess(bitmap, config)

    private fun extractPlateLogits(rawValue: Any?): Array<FloatArray>? = PlateOcrMath.extractPlateLogits(rawValue)

    private fun decodeFixedSlots(logits: Array<FloatArray>): DecodedPlate = PlateOcrMath.decodeFixedSlots(logits, config)

    private fun normalizePlateText(raw: String): String = PlateOcrMath.normalizePlateText(raw, config.padChar)

    private fun scoreCandidate(text: String, averageConfidence: Float): Float = PlateOcrMath.scoreCandidate(text, averageConfidence)

    private fun validateModelContract(): ModelValidation {
        val inputEntry = session.inputInfo().entries.firstOrNull() ?: throw IllegalStateException("OCR model has no inputs")
        if (!inputEntry.value.isTensor) {
            throw IllegalStateException("OCR model input is not a tensor")
        }
        val plateEntry = session.outputInfo()["plate"] ?: throw IllegalStateException("OCR model missing 'plate' output")
        if (!plateEntry.isTensor) {
            throw IllegalStateException("OCR model 'plate' output is not a tensor")
        }

        return ModelValidation(
            inputName = inputEntry.key,
            plateOutputName = "plate",
        )
    }

    private fun buildVariants(bitmap: Bitmap): List<Bitmap> = PlateOcrMath.buildVariants(bitmap)

    private fun focusedBandCrop(source: Bitmap, topFraction: Float, bottomFraction: Float): Bitmap? =
        PlateOcrMath.focusedBandCrop(source, topFraction, bottomFraction)
}

private fun loadPlateConfig(context: Context, assetPath: String): PlateConfig {
    val values = mutableMapOf<String, String>()
    context.assets.open(assetPath).bufferedReader().useLines { lines ->
        lines.forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isBlank() || ':' !in line) return@forEach
            val key = line.substringBefore(':').trim()
            val value = line.substringAfter(':').trim()
            values[key] = value
        }
    }

    fun required(key: String): String = values[key] ?: throw IllegalStateException("Missing '$key' in $assetPath")

    return PlateConfig(
        maxPlateSlots = required("max_plate_slots").toInt(),
        alphabet = required("alphabet").trim('\'', '"'),
        padChar = required("pad_char").trim('\'', '"').first(),
        imgHeight = required("img_height").toInt(),
        imgWidth = required("img_width").toInt(),
        keepAspectRatio = required("keep_aspect_ratio").toBooleanStrict(),
        imageColorMode = required("image_color_mode").trim('\'', '"').lowercase(),
    )
}

private fun copyAssetToCache(context: Context, assetPath: String): File {
    val outFile = File(context.cacheDir, assetPath.substringAfterLast('/'))
    context.assets.open(assetPath).use { input ->
        outFile.outputStream().use { output -> input.copyTo(output) }
    }
    return outFile
}
