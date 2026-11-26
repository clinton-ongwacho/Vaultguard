package com.example.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Captures a front-camera snapshot whenever a restricted app is opened.
 * Uses a mutex, lifecycle STARTED synchronization, an ImageAnalysis warm-up pipeline
 * with live YUV frame fallback, and retries so restricted sessions do not miss photos.
 */
object IntruderPhotoCapturer {

    private const val TAG = "IntruderPhotoCapturer"
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val captureMutex = Mutex()

    private data class CapturedYuvFrame(
        val nv21: ByteArray,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
    )

    suspend fun captureFrontPhoto(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        targetPackage: String,
    ): String? = captureMutex.withLock {
        val appContext = context.applicationContext
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withLock null
        }

        val started = awaitLifecycleStarted(lifecycleOwner, timeoutMs = 1_800L)
        if (!started) {
            Log.w(TAG, "Lifecycle never reached STARTED for session photo capture")
            return@withLock null
        }

        val cameraProvider = runCatching { getCameraProvider(appContext) }.getOrNull() ?: return@withLock null

        val selector = when {
            runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }
                .getOrDefault(false) -> CameraSelector.DEFAULT_FRONT_CAMERA
            runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }
                .getOrDefault(false) -> CameraSelector.DEFAULT_BACK_CAMERA
            else -> return@withLock null
        }

        val photosDir = File(appContext.filesDir, "session_photos").apply { mkdirs() }
        val safePkg = targetPackage.replace(Regex("[^A-Za-z0-9._-]"), "_").take(32)

        for (attempt in 1..2) {
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                break
            }
            val outFile = File(photosDir, "session_${System.currentTimeMillis()}_$safePkg.jpg")
            val captured = attemptSingleCapture(
                cameraProvider = cameraProvider,
                lifecycleOwner = lifecycleOwner,
                selector = selector,
                outFile = outFile,
                settleDelayMs = if (attempt == 1) 240L else 420L,
            )
            if (captured != null) {
                return@withLock captured
            }
            runCatching { outFile.delete() }
            if (attempt < 2) {
                delay(180L)
            }
        }
        null
    }

    private suspend fun attemptSingleCapture(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        selector: CameraSelector,
        outFile: File,
        settleDelayMs: Long,
    ): String? {
        val firstFrameSeen = AtomicBoolean(false)
        val latestFrame = AtomicReference<CapturedYuvFrame?>(null)

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .apply {
                setAnalyzer(ioExecutor) { imageProxy ->
                    try {
                        val nv21 = yuv420ToNv21(imageProxy)
                        latestFrame.set(
                            CapturedYuvFrame(
                                nv21 = nv21,
                                width = imageProxy.width,
                                height = imageProxy.height,
                                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                            )
                        )
                        firstFrameSeen.set(true)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed extracting preview analysis frame", t)
                    } finally {
                        imageProxy.close()
                    }
                }
            }

        var boundBoth = false
        val bound = withContext(Dispatchers.Main.immediate) {
            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, imageCapture, imageAnalysis)
                boundBoth = true
                true
            }.recoverCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, imageCapture)
                true
            }.getOrElse { err ->
                Log.w(TAG, "Failed to bind CameraX for session photo", err)
                false
            }
        }
        if (!bound) return null

        try {
            if (boundBoth) {
                val deadline = System.currentTimeMillis() + 900L
                while (!firstFrameSeen.get() && System.currentTimeMillis() < deadline) {
                    if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        break
                    }
                    delay(40L)
                }
            }
            // Brief AE/AWB settle delay after active stream begins.
            delay(settleDelayMs)

            val outputOptions = ImageCapture.OutputFileOptions.Builder(outFile).build()
            val primarySaved = withTimeoutOrNull(2_500L) {
                takePictureSuspend(imageCapture, outputOptions, outFile)
            }
            if (primarySaved != null) {
                return primarySaved
            }

            // Second quick still-capture try while hardware is already warmed up.
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                delay(140L)
                val secondSaved = withTimeoutOrNull(2_200L) {
                    takePictureSuspend(imageCapture, outputOptions, outFile)
                }
                if (secondSaved != null) {
                    return secondSaved
                }
            }

            // Fallback: encode latest live YUV frame from ImageAnalysis if still-capture pipeline failed.
            val frame = latestFrame.get()
            if (frame != null) {
                val fallbackPath = withContext(Dispatchers.IO) {
                    writeYuvFrameToJpeg(frame, outFile)
                }
                if (fallbackPath != null) {
                    Log.i(TAG, "Saved session photo via ImageAnalysis fallback pipeline")
                    return fallbackPath
                }
            }
            return null
        } finally {
            latestFrame.set(null)
            imageAnalysis.clearAnalyzer()
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                runCatching { cameraProvider.unbindAll() }
            }
        }
    }

    private suspend fun awaitLifecycleStarted(
        lifecycleOwner: LifecycleOwner,
        timeoutMs: Long,
    ): Boolean {
        val current = withContext(Dispatchers.Main.immediate) {
            lifecycleOwner.lifecycle.currentState
        }
        if (current.isAtLeast(Lifecycle.State.STARTED)) return true
        if (current == Lifecycle.State.DESTROYED) return false

        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { cont ->
                    val lifecycle = lifecycleOwner.lifecycle
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        cont.resume(true)
                        return@suspendCancellableCoroutine
                    }
                    if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
                        cont.resume(false)
                        return@suspendCancellableCoroutine
                    }
                    val observer = object : LifecycleEventObserver {
                        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                            if (event == Lifecycle.Event.ON_START ||
                                event == Lifecycle.Event.ON_RESUME ||
                                source.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                            ) {
                                lifecycle.removeObserver(this)
                                if (cont.isActive) cont.resume(true)
                            } else if (event == Lifecycle.Event.ON_DESTROY) {
                                lifecycle.removeObserver(this)
                                if (cont.isActive) cont.resume(false)
                            }
                        }
                    }
                    lifecycle.addObserver(observer)
                    cont.invokeOnCancellation {
                        lifecycle.removeObserver(observer)
                    }
                }
            }
        } ?: false
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

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var outIdx = 0
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            if (yPixelStride == 1) {
                yBuffer.position(rowStart)
                yBuffer.get(nv21, outIdx, width)
                outIdx += width
            } else {
                for (col in 0 until width) {
                    nv21[outIdx++] = yBuffer.get(rowStart + col * yPixelStride)
                }
            }
        }

        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val chromaHeight = height / 2
        val chromaWidth = width / 2

        for (row in 0 until chromaHeight) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            for (col in 0 until chromaWidth) {
                nv21[outIdx++] = vBuffer.get(vRowStart + col * vPixelStride)
                nv21[outIdx++] = uBuffer.get(uRowStart + col * uPixelStride)
            }
        }
        return nv21
    }

    private fun writeYuvFrameToJpeg(frame: CapturedYuvFrame, outFile: File): String? {
        return runCatching {
            val yuvImage = YuvImage(frame.nv21, ImageFormat.NV21, frame.width, frame.height, null)
            val baos = ByteArrayOutputStream()
            if (!yuvImage.compressToJpeg(Rect(0, 0, frame.width, frame.height), 88, baos)) {
                return@runCatching null
            }
            val rawJpeg = baos.toByteArray()
            if (frame.rotationDegrees != 0) {
                val bitmap = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size) ?: return@runCatching null
                val matrix = Matrix().apply { postRotate(frame.rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                FileOutputStream(outFile).use { fos ->
                    rotated.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                }
                if (rotated !== bitmap) rotated.recycle()
                bitmap.recycle()
            } else {
                FileOutputStream(outFile).use { fos ->
                    fos.write(rawJpeg)
                }
            }
            if (outFile.exists() && outFile.length() > 0L) outFile.absolutePath else null
        }.getOrNull()
    }
}
