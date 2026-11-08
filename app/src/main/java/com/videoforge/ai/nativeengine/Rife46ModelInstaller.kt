package com.videoforge.ai.nativeengine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the upstream RIFE v4.6 NCNN model on demand.
 * Files are pinned to the same upstream commit used by native CMake and verified against
 * their official Git blob SHA-1 before being atomically installed.
 */
object Rife46ModelInstaller {
    private const val UPSTREAM_COMMIT = "a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7"
    private const val MODEL_BASE_URL =
        "https://raw.githubusercontent.com/nihui/rife-ncnn-vulkan/$UPSTREAM_COMMIT/models/rife-v4.6/"

    private data class ModelArtifact(
        val fileName: String,
        val expectedSize: Long,
        val gitBlobSha1: String
    )

    private val artifacts = listOf(
        ModelArtifact(
            fileName = "flownet.param",
            expectedSize = 16_532L,
            gitBlobSha1 = "3cb69d4ae09b085a2b5b51eb3c2e93745f805644"
        ),
        ModelArtifact(
            fileName = "flownet.bin",
            expectedSize = 10_614_320L,
            gitBlobSha1 = "70de38ecc5b6f1b63afa56108bf76911f04ce630"
        )
    )

    fun isInstalled(context: Context): Boolean {
        val directory = File(context.applicationContext.filesDir, "models/rife-v4.6")
        return artifacts.all { artifact -> isValid(File(directory, artifact.fileName), artifact) }
    }

    suspend fun ensureInstalled(
        context: Context,
        onProgress: (fileName: String, downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val modelDirectory = File(context.applicationContext.filesDir, "models/rife-v4.6")
        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            throw IOException("Unable to create RIFE model directory: $modelDirectory")
        }

        artifacts.forEach { artifact ->
            val target = File(modelDirectory, artifact.fileName)
            if (isValid(target, artifact)) return@forEach

            target.delete()
            downloadAndVerify(target, artifact, onProgress)
        }

        modelDirectory
    }

    private suspend fun downloadAndVerify(
        target: File,
        artifact: ModelArtifact,
        onProgress: (String, Long, Long) -> Unit
    ) {
        val temporary = File(target.parentFile, target.name + ".part")
        temporary.delete()

        val connection = (URL(MODEL_BASE_URL + artifact.fileName).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "VideoForge-AI-Android")
        }

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("Model download failed: HTTP ${connection.responseCode}")
            }

            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(temporary.outputStream()).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(artifact.fileName, downloaded, artifact.expectedSize)
                        if (downloaded > artifact.expectedSize) {
                            throw IOException("Downloaded model file is larger than expected.")
                        }
                    }
                }
            }

            if (!isValid(temporary, artifact)) {
                throw IOException("RIFE model checksum/size verification failed for ${artifact.fileName}.")
            }

            if (target.exists() && !target.delete()) {
                throw IOException("Unable to replace old model file: $target")
            }
            if (!temporary.renameTo(target)) {
                throw IOException("Unable to install verified model file: $target")
            }
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun isValid(file: File, artifact: ModelArtifact): Boolean {
        if (!file.isFile || file.length() != artifact.expectedSize) return false
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${file.length()}\u0000".toByteArray(Charsets.US_ASCII))
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return actual.equals(artifact.gitBlobSha1, ignoreCase = true)
    }
}
