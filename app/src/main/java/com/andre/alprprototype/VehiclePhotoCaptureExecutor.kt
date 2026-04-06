package com.andre.alprprototype

import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import java.io.File
import java.util.concurrent.Executor

internal interface VehiclePhotoCaptureInvoker {
    fun takePicture(
        imageCapture: ImageCapture,
        outputOptions: ImageCapture.OutputFileOptions,
        executor: Executor,
        callback: ImageCapture.OnImageSavedCallback,
    )
}

internal object DefaultVehiclePhotoCaptureInvoker : VehiclePhotoCaptureInvoker {
    override fun takePicture(
        imageCapture: ImageCapture,
        outputOptions: ImageCapture.OutputFileOptions,
        executor: Executor,
        callback: ImageCapture.OnImageSavedCallback,
    ) {
        imageCapture.takePicture(outputOptions, executor, callback)
    }
}

internal interface VehiclePhotoCaptureExecutor {
    fun capture(
        imageCapture: ImageCapture,
        photoFile: File,
        executor: Executor,
        onSaved: () -> Unit,
        onError: (ImageCaptureException) -> Unit,
    )
}

internal object DefaultVehiclePhotoCaptureExecutor : VehiclePhotoCaptureExecutor {
    internal var invoker: VehiclePhotoCaptureInvoker = DefaultVehiclePhotoCaptureInvoker

    override fun capture(
        imageCapture: ImageCapture,
        photoFile: File,
        executor: Executor,
        onSaved: () -> Unit,
        onError: (ImageCaptureException) -> Unit,
    ) {
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        invoker.takePicture(
            imageCapture = imageCapture,
            outputOptions = outputOptions,
            executor = executor,
            callback = object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onSaved()
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            },
        )
    }
}
