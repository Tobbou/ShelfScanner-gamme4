package com.gamma4.shelfscanner

import android.media.Image
import android.util.Log
import com.gamma4.shelfscanner.model.BoundingBox
import com.gamma4.shelfscanner.model.DetectedBarcode
import com.gamma4.shelfscanner.model.DetectedTextBlock
import com.gamma4.shelfscanner.model.RawDetection
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

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

    suspend fun processFrame(image: Image, rotationDegrees: Int): RawDetection =
        withContext(Dispatchers.IO) {
            val frameId = frameCounter.incrementAndGet()
            val inputImage = InputImage.fromMediaImage(image, rotationDegrees)

            // Use Tasks.await() (from play-services-tasks, already included by ML Kit)
            val textResult: Text? = try {
                Tasks.await(textRecognizer.process(inputImage))
            } catch (e: Exception) {
                Log.w(TAG, "Text recognition failed on frame $frameId: ${e.message}")
                null
            }

            val barcodeResult: List<Barcode>? = try {
                Tasks.await(barcodeScanner.process(inputImage))
            } catch (e: Exception) {
                Log.w(TAG, "Barcode scanning failed on frame $frameId: ${e.message}")
                null
            }

            val textBlocks: List<DetectedTextBlock> = textResult?.textBlocks?.map { block ->
                DetectedTextBlock(
                    text = block.text,
                    boundingBox = block.boundingBox?.let { rect ->
                        BoundingBox(rect.left, rect.top, rect.right, rect.bottom)
                    },
                    confidence = null
                )
            } ?: emptyList()

            val barcodes: List<DetectedBarcode> = barcodeResult?.mapNotNull { barcode ->
                val value = barcode.rawValue
                if (value != null) {
                    DetectedBarcode(
                        rawValue = value,
                        format = barcodeFormatToString(barcode.format),
                        boundingBox = barcode.boundingBox?.let { rect ->
                            BoundingBox(rect.left, rect.top, rect.right, rect.bottom)
                        }
                    )
                } else null
            } ?: emptyList()

            if (textBlocks.isNotEmpty() || barcodes.isNotEmpty()) {
                Log.d(TAG, "Frame $frameId: ${textBlocks.size} text blocks, ${barcodes.size} barcodes")
            }

            RawDetection(
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
