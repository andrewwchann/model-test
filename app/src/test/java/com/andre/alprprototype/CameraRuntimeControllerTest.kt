package com.andre.alprprototype

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import com.andre.alprprototype.alpr.AlprPipeline
import com.andre.alprprototype.alpr.PlateCandidate
import com.andre.alprprototype.alpr.PlateCandidateGenerator
import com.andre.alprprototype.databinding.ActivityCameraBinding
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class CameraRuntimeControllerTest {
    @After
    fun tearDown() {
        CameraRuntimeEnvironment.reset()
        CameraActivityDependencies.reset()
    }

    @Test
    fun startCamera_returns_early_when_camera_cannot_be_used_before_provider_resolves() {
        val future = FakeProviderFuture(FakeCameraProviderSession())
        val activity = buildActivity().get()
        val binding = ReflectionHelpers.getField<ActivityCameraBinding>(activity, "binding")

        runStartCamera(
            activity = activity,
            binding = binding,
            providerFuture = future,
            canUseCamera = { false },
        )

        assertEquals(0, future.getCalls)
        assertNull(ReflectionHelpers.getField<CameraProviderSession?>(activity, "cameraProvider"))
    }

    @Test
    fun startCamera_unbinds_and_stops_when_camera_becomes_unavailable_after_provider_resolves() {
        val provider = FakeCameraProviderSession()
        val future = FakeProviderFuture(provider)
        val activity = buildActivity().get()
        val binding = ReflectionHelpers.getField<ActivityCameraBinding>(activity, "binding")
        var checks = 0

        runStartCamera(
            activity = activity,
            binding = binding,
            providerFuture = future,
            canUseCamera = {
                checks += 1
                checks == 1
            },
        )

        assertEquals(1, future.getCalls)
        assertEquals(1, provider.unbindAllCalls)
        assertEquals(0, provider.bindCalls)
        assertNull(ReflectionHelpers.getField<CameraProviderSession?>(activity, "cameraProvider"))
    }

    @Test
    fun startCamera_skips_bind_when_camera_becomes_unavailable_after_unbind() {
        val provider = FakeCameraProviderSession()
        val future = FakeProviderFuture(provider)
        val activity = buildActivity().get()
        val binding = ReflectionHelpers.getField<ActivityCameraBinding>(activity, "binding")
        val preview = Preview.Builder().build()
        val imageCapture = ImageCapture.Builder().build()
        val analysis = ImageAnalysis.Builder().build()
        var attachedAnalysis: ImageAnalysis? = null
        var checks = 0

        runStartCamera(
            activity = activity,
            binding = binding,
            providerFuture = future,
            preview = preview,
            imageCapture = imageCapture,
            analysis = analysis,
            canUseCamera = {
                checks += 1
                checks < 3
            },
            attachAnalyzer = { attachedAnalysis = it },
        )

        assertEquals(1, provider.unbindAllCalls)
        assertEquals(0, provider.bindCalls)
        assertSame(analysis, attachedAnalysis)
        assertSame(provider, ReflectionHelpers.getField<CameraProviderSession?>(activity, "cameraProvider"))
        assertSame(analysis, ReflectionHelpers.getField<ImageAnalysis?>(activity, "analysisUseCase"))
        assertSame(imageCapture, ReflectionHelpers.getField<ImageCapture?>(activity, "imageCapture"))
    }

    @Test
    fun startCamera_binds_and_starts_session_when_camera_remains_available() {
        val provider = FakeCameraProviderSession()
        val future = FakeProviderFuture(provider)
        val activity = buildActivity().get()
        val binding = ReflectionHelpers.getField<ActivityCameraBinding>(activity, "binding")
        val preview = Preview.Builder().build()
        val imageCapture = ImageCapture.Builder().build()
        val analysis = ImageAnalysis.Builder().build()
        var attachedAnalysis: ImageAnalysis? = null

        runStartCamera(
            activity = activity,
            binding = binding,
            providerFuture = future,
            preview = preview,
            imageCapture = imageCapture,
            analysis = analysis,
            canUseCamera = { true },
            attachAnalyzer = { attachedAnalysis = it },
        )

        assertEquals(1, future.getCalls)
        assertEquals(1, provider.unbindAllCalls)
        assertEquals(1, provider.bindCalls)
        assertSame(activity, provider.lastActivity)
        assertSame(preview, provider.lastPreview)
        assertSame(analysis, provider.lastAnalysis)
        assertSame(imageCapture, provider.lastImageCapture)
        assertSame(analysis, attachedAnalysis)
        assertSame(provider, ReflectionHelpers.getField<CameraProviderSession?>(activity, "cameraProvider"))
    }

    @Test
    fun startCamera_swallows_provider_failures() {
        val future = FakeProviderFuture(
            value = null,
            failure = IllegalStateException("boom"),
        )
        val activity = buildActivity().get()
        val binding = ReflectionHelpers.getField<ActivityCameraBinding>(activity, "binding")

        runStartCamera(
            activity = activity,
            binding = binding,
            providerFuture = future,
            canUseCamera = { true },
        )

        assertEquals(1, future.getCalls)
        assertNull(ReflectionHelpers.getField<CameraProviderSession?>(activity, "cameraProvider"))
        assertNull(ReflectionHelpers.getField<ImageAnalysis?>(activity, "analysisUseCase"))
        assertNull(ReflectionHelpers.getField<ImageCapture?>(activity, "imageCapture"))
    }

    @Test
    fun clearAnalyzer_ignores_null_use_case() {
        DefaultCameraRuntimeController.clearAnalyzer(null)
    }

    @Test
    fun clearAnalyzer_clears_existing_use_case() {
        val imageAnalysis = ImageAnalysis.Builder().build()

        DefaultCameraRuntimeController.clearAnalyzer(imageAnalysis)
    }

    private fun runStartCamera(
        activity: CameraActivity,
        binding: ActivityCameraBinding,
        providerFuture: FakeProviderFuture,
        preview: Preview = Preview.Builder().build(),
        imageCapture: ImageCapture = ImageCapture.Builder().build(),
        analysis: ImageAnalysis = ImageAnalysis.Builder().build(),
        canUseCamera: () -> Boolean,
        attachAnalyzer: (ImageAnalysis) -> Unit = {},
    ) {
        CameraRuntimeEnvironment.providerFutureFactory = { providerFuture }
        CameraRuntimeEnvironment.previewFactory = { preview }
        CameraRuntimeEnvironment.imageCaptureFactory = { imageCapture }
        CameraRuntimeEnvironment.analysisFactory = { callback ->
            callback(analysis)
            analysis
        }

        val cameraExecutor = Executors.newSingleThreadExecutor()
        try {
            DefaultCameraRuntimeController.startCamera(
                activity = activity,
                binding = binding,
                mainExecutor = Executor { runnable -> runnable.run() },
                cameraExecutor = cameraExecutor,
                pipeline = AlprPipeline(FakePlateCandidateGenerator()),
                cropSaver = com.andre.alprprototype.alpr.BestPlateCropSaver(activity),
                canUseCamera = canUseCamera,
                attachAnalyzer = attachAnalyzer,
            )
        } finally {
            cameraExecutor.shutdownNow()
        }
    }

    private fun buildActivity(): ActivityController<CameraActivity> {
        CameraActivityDependencies.pipelineFactory = { AlprPipeline(FakePlateCandidateGenerator()) }
        CameraActivityDependencies.plateOcrRecognizerFactory = { FakePlateOcrRecognizer() }
        CameraActivityDependencies.registryManagerFactory = {
            object : PlateRegistry {
                override fun isPlateValid(plateText: String): RegistryManager.PlateValidationResult {
                    return RegistryManager.PlateValidationResult.NOT_FOUND
                }

                override suspend fun syncRegistry(): Result<Int> = Result.success(0)
            }
        }
        CameraActivityDependencies.violationManagerFactory = {
            object : ViolationQueue {
                override fun addViolation(violation: ViolationEvent): Result<ViolationEvent> = Result.success(violation)

                override fun getQueueSize(): Int = 0

                override suspend fun uploadQueue(): Result<Int> = Result.success(0)
            }
        }
        CameraActivityDependencies.cameraRuntimeController = object : CameraRuntimeController {
            override fun startCamera(
                activity: CameraActivity,
                binding: ActivityCameraBinding,
                mainExecutor: Executor,
                cameraExecutor: ExecutorService,
                pipeline: AlprPipeline,
                cropSaver: com.andre.alprprototype.alpr.BestPlateCropSaver,
                canUseCamera: () -> Boolean,
                attachAnalyzer: (ImageAnalysis) -> Unit,
            ) = Unit

            override fun clearAnalyzer(imageAnalysis: ImageAnalysis?) = Unit
        }
        return Robolectric.buildActivity(CameraActivity::class.java).setup()
    }

    private class FakeProviderFuture(
        private val value: CameraProviderSession? = null,
        private val failure: Throwable? = null,
    ) : ListenableFuture<CameraProviderSession> {
        var getCalls = 0

        override fun addListener(listener: Runnable, executor: Executor) {
            executor.execute(listener)
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

        override fun isCancelled(): Boolean = false

        override fun isDone(): Boolean = true

        override fun get(): CameraProviderSession {
            getCalls += 1
            failure?.let { throw it }
            return checkNotNull(value)
        }

        override fun get(timeout: Long, unit: TimeUnit): CameraProviderSession = get()
    }

    private class FakeCameraProviderSession : CameraProviderSession {
        var unbindAllCalls = 0
        var bindCalls = 0
        var lastActivity: CameraActivity? = null
        var lastPreview: Preview? = null
        var lastAnalysis: ImageAnalysis? = null
        var lastImageCapture: ImageCapture? = null

        override fun unbindAll() {
            unbindAllCalls += 1
        }

        override fun bindToLifecycle(
            activity: CameraActivity,
            preview: Preview,
            analysis: ImageAnalysis,
            imageCapture: ImageCapture,
        ) {
            bindCalls += 1
            lastActivity = activity
            lastPreview = preview
            lastAnalysis = analysis
            lastImageCapture = imageCapture
        }
    }

    private class FakePlateCandidateGenerator : PlateCandidateGenerator {
        override val name: String = "runtime-test"

        override fun generate(
            image: androidx.camera.core.ImageProxy,
            uprightBitmapProvider: (() -> android.graphics.Bitmap?)?,
        ): List<PlateCandidate> = emptyList()
    }

    private class FakePlateOcrRecognizer : PlateOcrRecognizer {
        override fun recognize(cropPath: String, onResult: (OcrDisplayResult?) -> Unit) = Unit

        override fun close() = Unit
    }
}
