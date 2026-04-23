package com.focusguard.security

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null

    fun setupAndCaptureSilent(lifecycleOwner: LifecycleOwner, onComplete: (File?) -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageCapture
                )

                takePhoto(onComplete)

            } catch (exc: Exception) {
                Log.e("CameraManager", "Use case binding failed", exc)
                onComplete(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun takePhoto(onComplete: (File?) -> Unit) {
        val imageCapture = imageCapture ?: return onComplete(null)

        val photoFile = File(
            context.getExternalFilesDir(null),
            SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraManager", "Photo capture failed: ${exc.message}", exc)
                    onComplete(null)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("CameraManager", "Photo capture succeeded: ${photoFile.absolutePath}")
                    onComplete(photoFile)
                }
            }
        )
    }
}
