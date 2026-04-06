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
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

internal data class CameraSessionStartResult(
    val provider: CameraProviderSession? = null,
    val analysisUseCase: ImageAnalysis? = null,
    val imageCapture: ImageCapture? = null,
)

internal interface CameraProviderSession {
    fun unbindAll()

    fun bindToLifecycle(
        activity: CameraActivity,
        preview: Preview,
        analysis: ImageAnalysis,
        imageCapture: ImageCapture,
    )
}

internal class DefaultCameraProviderSession(
    private val provider: ProcessCameraProvider,
) : CameraProviderSession {
    override fun unbindAll() {
        provider.unbindAll()
    }

    override fun bindToLifecycle(
        activity: CameraActivity,
        preview: Preview,
        analysis: ImageAnalysis,
        imageCapture: ImageCapture,
    ) {
        provider.bindToLifecycle(
            activity,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
            imageCapture,
        )
    }
}

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

internal object CameraRuntimeEnvironment {
    var providerFutureFactory: (CameraActivity) -> ListenableFuture<CameraProviderSession> =
        { activity ->
            MappingListenableFuture(
                delegate = ProcessCameraProvider.getInstance(activity),
                mapper = ::DefaultCameraProviderSession,
            )
        }

    var previewFactory: (ActivityCameraBinding) -> Preview = { binding ->
        Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
    }

    var imageCaptureFactory: () -> ImageCapture = {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    var analysisFactory: ((ImageAnalysis) -> Unit) -> ImageAnalysis = { attachAnalyzer ->
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

        ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also(attachAnalyzer)
    }

    fun reset() {
        providerFutureFactory = { activity ->
            MappingListenableFuture(
                delegate = ProcessCameraProvider.getInstance(activity),
                mapper = ::DefaultCameraProviderSession,
            )
        }
        previewFactory = { binding ->
            Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
        }
        imageCaptureFactory = {
            ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
        }
        analysisFactory = { attachAnalyzer ->
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

            ImageAnalysis.Builder()
                .setResolutionSelector(analysisResolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also(attachAnalyzer)
        }
    }
}

private class MappingListenableFuture<T, R>(
    private val delegate: ListenableFuture<T>,
    private val mapper: (T) -> R,
) : ListenableFuture<R> {
    override fun addListener(listener: Runnable, executor: Executor) {
        delegate.addListener(listener, executor)
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = delegate.cancel(mayInterruptIfRunning)

    override fun isCancelled(): Boolean = delegate.isCancelled

    override fun isDone(): Boolean = delegate.isDone

    override fun get(): R = mapper(delegate.get())

    override fun get(timeout: Long, unit: TimeUnit): R = mapper(delegate.get(timeout, unit))
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
        val cameraProviderFuture = CameraRuntimeEnvironment.providerFutureFactory(activity)
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

                val preview = CameraRuntimeEnvironment.previewFactory(binding)
                val imageCapture = CameraRuntimeEnvironment.imageCaptureFactory()
                val analysis = CameraRuntimeEnvironment.analysisFactory(attachAnalyzer)

                cameraProvider.unbindAll()
                if (canUseCamera()) {
                    cameraProvider.bindToLifecycle(activity, preview, analysis, imageCapture)
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
