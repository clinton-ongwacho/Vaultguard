package com.example.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Silently captures a single front-camera snapshot when a restricted app is opened,
 * then immediately unbinds CameraX so camera hardware is freed right away.
 */
object IntruderPhotoCapturer {

    private const val TAG = "IntruderPhotoCapturer"
    private val ioExecutor = Executors.newSingleThreadExecutor()

    suspend fun captureFrontPhoto(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        targetPackage: String,
    ): String? {
        val appContext = context.applicationContext
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val cameraProvider = runCatching { getCameraProvider(appContext) }.getOrNull() ?: return null

        val selector = when {
            runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }
                .getOrDefault(false) -> CameraSelector.DEFAULT_FRONT_CAMERA
            runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }
                .getOrDefault(false) -> CameraSelector.DEFAULT_BACK_CAMERA
            else -> return null
        }

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()

        val bound = withContext(Dispatchers.Main.immediate) {
            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, imageCapture)
                true
            }.getOrElse { err ->
                Log.w(TAG, "Failed to bind CameraX for session photo", err)
                false
            }
        }
        if (!bound) return null

        // Brief AE/AWB settle delay so cold camera frames are properly exposed.
        delay(350L)

        val photosDir = File(appContext.filesDir, "session_photos").apply { mkdirs() }
        val safePkg = targetPackage.replace(Regex("[^A-Za-z0-9._-]"), "_").take(32)
        val outFile = File(photosDir, "session_${System.currentTimeMillis()}_$safePkg.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outFile).build()

        return try {
            takePictureSuspend(imageCapture, outputOptions, outFile)
        } finally {
            withContext(Dispatchers.Main.immediate) {
                runCatching { cameraProvider.unbindAll() }
            }
        }
    }

    private suspend fun getCameraProvider(context: Context): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    if (!cont.isActive) return@addListener
                    runCatching { future.get() }
                        .onSuccess { cont.resume(it) }
                        .onFailure { cont.resumeWith(Result.failure(it)) }
                },
                ContextCompat.getMainExecutor(context),
            )
        }

    private suspend fun takePictureSuspend(
        imageCapture: ImageCapture,
        outputOptions: ImageCapture.OutputFileOptions,
        outFile: File,
    ): String? = suspendCancellableCoroutine { cont: CancellableContinuation<String?> ->
        imageCapture.takePicture(
            outputOptions,
            ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (cont.isActive) {
                        val savedPath = if (outFile.exists() && outFile.length() > 0L) {
                            outFile.absolutePath
                        } else {
                            null
                        }
                        cont.resume(savedPath)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "Automatic session photo capture failed: ${exception.message}")
                    runCatching { outFile.delete() }
                    if (cont.isActive) {
                        cont.resume(null)
                    }
                }
            },
        )
    }
}
