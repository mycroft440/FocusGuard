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
import java.util.concurrent.atomic.AtomicBoolean

class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    
    // Guard against multiple simultaneous capture attempts
    private val isCapturing = AtomicBoolean(false)

    fun setupAndCaptureSilent(lifecycleOwner: LifecycleOwner, onComplete: (File?) -> Unit) {
        // Prevent concurrent capture attempts that would cause CameraX race conditions
        if (!isCapturing.compareAndSet(false, true)) {
            Log.w("CameraManager", "Capture already in progress, skipping")
            onComplete(null)
            return
        }
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                // Check if front camera is available before trying to bind
                val cameraSelector = try {
                    val hasCamera = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                    if (hasCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        Log.w("CameraManager", "No front camera available, trying back camera")
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                } catch (e: Exception) {
                    Log.e("CameraManager", "Camera check failed, falling back to back camera", e)
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageCapture
                )

                takePhoto { file ->
                    // Unbind after capture to release camera resources
                    try {
                        cameraProvider.unbindAll()
                    } catch (_: Exception) {}
                    isCapturing.set(false)
                    onComplete(file)
                }

            } catch (exc: Exception) {
                Log.e("CameraManager", "Use case binding failed", exc)
                isCapturing.set(false)
                onComplete(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun takePhoto(onComplete: (File?) -> Unit) {
        val imageCapture = imageCapture ?: run {
            onComplete(null)
            return
        }

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
