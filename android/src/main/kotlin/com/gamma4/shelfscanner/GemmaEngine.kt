package com.gamma4.shelfscanner

import android.content.Context
import android.util.Log
import com.gamma4.shelfscanner.model.ProductInfo
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stub for Gemma 4 E4B inference via LiteRT-LM.
 *
 * LiteRT-LM requires Kotlin 2.x but MABS 12 uses Kotlin 1.9.
 * This stub parses raw OCR text into ProductInfo using simple heuristics
 * until LiteRT-LM becomes compatible.
 *
 * TODO: Replace with real LiteRT-LM inference when MABS supports Kotlin 2.x
 */
class GemmaEngine(private val context: Context) {

    companion object {
        private const val TAG = "GemmaEngine"
    }

    private val gson = Gson()
    var isInitialized: Boolean = false
        private set

    suspend fun initialize(modelPath: String, useGpu: Boolean = true): Long =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "GemmaEngine stub initialized (LiteRT-LM pending Kotlin 2.x support)")
            isInitialized = true
            100L
        }

    /**
     * Stub: structures OCR text into products using simple text parsing.
     * In production this would use Gemma 4 E4B for intelligent structuring.
     */
    suspend fun structureProducts(
        rawTexts: List<String>,
        barcodes: List<String>
    ): Pair<List<ProductInfo>, Long> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val products = mutableListOf<ProductInfo>()

        for (text in rawTexts) {
            val trimmed = text.trim()
            if (trimmed.length < 3) continue

            val priceRegex = Regex("(\\d+[.,]\\d{2})\\s*(kr\\.?|DKK|EUR|€|\\$)?")
            val priceMatch = priceRegex.find(trimmed)
            val ean = barcodes.firstOrNull()

            if (priceMatch != null) {
                val pricePart = priceMatch.value
                val namePart = trimmed.substring(0, priceMatch.range.first).trim()
                if (namePart.length >= 2) {
                    products.add(ProductInfo(productName = namePart, price = pricePart, ean = ean))
                }
            } else if (trimmed.length >= 5 && !trimmed.all { it.isDigit() }) {
                products.add(ProductInfo(productName = trimmed, ean = ean))
            }
        }

        val inferenceTimeMs = System.currentTimeMillis() - startTime
        Log.d(TAG, "Stub structured ${products.size} products in ${inferenceTimeMs}ms")
        Pair(products, inferenceTimeMs)
    }

    fun resetConversation() {}

    fun release() {
        isInitialized = false
    }
}
