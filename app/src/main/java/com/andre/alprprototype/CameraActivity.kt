package com.andre.alprprototype

import android.Manifest
import android.graphics.BitmapFactory
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.andre.alprprototype.alpr.AlprPipeline
import com.andre.alprprototype.alpr.BestPlateCropSaver
import com.andre.alprprototype.alpr.YoloTflitePlateCandidateGenerator
import com.andre.alprprototype.databinding.ActivityCameraBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CameraActivity : AppCompatActivity() {
    private enum class OcrUiState {
        IDLE,
        PENDING,
        READY,
        UNAVAILABLE,
    }

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var cropSaver: BestPlateCropSaver
    private lateinit var pipeline: AlprPipeline
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var latestFrame: AnalyzedFrame? = null
    private var lastSavedCropPath: String? = null
    private var lastOcrRequestedPath: String? = null
    private var latestOcrResult: OcrDisplayResult? = null
    private var latestOcrState: OcrUiState = OcrUiState.IDLE
    private var detectorSelfTestSummary: String = "Self-test: pending"
    private lateinit var plateOcrEngine: PlateOcrEngine
    private var isStatusExpanded: Boolean = false
    private val isShuttingDown = AtomicBoolean(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        cropSaver = BestPlateCropSaver(this)
        pipeline = AlprPipeline.create(this)
        plateOcrEngine = PlateOcrEngine(this)
        binding.sessionStatus.text = "Detector loaded: ${pipeline.detectorName}\n$detectorSelfTestSummary\nOpening camera stream and ALPR debug pipeline..."
        runDetectorSelfTest()
        applyStatusPanelState()

        binding.closeButton.setOnClickListener { finish() }
        binding.sessionStatusHeader.setOnClickListener {
            isStatusExpanded = !isStatusExpanded
            applyStatusPanelState()
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            if (!canUseCamera()) {
                return@addListener
            }
            try {
                val cameraProvider = cameraProviderFuture.get()
                if (!canUseCamera()) {
                    cameraProvider.unbindAll()
                    return@addListener
                }
                this.cameraProvider = cameraProvider

                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { imageAnalysis ->
                        analysisUseCase = imageAnalysis
                        imageAnalysis.setAnalyzer(
                            cameraExecutor,
                            PlateFrameAnalyzer(pipeline, cropSaver) { frame ->
                                runOnUiThread {
                                    if (!canUpdateUi()) {
                                        return@runOnUiThread
                                    }
                                    latestFrame = frame
                                    if (frame.savedCropPath != null) {
                                        lastSavedCropPath = frame.savedCropPath
                                        updateCropPreview(frame.savedCropPath)
                                        requestOcrIfNeeded(frame.savedCropPath)
                                    }
                                    binding.sessionStatus.text = buildStatusText(frame)
                                    binding.debugOverlay.render(frame.state)
                                }
                            },
                        )
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                if (canUseCamera()) {
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, analysis)
                }
            } catch (_: Throwable) {
                // Activity may already be finishing/destroyed when camera provider resolves.
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        isShuttingDown.set(true)
        analysisUseCase?.clearAnalyzer()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdownNow()
        try {
            cameraExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        pipeline.close()
        plateOcrEngine.close()
        super.onDestroy()
    }

    private fun buildStatusText(frame: AnalyzedFrame): String {
        val savedText = lastSavedCropPath ?: "none yet"
        val ocrText = when (latestOcrState) {
            OcrUiState.IDLE -> "idle"
            OcrUiState.PENDING -> "pending"
            OcrUiState.READY -> latestOcrResult?.text ?: "ready"
            OcrUiState.UNAVAILABLE -> "unavailable"
        }
        return frame.state.statusText() +
            "\n$detectorSelfTestSummary" +
            "\nSaved crop: $savedText" +
            "\nPlate OCR: $ocrText"
    }

    private fun runDetectorSelfTest() {
        val detector = YoloTflitePlateCandidateGenerator.createOrNull(this)
        if (detector == null) {
            detectorSelfTestSummary = "Self-test: detector unavailable"
            binding.sessionStatus.text = "Detector loaded: ${pipeline.detectorName}\n$detectorSelfTestSummary\nOpening camera stream and ALPR debug pipeline..."
            return
        }

        cameraExecutor.execute {
            val summary = try {
                val car1 = assets.open("debug-samples/car1.png").use { BitmapFactory.decodeStream(it) }
                val car2 = assets.open("debug-samples/car2.png").use { BitmapFactory.decodeStream(it) }
                val car1Top = car1?.let { detector.detectBitmap(it).maxByOrNull { c -> c.confidence } }
                val car2Top = car2?.let { detector.detectBitmap(it).maxByOrNull { c -> c.confidence } }
                val car1Size = "${car1Top?.boundingBox?.width()?.toInt() ?: 0}x${car1Top?.boundingBox?.height()?.toInt() ?: 0}"
                val car2Size = "${car2Top?.boundingBox?.width()?.toInt() ?: 0}x${car2Top?.boundingBox?.height()?.toInt() ?: 0}"
                val car1Conf = car1Top?.confidence?.let { String.format("%.2f", it) } ?: "-"
                val car2Conf = car2Top?.confidence?.let { String.format("%.2f", it) } ?: "-"
                "Self-test car1=$car1Size c=$car1Conf | car2=$car2Size c=$car2Conf"
            } catch (_: Exception) {
                "Self-test: failed"
            } finally {
                detector.close()
            }

            runOnUiThread {
                if (!canUpdateUi()) {
                    return@runOnUiThread
                }
                detectorSelfTestSummary = summary
                latestFrame?.let { binding.sessionStatus.text = buildStatusText(it) }
                    ?: run {
                        binding.sessionStatus.text = "Detector loaded: ${pipeline.detectorName}\n$detectorSelfTestSummary\nOpening camera stream and ALPR debug pipeline..."
                    }
            }
        }
    }

    private fun requestOcrIfNeeded(cropPath: String) {
        if (cropPath == lastOcrRequestedPath) {
            return
        }
        lastOcrRequestedPath = cropPath
        latestOcrResult = null
        latestOcrState = OcrUiState.PENDING
        updateCropCaption()
        plateOcrEngine.recognize(cropPath) { result ->
            runOnUiThread {
                if (!canUpdateUi() || cropPath != lastOcrRequestedPath) {
                    return@runOnUiThread
                }
                latestOcrResult = result
                latestOcrState = if (result?.text.isNullOrBlank()) OcrUiState.UNAVAILABLE else OcrUiState.READY
                updateCropCaption()
                latestFrame?.let { frame ->
                    binding.sessionStatus.text = buildStatusText(frame)
                }
            }
        }
    }

    private fun updateCropPreview(cropPath: String) {
        val bitmap = BitmapFactory.decodeFile(cropPath)
        binding.latestCropImage.setImageBitmap(bitmap)
        updateCropCaption()
    }

    private fun updateCropCaption() {
        val ocrText = when {
            latestOcrResult?.text != null -> latestOcrResult!!.text
            lastSavedCropPath != null && latestOcrState == OcrUiState.PENDING -> getString(R.string.latest_crop_ocr_pending)
            lastSavedCropPath != null -> getString(R.string.latest_crop_ocr_unavailable)
            else -> getString(R.string.latest_crop_empty)
        }
        binding.latestCropCaption.text = ocrText
    }

    private fun applyStatusPanelState() {
        binding.sessionStatus.visibility = if (isStatusExpanded) android.view.View.VISIBLE else android.view.View.GONE
        binding.sessionStatusToggle.setText(
            if (isStatusExpanded) R.string.session_status_collapse else R.string.session_status_expand,
        )
    }

    private fun canUpdateUi(): Boolean {
        return !isShuttingDown.get() && !isDestroyed && !isFinishing
    }

    private fun canUseCamera(): Boolean {
        return !isShuttingDown.get() && !isDestroyed && !isFinishing
    }
}
