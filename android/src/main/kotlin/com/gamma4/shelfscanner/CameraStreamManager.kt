package com.gamma4.shelfscanner

import android.content.Context
import android.media.Image
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages CameraX preview + ImageAnalysis for continuous frame capture.
 * Frames are delivered to the onFrame callback for ML Kit processing.
 *
 * Uses STRATEGY_KEEP_ONLY_LATEST so frames are dropped when ML Kit is busy,
 * keeping the camera preview smooth regardless of processing speed.
 */
class CameraStreamManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraStreamManager"
        private val TARGET_RESOLUTION = Size(1280, 720)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var isRunning = false

    val isActive: Boolean get() = isRunning

    /**
     * Start the camera with ImageAnalysis.
     *
     * @param lifecycleOwner The lifecycle to bind the camera to
     * @param onFrame Callback for each frame: (Image, rotationDegrees, closeAction).
     *                Caller MUST invoke closeAction() when done processing the frame.
     */
    fun start(
        lifecycleOwner: LifecycleOwner,
        onFrame: (Image, Int, () -> Unit) -> Unit
    ) {
        if (isRunning) {
            Log.w(TAG, "Camera already running")
            return
        }

        analysisExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(TARGET_RESOLUTION)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor!!) { imageProxy: ImageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    onFrame(mediaImage, rotation) {
                        imageProxy.close()
                    }
                } else {
                    imageProxy.close()
                }
            }

            // Use back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalysis
                )
                isRunning = true
                Log.i(TAG, "Camera started with resolution ${TARGET_RESOLUTION}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Start camera with preview surface + analysis.
     *
     * @param lifecycleOwner The lifecycle to bind the camera to
     * @param surfaceProvider Preview surface provider (from PreviewView)
     * @param onFrame Frame callback (same as above)
     */
    fun startWithPreview(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        onFrame: (Image, Int, () -> Unit) -> Unit
    ) {
        if (isRunning) {
            Log.w(TAG, "Camera already running")
            return
        }

        analysisExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder()
                .setTargetResolution(TARGET_RESOLUTION)
                .build()
            preview.setSurfaceProvider(surfaceProvider)

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(TARGET_RESOLUTION)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor!!) { imageProxy: ImageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    onFrame(mediaImage, rotation) {
                        imageProxy.close()
                    }
                } else {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                isRunning = true
                Log.i(TAG, "Camera started with preview + analysis")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraProvider?.unbindAll()
        analysisExecutor?.shutdown()
        analysisExecutor = null
        isRunning = false
        Log.i(TAG, "Camera stopped")
    }
}
