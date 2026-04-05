package com.andre.alprprototype

import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.andre.alprprototype.alpr.AlprPipeline
import com.andre.alprprototype.alpr.BestPlateCropSaver
import com.andre.alprprototype.databinding.ActivityCameraBinding
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

internal data class CameraSessionStartResult(
    val provider: ProcessCameraProvider? = null,
    val analysisUseCase: ImageAnalysis? = null,
    val imageCapture: ImageCapture? = null,
)

internal interface CameraRuntimeController {
    fun startCamera(
        activity: CameraActivity,
        binding: ActivityCameraBinding,
        mainExecutor: Executor,
        cameraExecutor: ExecutorService,
        pipeline: AlprPipeline,
        cropSaver: BestPlateCropSaver,
        canUseCamera: () -> Boolean,
        attachAnalyzer: (ImageAnalysis) -> Unit,
    )

    fun clearAnalyzer(imageAnalysis: ImageAnalysis?)
}

internal object DefaultCameraRuntimeController : CameraRuntimeController {
    override fun startCamera(
        activity: CameraActivity,
        binding: ActivityCameraBinding,
        mainExecutor: Executor,
        cameraExecutor: ExecutorService,
        pipeline: AlprPipeline,
        cropSaver: BestPlateCropSaver,
        canUseCamera: () -> Boolean,
        attachAnalyzer: (ImageAnalysis) -> Unit,
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
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

                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val analysisResolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(800, 600),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .setAspectRatioStrategy(
                        AspectRatioStrategy(
                            AspectRatio.RATIO_4_3,
                            AspectRatioStrategy.FALLBACK_RULE_AUTO,
                        ),
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisResolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also(attachAnalyzer)

                cameraProvider.unbindAll()
                if (canUseCamera()) {
                    cameraProvider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, imageCapture)
                }
                activity.onCameraSessionStarted(
                    CameraSessionStartResult(
                        provider = cameraProvider,
                        analysisUseCase = analysis,
                        imageCapture = imageCapture,
                    ),
                )
            } catch (_: Throwable) {
                // Activity may already be finishing/destroyed when camera provider resolves.
            }
        }, mainExecutor)
    }

    override fun clearAnalyzer(imageAnalysis: ImageAnalysis?) {
        imageAnalysis?.clearAnalyzer()
    }
}
