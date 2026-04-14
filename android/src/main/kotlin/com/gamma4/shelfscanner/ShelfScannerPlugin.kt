package com.gamma4.shelfscanner

import android.Manifest
import android.util.Log
import com.gamma4.shelfscanner.model.RawDetection
import com.gamma4.shelfscanner.model.ScanResult
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@CapacitorPlugin(
    name = "ShelfScanner",
    permissions = [
        Permission(
            alias = "camera",
            strings = [Manifest.permission.CAMERA]
        )
    ]
)
class ShelfScannerPlugin : Plugin() {

    companion object {
        private const val TAG = "ShelfScannerPlugin"

        // Event names emitted to JavaScript
        const val EVENT_RAW_DETECTION = "onRawDetection"
        const val EVENT_PRODUCTS_DETECTED = "onProductsDetected"
        const val EVENT_DOWNLOAD_PROGRESS = "onDownloadProgress"
        const val EVENT_ERROR = "onError"
    }

    private lateinit var modelDownloader: ModelDownloader
    private lateinit var gemmaEngine: GemmaEngine
    private var scanPipeline: ScanPipeline? = null
    private val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun load() {
        modelDownloader = ModelDownloader(context)
        gemmaEngine = GemmaEngine(context)
    }

    // -----------------------------------------------------------------------
    // downloadModel — Download Gemma 4 E4B from HuggingFace (~3.65 GB)
    // -----------------------------------------------------------------------

