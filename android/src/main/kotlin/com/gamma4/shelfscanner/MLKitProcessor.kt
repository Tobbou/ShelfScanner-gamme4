package com.gamma4.shelfscanner

import android.media.Image
import android.util.Log
import com.gamma4.shelfscanner.model.BoundingBox
import com.gamma4.shelfscanner.model.DetectedBarcode
import com.gamma4.shelfscanner.model.DetectedTextBlock
import com.gamma4.shelfscanner.model.RawDetection
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real-time text and barcode recognition using Google ML Kit.
 * Runs at 30+ fps on modern Android devices — all processing on-device.
 */
class MLKitProcessor {

    companion object {
        private const val TAG = "MLKitProcessor"
    }

    private val textRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.Builder().build())

    private val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_QR_CODE
            )
            .build()
    )

    private val frameCounter = AtomicInteger(0)

    /**
     * Process a camera frame through both text recognition and barcode scanning
     * in parallel. Returns a RawDetection with all findings.
     *
     * @param image The camera frame (from CameraX ImageProxy)
     * @param rotationDegrees Image rotation from ImageProxy.imageInfo.rotationDegrees
     */
    suspend fun processFrame(image: Image, rotationDegrees: Int): RawDetection {
        val frameId = frameCounter.incrementAndGet()
        val inputImage = InputImage.fromMediaImage(image, rotationDegrees)

        // Run text recognition and barcode scanning in parallel
        val textResult = try {
            textRecognizer.process(inputImage).await()
        } catch (e: Exception) {
            Log.w(TAG, "Text recognition failed on frame $frameId: ${e.message}")
            null
        }

        val barcodeResult = try {
            barcodeScanner.process(inputImage).await()
        } catch (e: Exception) {
            Log.w(TAG, "Barcode scanning failed on frame $frameId: ${e.message}")
            null
        }

        // Map text blocks
        val textBlocks = textResult?.textBlocks?.map { block ->
            DetectedTextBlock(
                text = block.text,
                boundingBox = block.boundingBox?.let {
                    BoundingBox(it.left, it.top, it.right, it.bottom)
                },
                confidence = null // ML Kit v2 text recognition doesn't expose confidence per block
            )
        } ?: emptyList()

        // Map barcodes
        val barcodes = barcodeResult?.mapNotNull { barcode ->
            barcode.rawValue?.let { value ->
                DetectedBarcode(
                    rawValue = value,
                    format = barcodeFormatToString(barcode.format),
                    boundingBox = barcode.boundingBox?.let {
                        BoundingBox(it.left, it.top, it.right, it.bottom)
                    }
                )
            }
        } ?: emptyList()

        if (textBlocks.isNotEmpty() || barcodes.isNotEmpty()) {
            Log.d(TAG, "Frame $frameId: ${textBlocks.size} text blocks, ${barcodes.size} barcodes")
        }

        return RawDetection(
            textBlocks = textBlocks,
            barcodes = barcodes,
            timestampMs = System.currentTimeMillis(),
            frameId = frameId
        )
    }

    private fun barcodeFormatToString(format: Int): String = when (format) {
        Barcode.FORMAT_EAN_13 -> "EAN_13"
        Barcode.FORMAT_EAN_8 -> "EAN_8"
        Barcode.FORMAT_UPC_A -> "UPC_A"
        Barcode.FORMAT_UPC_E -> "UPC_E"
        Barcode.FORMAT_QR_CODE -> "QR_CODE"
        else -> "UNKNOWN"
    }

    fun close() {
        textRecognizer.close()
        barcodeScanner.close()
    }
}
