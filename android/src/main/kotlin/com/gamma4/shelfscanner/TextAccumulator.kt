package com.gamma4.shelfscanner

import android.util.Log
import com.gamma4.shelfscanner.model.AccumulatedData
import com.gamma4.shelfscanner.model.RawDetection
import java.util.concurrent.ConcurrentHashMap

/**
 * Accumulates raw ML Kit detections over a configurable time window,
 * deduplicates text fragments, and triggers Gemma processing when
 * enough data has been collected.
 *
 * This is the bridge between the real-time ML Kit layer (30+ fps)
 * and the smart Gemma layer (~1-3 sec per inference).
 */
class TextAccumulator(
    private val windowDurationMs: Long = 2000L,
    private val minUniqueTexts: Int = 3,
    private val onWindowReady: (AccumulatedData) -> Unit
) {

    companion object {
        private const val TAG = "TextAccumulator"
        private const val SIMILARITY_THRESHOLD = 0.85
    }

    private val uniqueTexts = ConcurrentHashMap<String, Int>()  // text → occurrence count
    private val uniqueBarcodes = ConcurrentHashMap.newKeySet<String>()
    private var windowStartMs: Long = 0L
    private var frameCount = 0
    private var isGemmaBusy = false

    /**
     * Add a raw detection from ML Kit.
     * Called at 30+ fps — must be fast.
     */
    @Synchronized
    fun addDetection(detection: RawDetection) {
        if (windowStartMs == 0L) {
            windowStartMs = detection.timestampMs
        }
        frameCount++

        // Add text blocks (deduplicate similar strings)
        for (block in detection.textBlocks) {
            val text = block.text.trim()
            if (text.length < 2) continue  // skip single characters

            val normalizedText = normalizeForDedup(text)
            if (!isDuplicate(normalizedText)) {
                uniqueTexts[normalizedText] = (uniqueTexts[normalizedText] ?: 0) + 1
            }
        }

        // Add barcodes (exact dedup)
        for (barcode in detection.barcodes) {
            uniqueBarcodes.add(barcode.rawValue)
        }

        // Check if window is ready
        val elapsed = detection.timestampMs - windowStartMs
        val hasEnoughData = uniqueTexts.size >= minUniqueTexts || uniqueBarcodes.isNotEmpty()

        if (elapsed >= windowDurationMs && hasEnoughData && !isGemmaBusy) {
            flush(detection.timestampMs)
        }
    }

    /**
     * Force flush the current window regardless of timing.
     */
    @Synchronized
    fun forceFlush() {
        if (uniqueTexts.isNotEmpty() || uniqueBarcodes.isNotEmpty()) {
            flush(System.currentTimeMillis())
        }
    }

    /**
     * Signal that Gemma has finished processing — ready for next window.
     */
    fun onGemmaComplete() {
        isGemmaBusy = false
    }

    /**
     * Reset all accumulated data.
     */
    @Synchronized
    fun reset() {
        uniqueTexts.clear()
        uniqueBarcodes.clear()
        windowStartMs = 0L
        frameCount = 0
        isGemmaBusy = false
    }

    private fun flush(currentTimestampMs: Long) {
        isGemmaBusy = true

        // Prioritize texts seen in more frames (more reliable OCR)
        val sortedTexts = uniqueTexts.entries
            .sortedByDescending { it.value }
            .map { it.key }

        val data = AccumulatedData(
            rawTextFragments = sortedTexts,
            barcodes = uniqueBarcodes.toList(),
            windowStartMs = windowStartMs,
            windowEndMs = currentTimestampMs,
            frameCount = frameCount
        )

        Log.d(TAG, "Window flush: ${sortedTexts.size} texts, ${uniqueBarcodes.size} barcodes, " +
                "${frameCount} frames over ${currentTimestampMs - windowStartMs}ms")

        // Reset for next window
        uniqueTexts.clear()
        uniqueBarcodes.clear()
        windowStartMs = 0L
        frameCount = 0

        onWindowReady(data)
    }

    /**
     * Normalize text for deduplication (lowercase, collapse whitespace).
     */
    private fun normalizeForDedup(text: String): String {
        return text.lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Check if a normalized text is too similar to an existing entry.
     * Uses simple containment check + Levenshtein for short strings.
     */
    private fun isDuplicate(normalizedText: String): Boolean {
        for (existing in uniqueTexts.keys) {
            // Exact match
            if (existing == normalizedText) return true

            // Containment: one is substring of the other
            if (existing.contains(normalizedText) || normalizedText.contains(existing)) return true

            // Levenshtein similarity for short texts (< 50 chars)
            if (normalizedText.length < 50 && existing.length < 50) {
                val similarity = levenshteinSimilarity(existing, normalizedText)
                if (similarity >= SIMILARITY_THRESHOLD) return true
            }
        }
        return false
    }

    /**
     * Levenshtein similarity ratio (0.0 to 1.0).
     */
    private fun levenshteinSimilarity(a: String, b: String): Double {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - (levenshteinDistance(a, b).toDouble() / maxLen)
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }
}
