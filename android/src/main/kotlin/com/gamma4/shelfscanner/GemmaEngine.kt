package com.gamma4.shelfscanner

import android.content.Context
import android.util.Log
import com.gamma4.shelfscanner.model.ProductInfo
import com.gamma4.shelfscanner.model.PromptTemplates
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Wraps LiteRT-LM for text-only inference with Gemma 4 E4B.
 * Receives accumulated OCR text + barcodes and returns structured ProductInfo.
 *
 * Text-only inference is 5-10x faster than vision inference (~1-3 sec vs ~5-15 sec).
 */
class GemmaEngine(private val context: Context) {

    companion object {
        private const val TAG = "GemmaEngine"
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val gson = Gson()

    val isInitialized: Boolean get() = engine != null

    /**
     * Initialize the LiteRT-LM engine with the downloaded model.
     * This blocks for ~5-10 seconds — must be called from a coroutine / background thread.
     *
     * @return Load time in milliseconds
     */
    suspend fun initialize(modelPath: String, useGpu: Boolean = true): Long =
        withContext(Dispatchers.IO) {
            release() // clean up any previous instance

            val startTime = System.currentTimeMillis()

            val backend = if (useGpu) Backend.GPU() else Backend.CPU()

            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                cacheDir = context.cacheDir
            )

            engine = Engine(config).also { it.initialize() }
            conversation = engine!!.createConversation()

            val loadTimeMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "Engine initialized in ${loadTimeMs}ms (backend=${if (useGpu) "GPU" else "CPU"})")
            loadTimeMs
        }

    /**
     * Analyze accumulated OCR text + barcodes and return structured product data.
     * This is a text-only inference call — no images involved.
     *
     * @return Pair of (List<ProductInfo>, inferenceTimeMs)
     */
    suspend fun structureProducts(
        rawTexts: List<String>,
        barcodes: List<String>
    ): Pair<List<ProductInfo>, Long> = withContext(Dispatchers.IO) {
        val conv = conversation ?: throw IllegalStateException("Engine not initialized")

        val prompt = PromptTemplates.shelfScanPrompt(rawTexts, barcodes)
        val startTime = System.currentTimeMillis()

        // Collect the full response from streaming
        val responseBuilder = StringBuilder()
        conv.sendMessageAsync(Contents.of(Content.Text(prompt))).collect { message ->
            responseBuilder.append(message)
        }

        val inferenceTimeMs = System.currentTimeMillis() - startTime
        val responseText = responseBuilder.toString()

        Log.d(TAG, "Inference completed in ${inferenceTimeMs}ms, response length: ${responseText.length}")

        val products = parseProducts(responseText)
        Pair(products, inferenceTimeMs)
    }

    /**
     * Stream the inference response token by token for real-time UI updates.
     */
    fun structureProductsStreaming(
        rawTexts: List<String>,
        barcodes: List<String>
    ): Flow<String> = flow {
        val conv = conversation ?: throw IllegalStateException("Engine not initialized")
        val prompt = PromptTemplates.shelfScanPrompt(rawTexts, barcodes)

        conv.sendMessageAsync(Contents.of(Content.Text(prompt))).collect { message ->
            emit(message.toString())
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Parse JSON product array from Gemma's response.
     * Handles common LLM output quirks (markdown fences, trailing text, etc.)
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

    /**
     * Extract JSON array from response that may contain markdown fences or extra text.
     */
    private fun extractJsonArray(text: String): String {
        // Try to find JSON array between ```json ... ``` fences
        val fencedPattern = Regex("```(?:json)?\\s*\\n?(\\[.*?])\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        fencedPattern.find(text)?.let { return it.groupValues[1] }

        // Try to find bare JSON array
        val arrayPattern = Regex("\\[\\s*\\{.*}\\s*]", RegexOption.DOT_MATCHES_ALL)
        arrayPattern.find(text)?.let { return it.value }

        // Try empty array
        if (text.contains("[]")) return "[]"

        return text.trim()
    }

    /**
     * Fallback: attempt to extract product info even from malformed JSON.
     */
    private fun tryFallbackParse(response: String): List<ProductInfo> {
        val products = mutableListOf<ProductInfo>()

        // Try parsing individual JSON objects
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

    /**
     * Reset the conversation context (for starting a fresh scan session).
     */
    fun resetConversation() {
        conversation = engine?.createConversation()
    }

    /**
     * Release all resources.
     */
    fun release() {
        try {
            conversation = null
            engine?.close()
            engine = null
            Log.i(TAG, "Engine released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing engine: ${e.message}")
        }
    }
}
