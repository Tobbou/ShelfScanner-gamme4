package com.gamma4.shelfscanner.model

import com.google.gson.annotations.SerializedName

/**
 * Raw detection from ML Kit — emitted in real-time at 30+ fps.
 */
data class RawDetection(
    val textBlocks: List<DetectedTextBlock>,
    val barcodes: List<DetectedBarcode>,
    val timestampMs: Long,
    val frameId: Int
)

data class DetectedTextBlock(
    val text: String,
    val boundingBox: BoundingBox?,
    val confidence: Float?
)

data class DetectedBarcode(
    val rawValue: String,
    val format: String,       // "EAN_13", "EAN_8", "QR_CODE", etc.
    val boundingBox: BoundingBox?
)

data class BoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/**
 * Accumulated OCR data ready for Gemma structuring.
 */
data class AccumulatedData(
    val rawTextFragments: List<String>,
    val barcodes: List<String>,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val frameCount: Int
)

/**
 * Structured product info — output from Gemma 4 E4B.
 */
data class ProductInfo(
    @SerializedName("product_name") val productName: String,
    @SerializedName("brand") val brand: String? = null,
    @SerializedName("price") val price: String? = null,
    @SerializedName("ean") val ean: String? = null,
    @SerializedName("unit_price") val unitPrice: String? = null,
    @SerializedName("position") val position: String? = null
)

/**
 * Final scan result emitted to JavaScript.
 */
data class ScanResult(
    val products: List<ProductInfo>,
    val newProducts: List<ProductInfo>,
    val inferenceTimeMs: Long,
    val totalProductCount: Int
)

/**
 * Plugin status.
 */
data class ScannerStatus(
    val modelDownloaded: Boolean,
    val modelLoaded: Boolean,
    val scanning: Boolean,
    val totalProductsFound: Int
)

/**
 * Download progress event.
 */
data class DownloadProgress(
    val percent: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val status: String    // "downloading", "completed", "error", "verifying"
)
