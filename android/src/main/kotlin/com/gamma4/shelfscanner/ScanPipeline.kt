package com.gamma4.shelfscanner

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.gamma4.shelfscanner.model.AccumulatedData
import com.gamma4.shelfscanner.model.ProductInfo
import com.gamma4.shelfscanner.model.RawDetection
import com.gamma4.shelfscanner.model.ScanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates the full hybrid scanning pipeline:
 *
 *   CameraX (30fps) → ML Kit (real-time) → TextAccumulator (2s windows) → Gemma (structured)
 *
 * Emits two types of events:
 * - onRawDetection: real-time ML Kit results for instant UI feedback
 * - onProductsDetected: structured product data from Gemma
 */
class ScanPipeline(
    private val context: Context,
    private val gemmaEngine: GemmaEngine
) {

    companion object {
        private const val TAG = "ScanPipeline"
        private const val PRODUCT_SIMILARITY_THRESHOLD = 0.8
    }

    private val cameraManager = CameraStreamManager(context)
    private val mlKitProcessor = MLKitProcessor()
    private var accumulator: TextAccumulator? = null
    private var pipelineScope: CoroutineScope? = null

    // Accumulated products across all Gemma inference windows
    private val allProducts = ConcurrentHashMap<String, ProductInfo>()

    var isScanning: Boolean = false
        private set

    /**
     * Start the full scanning pipeline.
     *
     * @param lifecycleOwner Lifecycle to bind camera to
     * @param accumulationWindowMs How long to accumulate OCR before sending to Gemma (default 2s)
     * @param onRawDetection Callback for real-time ML Kit results (30+ fps)
     * @param onProductsDetected Callback for structured products from Gemma (~every 2-5s)
     * @param onError Error callback
     */
    fun start(
        lifecycleOwner: LifecycleOwner,
        accumulationWindowMs: Long = 2000L,
        onRawDetection: (RawDetection) -> Unit,
        onProductsDetected: (ScanResult) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (isScanning) {
            Log.w(TAG, "Pipeline already running")
            return
        }

        if (!gemmaEngine.isInitialized) {
            onError(IllegalStateException("Gemma engine not initialized. Call initializeEngine() first."))
            return
        }

        pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        allProducts.clear()

        // Set up accumulator → Gemma pipeline
        accumulator = TextAccumulator(
            windowDurationMs = accumulationWindowMs,
            onWindowReady = { data ->
                processWithGemma(data, onProductsDetected, onError)
            }
        )

        // Start camera → ML Kit → accumulator pipeline
        cameraManager.start(lifecycleOwner) { image, rotation, close ->
            pipelineScope?.launch {
                try {
                    val detection = mlKitProcessor.processFrame(image, rotation)

                    // Emit raw detection for instant UI feedback
                    if (detection.textBlocks.isNotEmpty() || detection.barcodes.isNotEmpty()) {
                        onRawDetection(detection)
                    }

                    // Feed into accumulator for Gemma processing
                    accumulator?.addDetection(detection)
                } catch (e: Exception) {
                    Log.e(TAG, "Frame processing error: ${e.message}")
                } finally {
                    close() // Release the ImageProxy back to CameraX
                }
            }
        }

        isScanning = true
        Log.i(TAG, "Scan pipeline started (window=${accumulationWindowMs}ms)")
    }

    /**
     * Stop the pipeline and clean up.
     *
     * @return Total number of unique products found
     */
    fun stop(): Int {
        // Flush any remaining accumulated data
        accumulator?.forceFlush()

        cameraManager.stop()
        mlKitProcessor.close()
        pipelineScope?.cancel()
        pipelineScope = null
        accumulator?.reset()
        accumulator = null
        isScanning = false

        val total = allProducts.size
        Log.i(TAG, "Scan pipeline stopped. Total products: $total")
        return total
    }

    /**
     * Get all products found so far.
     */
    fun getAllProducts(): List<ProductInfo> = allProducts.values.toList()

    /**
     * Reset accumulated products (e.g. when starting a new shelf).
     */
    fun resetProducts() {
        allProducts.clear()
    }

    // --- Private ---

    private fun processWithGemma(
        data: AccumulatedData,
        onProductsDetected: (ScanResult) -> Unit,
        onError: (Exception) -> Unit
    ) {
        pipelineScope?.launch {
            try {
                val (products, inferenceTimeMs) = gemmaEngine.structureProducts(
                    rawTexts = data.rawTextFragments,
                    barcodes = data.barcodes
                )

                // Merge with existing products (dedup across windows)
                val newProducts = mutableListOf<ProductInfo>()
                for (product in products) {
                    val key = deduplicationKey(product)
                    if (!allProducts.containsKey(key)) {
                        allProducts[key] = product
                        newProducts.add(product)
                    } else {
                        // Update with richer data if available
                        val existing = allProducts[key]!!
                        val merged = mergeProducts(existing, product)
                        allProducts[key] = merged
                    }
                }

                val result = ScanResult(
                    products = allProducts.values.toList(),
                    newProducts = newProducts,
                    inferenceTimeMs = inferenceTimeMs,
                    totalProductCount = allProducts.size
                )

                Log.d(TAG, "Gemma result: ${products.size} products (${newProducts.size} new), " +
                        "${inferenceTimeMs}ms inference, ${allProducts.size} total")

                onProductsDetected(result)
                accumulator?.onGemmaComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Gemma processing error: ${e.message}")
                onError(e)
                accumulator?.onGemmaComplete()
            }
        }
    }

    /**
     * Generate a deduplication key for a product.
     * Normalizes the product name for fuzzy matching.
     */
    private fun deduplicationKey(product: ProductInfo): String {
        // If EAN is available, use it as the primary key (most reliable)
        product.ean?.let { ean ->
            if (ean.length >= 8) return "ean:$ean"
        }

        // Fall back to normalized product name
        return "name:" + product.productName.lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Merge two product records, preferring non-null values from either source.
     */
    private fun mergeProducts(existing: ProductInfo, new: ProductInfo): ProductInfo {
        return existing.copy(
            brand = existing.brand ?: new.brand,
            price = existing.price ?: new.price,
            ean = existing.ean ?: new.ean,
            unitPrice = existing.unitPrice ?: new.unitPrice,
            position = new.position ?: existing.position  // prefer latest position
        )
    }
}
