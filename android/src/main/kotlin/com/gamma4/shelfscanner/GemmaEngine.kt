package com.gamma4.shelfscanner

import android.content.Context
import android.util.Log
import com.gamma4.shelfscanner.model.ProductInfo
import com.gamma4.shelfscanner.model.PromptTemplates
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device Gemma 4 E4B inference via llama.cpp (GGUF format).
 * Uses native C++ library through JNI — no Kotlin version constraints.
 * Receives accumulated OCR text + barcodes and returns structured ProductInfo.
 */
class GemmaEngine(private val context: Context) {

    companion object {
        private const val TAG = "GemmaEngine"
    }

    private val gson = Gson()

    val isInitialized: Boolean get() = LlamaCppBridge.isLoaded

    /**
     * Initialize llama.cpp with the downloaded GGUF model.
     * Takes ~5-15 seconds depending on device.
     *
     * @return Load time in milliseconds
     */
    suspend fun initialize(modelPath: String, useGpu: Boolean = true): Long =
        withContext(Dispatchers.IO) {
            release()

            val nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            val loadTimeMs = LlamaCppBridge.initialize(
                modelPath = modelPath,
                nThreads = nThreads,
                nCtx = 2048
            )

            Log.i(TAG, "Engine initialized in ${loadTimeMs}ms (threads=$nThreads, backend=cpu)")
            loadTimeMs
        }

    /**
     * Analyze accumulated OCR text + barcodes and return structured product data.
     * Text-only inference.
     *
     * @return Pair of (List<ProductInfo>, inferenceTimeMs)
     */
    suspend fun structureProducts(
        rawTexts: List<String>,
        barcodes: List<String>
    ): Pair<List<ProductInfo>, Long> = withContext(Dispatchers.IO) {
        val prompt = PromptTemplates.shelfScanPrompt(rawTexts, barcodes)
        val startTime = System.currentTimeMillis()

        val responseText = LlamaCppBridge.generate(
            prompt = prompt,
            maxTokens = 512,
            temperature = 0.2f
        )

        val inferenceTimeMs = System.currentTimeMillis() - startTime
        Log.d(TAG, "Inference completed in ${inferenceTimeMs}ms, response: ${responseText.length} chars")

        val products = parseProducts(responseText)
        Pair(products, inferenceTimeMs)
    }

    private fun parseProducts(response: String): List<ProductInfo> {
        return try {
            val jsonStr = extractJsonArray(response)
            val type = object : TypeToken<List<ProductInfo>>() {}.type
            gson.fromJson(jsonStr, type) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse failed, attempting fallback: ${e.message}")
            tryFallbackParse(response)
        }
    }

    private fun extractJsonArray(text: String): String {
        val fencedPattern = Regex("```(?:json)?\\s*\\n?(\\[.*?])\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        fencedPattern.find(text)?.let { return it.groupValues[1] }

        val arrayPattern = Regex("\\[\\s*\\{.*}\\s*]", RegexOption.DOT_MATCHES_ALL)
        arrayPattern.find(text)?.let { return it.value }

        if (text.contains("[]")) return "[]"
        return text.trim()
    }

    private fun tryFallbackParse(response: String): List<ProductInfo> {
        val products = mutableListOf<ProductInfo>()
        val objectPattern = Regex("\\{[^{}]*\"product_name\"[^{}]*}", RegexOption.DOT_MATCHES_ALL)
        objectPattern.findAll(response).forEach { match ->
            try {
                val product = gson.fromJson(match.value, ProductInfo::class.java)
                if (product.productName.isNotBlank()) {
                    products.add(product)
                }
            } catch (_: Exception) { }
        }
        return products
    }

    fun resetConversation() {
        // Each generate() call is independent in llama.cpp
    }

    fun release() {
        LlamaCppBridge.release()
    }
}
