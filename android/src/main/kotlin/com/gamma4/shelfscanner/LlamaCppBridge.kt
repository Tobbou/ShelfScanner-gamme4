package com.gamma4.shelfscanner

import android.util.Log

/**
 * JNI bridge to llama.cpp native library.
 * Loads libllama.so and provides Kotlin-friendly API for text inference.
 */
object LlamaCppBridge {

    private const val TAG = "LlamaCppBridge"

    // Native method declarations
    private external fun nativeInit(modelPath: String, nThreads: Int, nCtx: Int): Long
    private external fun nativeGenerate(contextPtr: Long, prompt: String, maxTokens: Int, temperature: Float): String
    private external fun nativeFree(contextPtr: Long)
    private external fun nativeSystemInfo(): String

    private var contextPtr: Long = 0L
    var isLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("ggml")
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml-cpu")
            System.loadLibrary("llama")
            System.loadLibrary("llama-jni")
            Log.i(TAG, "Native libraries loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native libraries: ${e.message}")
        }
    }

    /**
     * Initialize llama.cpp with a GGUF model file.
     * @param modelPath Absolute path to .gguf file on device
     * @param nThreads Number of CPU threads (default: 4)
     * @param nCtx Context window size (default: 2048, enough for OCR text)
     * @return Load time in milliseconds
     */
    fun initialize(modelPath: String, nThreads: Int = 4, nCtx: Int = 2048): Long {
        val startTime = System.currentTimeMillis()
        contextPtr = nativeInit(modelPath, nThreads, nCtx)
        isLoaded = contextPtr != 0L
        val loadTimeMs = System.currentTimeMillis() - startTime

        if (isLoaded) {
            Log.i(TAG, "Model loaded in ${loadTimeMs}ms (threads=$nThreads, ctx=$nCtx)")
        } else {
            Log.e(TAG, "Failed to load model: $modelPath")
        }
        return loadTimeMs
    }

    /**
     * Generate text completion from a prompt.
     * @param prompt The input prompt
     * @param maxTokens Maximum tokens to generate (default: 512)
     * @param temperature Sampling temperature (default: 0.2 for structured output)
     * @return Generated text
     */
    fun generate(prompt: String, maxTokens: Int = 512, temperature: Float = 0.2f): String {
        if (!isLoaded) throw IllegalStateException("Model not loaded")
        return nativeGenerate(contextPtr, prompt, maxTokens, temperature)
    }

    fun release() {
        if (isLoaded) {
            nativeFree(contextPtr)
            contextPtr = 0L
            isLoaded = false
            Log.i(TAG, "Model released")
        }
    }

    fun getSystemInfo(): String {
        return try {
            nativeSystemInfo()
        } catch (e: Exception) {
            "unavailable"
        }
    }
}
