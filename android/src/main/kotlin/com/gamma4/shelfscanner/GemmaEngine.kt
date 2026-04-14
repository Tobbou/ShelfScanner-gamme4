package com.gamma4.shelfscanner

import android.content.Context
import android.util.Log
import com.gamma4.shelfscanner.model.ProductInfo
import com.gamma4.shelfscanner.model.PromptTemplates
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device Gemma 4 E4B inference via MediaPipe LLM Inference API.
 * Receives accumulated OCR text + barcodes and returns structured ProductInfo.
 * Text-only inference (~1-3 sec per call).
 */
class GemmaEngine(private val context: Context) {

    companion object {
        private const val TAG = "GemmaEngine"
    }

    private var llmInference: LlmInference? = null
    private val gson = Gson()

    var isInitialized: Boolean = false
        private set

    /**
     * Initialize MediaPipe LLM Inference with the downloaded .task model.
     * This takes ~5-10 seconds — must be called from background thread.
     *
     * @return Load time in milliseconds
     */
    suspend fun initialize(modelPath: String, useGpu: Boolean = true): Long =
        withContext(Dispatchers.IO) {
            release()

            val startTime = System.currentTimeMillis()

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true

            val loadTimeMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "Gemma engine initialized in ${loadTimeMs}ms")
            loadTimeMs
        }

    /**
     * Analyze accumulated OCR text + barcodes and return structured product data.
     * Text-only inference — no images involved.
     *
     * @return Pair of (List<ProductInfo>, inferenceTimeMs)
     */
    suspend fun structureProducts(
        rawTexts: List<String>,
        barcodes: List<String>
    ): Pair<List<ProductInfo>, Long> = withContext(Dispatchers.IO) {
        val inference = llmInference
            ?: throw IllegalStateException("Engine not initialized")

        val prompt = PromptTemplates.shelfScanPrompt(rawTexts, barcodes)
        val startTime = System.currentTimeMillis()

        val responseText = inference.generateResponse(prompt)

        val inferenceTimeMs = System.currentTimeMillis() - startTime
        Log.d(TAG, "Inference completed in ${inferenceTimeMs}ms, response: ${responseText.length} chars")

        val products = parseProducts(responseText)
        Pair(products, inferenceTimeMs)
    }

    /**
     * Parse JSON product array from Gemma's response.
     */
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
        // MediaPipe LlmInference doesn't have explicit conversation reset
        // Each generateResponse call is independent
    }

    fun release() {
        try {
            llmInference?.close()
            llmInference = null
            isInitialized = false
            Log.i(TAG, "Engine released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing engine: ${e.message}")
        }
    }
}