    @PluginMethod
    fun downloadModel(call: PluginCall) {
        val forceRedownload = call.getBoolean("forceRedownload", false) ?: false

        pluginScope.launch {
            try {
                val path = modelDownloader.downloadModel(
                    forceRedownload = forceRedownload,
                    onProgress = { progress ->
                        val event = JSObject().apply {
                            put("percent", progress.percent)
                            put("downloadedBytes", progress.downloadedBytes)
                            put("totalBytes", progress.totalBytes)
                            put("status", progress.status)
                        }
                        notifyListeners(EVENT_DOWNLOAD_PROGRESS, event)
                    }
                )

                call.resolve(JSObject().apply {
                    put("success", true)
                    put("modelPath", path)
                })
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}")
                call.reject("Download failed: ${e.message}", e)
            }
        }
    }

    // -----------------------------------------------------------------------
    // initializeEngine — Load Gemma model into memory (~5-10 seconds)
    // -----------------------------------------------------------------------

    @PluginMethod
    fun initializeEngine(call: PluginCall) {
        val backend = call.getString("backend", "gpu") ?: "gpu"
        val useGpu = backend.lowercase() == "gpu"

        val modelPath = modelDownloader.getModelPath()
        if (modelPath == null) {
            call.reject("Model not downloaded. Call downloadModel() first.")
            return
        }

        pluginScope.launch {
            try {
                Log.i(TAG, "Initializing engine with model: $modelPath (exists: ${java.io.File(modelPath).exists()}, size: ${java.io.File(modelPath).length()} bytes)")
                val loadTimeMs = gemmaEngine.initialize(modelPath, useGpu)

                if (gemmaEngine.isInitialized) {
                    call.resolve(JSObject().apply {
                        put("success", true)
                        put("loadTimeMs", loadTimeMs)
                        put("backend", "cpu")
                        put("modelPath", modelPath)
                    })
                } else {
                    call.reject("Engine init returned but model not loaded. Path: $modelPath, File exists: ${java.io.File(modelPath).exists()}, Size: ${java.io.File(modelPath).length()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Engine init failed: ${e.message}")
                call.reject("Engine init failed: ${e.message}. Path: $modelPath", e)
            }
        }
    }

    // -----------------------------------------------------------------------
    // startScanning — Start camera + ML Kit + Gemma pipeline
    // -----------------------------------------------------------------------

    @PluginMethod
    fun startScanning(call: PluginCall) {
        val accumulationWindowMs: Long = call.getLong("accumulationWindowMs", 2000L) ?: 2000L

        if (!gemmaEngine.isInitialized) {
            call.reject("Engine not initialized. Call initializeEngine() first.")
            return
        }

        if (scanPipeline?.isScanning == true) {
            call.reject("Already scanning. Call stopScanning() first.")
            return
        }

        val activity = activity
        if (activity == null) {
            call.reject("No activity available")
            return
        }

        scanPipeline = ScanPipeline(context, gemmaEngine)
        scanPipeline!!.start(
            lifecycleOwner = activity,
            accumulationWindowMs = accumulationWindowMs,
            onRawDetection = { detection ->
                notifyListeners(EVENT_RAW_DETECTION, rawDetectionToJS(detection))
            },
            onProductsDetected = { result ->
                notifyListeners(EVENT_PRODUCTS_DETECTED, scanResultToJS(result))
            },
            onError = { error ->
                notifyListeners(EVENT_ERROR, JSObject().apply {
                    put("message", error.message ?: "Unknown error")
                })
            }
        )

        call.resolve(JSObject().apply {
            put("success", true)
            put("message", "Scanning started")
        })
    }

    // -----------------------------------------------------------------------
    // stopScanning — Stop camera + pipeline
    // -----------------------------------------------------------------------

    @PluginMethod
    fun stopScanning(call: PluginCall) {
        val totalProducts = scanPipeline?.stop() ?: 0
        val products = scanPipeline?.getAllProducts() ?: emptyList()
        scanPipeline = null

        val productsArray = JSArray()
        products.forEach { product ->
            productsArray.put(productInfoToJS(product))
        }

        call.resolve(JSObject().apply {
            put("success", true)
            put("totalProducts", totalProducts)
            put("products", productsArray)
        })
    }

    // -----------------------------------------------------------------------
    // getStatus — Check plugin state
    // -----------------------------------------------------------------------

    @PluginMethod
    fun getStatus(call: PluginCall) {
        call.resolve(JSObject().apply {
            put("modelDownloaded", modelDownloader.isModelDownloaded())
            put("modelLoaded", gemmaEngine.isInitialized)
            put("scanning", scanPipeline?.isScanning ?: false)
            put("totalProductsFound", scanPipeline?.getAllProducts()?.size ?: 0)
        })
    }

    // -----------------------------------------------------------------------
    // releaseEngine — Free model from memory
    // -----------------------------------------------------------------------

    @PluginMethod
    fun releaseEngine(call: PluginCall) {
        scanPipeline?.stop()
        scanPipeline = null
        gemmaEngine.release()

        call.resolve(JSObject().apply {
            put("success", true)
        })
    }

    // -----------------------------------------------------------------------
    // Serialization helpers: Kotlin data classes → Capacitor JSObject
    // -----------------------------------------------------------------------

    private fun rawDetectionToJS(detection: RawDetection): JSObject {
        val textBlocks = JSArray()
        detection.textBlocks.forEach { block ->
            textBlocks.put(JSObject().apply {
                put("text", block.text)
                block.boundingBox?.let { box ->
                    put("boundingBox", JSObject().apply {
                        put("left", box.left)
                        put("top", box.top)
                        put("right", box.right)
                        put("bottom", box.bottom)
                    })
                }
            })
        }

        val barcodes = JSArray()
        detection.barcodes.forEach { barcode ->
            barcodes.put(JSObject().apply {
                put("rawValue", barcode.rawValue)
                put("format", barcode.format)
                barcode.boundingBox?.let { box ->
                    put("boundingBox", JSObject().apply {
                        put("left", box.left)
                        put("top", box.top)
                        put("right", box.right)
                        put("bottom", box.bottom)
                    })
                }
            })
        }

        return JSObject().apply {
            put("textBlocks", textBlocks)
            put("barcodes", barcodes)
            put("timestampMs", detection.timestampMs)
            put("frameId", detection.frameId)
        }
    }

    private fun scanResultToJS(result: ScanResult): JSObject {
        val productsArray = JSArray()
        result.products.forEach { productsArray.put(productInfoToJS(it)) }

        val newProductsArray = JSArray()
        result.newProducts.forEach { newProductsArray.put(productInfoToJS(it)) }

        return JSObject().apply {
            put("products", productsArray)
            put("newProducts", newProductsArray)
            put("inferenceTimeMs", result.inferenceTimeMs)
            put("totalProductCount", result.totalProductCount)
        }
    }

    private fun productInfoToJS(product: com.gamma4.shelfscanner.model.ProductInfo): JSObject {
        return JSObject().apply {
            put("productName", product.productName)
            put("brand", product.brand)
            put("price", product.price)
            put("ean", product.ean)
            put("unitPrice", product.unitPrice)
            put("position", product.position)
        }
    }

    override fun handleOnDestroy() {
        scanPipeline?.stop()
        gemmaEngine.release()
        super.handleOnDestroy()
    }
}
