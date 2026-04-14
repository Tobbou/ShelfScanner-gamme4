package com.gamma4.shelfscanner

import android.content.Context
import com.gamma4.shelfscanner.model.DownloadProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class ModelDownloader(private val context: Context) {

    companion object {
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-litert-lm.litertlm"
        private const val MODEL_FILENAME = "gemma-4-E4B-it-litert-lm.litertlm"
        private const val MODELS_DIR = "models"
        private const val BUFFER_SIZE = 8 * 1024 // 8 KB chunks
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val modelsDir: File
        get() = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }

    private val modelFile: File
        get() = File(modelsDir, MODEL_FILENAME)

    private val partialFile: File
        get() = File(modelsDir, "$MODEL_FILENAME.part")

    fun isModelDownloaded(): Boolean = modelFile.exists() && modelFile.length() > 0

    fun getModelPath(): String? = if (isModelDownloaded()) modelFile.absolutePath else null

    fun deleteModel() {
        modelFile.delete()
        partialFile.delete()
    }

    /**
     * Download model from HuggingFace with resume support and progress reporting.
     * Returns the local file path on success.
     */
    suspend fun downloadModel(
        forceRedownload: Boolean = false,
        onProgress: (DownloadProgress) -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (isModelDownloaded() && !forceRedownload) {
            onProgress(DownloadProgress(100, modelFile.length(), modelFile.length(), "completed"))
            return@withContext modelFile.absolutePath
        }

        if (forceRedownload) {
            deleteModel()
        }

        // Check for partial download to resume
        val existingBytes = if (partialFile.exists()) partialFile.length() else 0L

        val requestBuilder = Request.Builder().url(MODEL_URL)
        if (existingBytes > 0) {
            requestBuilder.addHeader("Range", "bytes=$existingBytes-")
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (!response.isSuccessful && response.code != 206) {
            throw IOException("Download failed: HTTP ${response.code}")
        }

        val body = response.body ?: throw IOException("Empty response body")
        val isResume = response.code == 206
        val contentLength = body.contentLength()
        val totalBytes = if (isResume) existingBytes + contentLength else contentLength

        onProgress(DownloadProgress(
            percent = if (totalBytes > 0) ((existingBytes * 100) / totalBytes).toInt() else 0,
            downloadedBytes = existingBytes,
            totalBytes = totalBytes,
            status = if (isResume) "resuming" else "downloading"
        ))

        val outputStream = FileOutputStream(partialFile, isResume)
        val inputStream = body.byteStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var downloadedBytes = existingBytes
        var lastProgressPercent = -1

        try {
            while (true) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break

                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                if (percent != lastProgressPercent) {
                    lastProgressPercent = percent
                    onProgress(DownloadProgress(
                        percent = percent,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        status = "downloading"
                    ))
                }
            }
        } finally {
            outputStream.flush()
            outputStream.close()
            inputStream.close()
        }

        // Rename partial to final
        partialFile.renameTo(modelFile)

        onProgress(DownloadProgress(100, downloadedBytes, totalBytes, "completed"))
        modelFile.absolutePath
    }
}
