package com.videoforge.ai.nativeengine

import java.io.Closeable
import java.io.File

enum class RifeComputeBackend {
    VULKAN,
    CPU
}

/**
 * JNI bridge to the RIFE 4.6 ncnn implementation.
 *
 * Input/output frame format: tightly packed RGB24 (3 bytes per pixel).
 * The caller remains responsible for video decoding, timestamp scheduling and encoding.
 */
object Rife46Engine {
    @Volatile
    private var libraryLoaded = false
    private val loadLock = Any()

    private external fun nativeGetGpuCount(): Int
    private external fun nativeCreateModel(
        modelDirectory: String,
        preferVulkan: Boolean,
        allowCpuFallback: Boolean,
        numThreads: Int
    ): Long
    private external fun nativeSessionUsesVulkan(handle: Long): Boolean
    private external fun nativeInterpolate(
        handle: Long,
        frame0: ByteArray,
        frame1: ByteArray,
        width: Int,
        height: Int,
        timestep: Float
    ): ByteArray?
    private external fun nativeReleaseModel(handle: Long)

    private fun ensureLibraryLoaded() {
        if (libraryLoaded) return
        synchronized(loadLock) {
            if (!libraryLoaded) {
                System.loadLibrary("videoforge_rife_jni")
                libraryLoaded = true
            }
        }
    }

    /** Returns the number of Vulkan devices visible to ncnn on this device. */
    fun getVulkanDeviceCount(): Int {
        ensureLibraryLoaded()
        return nativeGetGpuCount()
    }

    /**
     * Creates a RIFE v4.6 session from a directory containing flownet.param and flownet.bin.
     * CPU fallback is enabled by default so devices without a usable Vulkan driver can still run.
     */
    fun createSession(
        modelDirectory: File,
        preferVulkan: Boolean = true,
        allowCpuFallback: Boolean = true,
        numThreads: Int = 2
    ): Rife46Session {
        ensureLibraryLoaded()
        require(modelDirectory.isDirectory) { "RIFE model directory does not exist: $modelDirectory" }

        val handle = nativeCreateModel(
            modelDirectory.canonicalPath,
            preferVulkan,
            allowCpuFallback,
            numThreads.coerceIn(1, 4)
        )
        check(handle != 0L) { "Native RIFE engine returned an invalid session handle." }

        val backend = if (nativeSessionUsesVulkan(handle)) {
            RifeComputeBackend.VULKAN
        } else {
            RifeComputeBackend.CPU
        }
        return Rife46Session(handle, backend)
    }

    internal fun interpolate(
        handle: Long,
        frame0Rgb24: ByteArray,
        frame1Rgb24: ByteArray,
        width: Int,
        height: Int,
        timestep: Float
    ): ByteArray {
        require(width > 0 && height > 0)
        require(timestep in 0f..1f)
        val expectedBytes = width.toLong() * height.toLong() * 3L
        require(expectedBytes <= Int.MAX_VALUE) { "Frame dimensions are too large." }
        require(frame0Rgb24.size.toLong() == expectedBytes) {
            "frame0 must contain exactly width × height × 3 RGB bytes."
        }
        require(frame1Rgb24.size.toLong() == expectedBytes) {
            "frame1 must contain exactly width × height × 3 RGB bytes."
        }
        return checkNotNull(
            nativeInterpolate(handle, frame0Rgb24, frame1Rgb24, width, height, timestep)
        ) { "Native RIFE interpolation returned no output." }
    }

    internal fun releaseSession(handle: Long) {
        if (libraryLoaded) nativeReleaseModel(handle)
    }
}

class Rife46Session internal constructor(
    private var nativeHandle: Long,
    val backend: RifeComputeBackend
) : Closeable {

    @Synchronized
    fun interpolate(
        frame0Rgb24: ByteArray,
        frame1Rgb24: ByteArray,
        width: Int,
        height: Int,
        timestep: Float = 0.5f
    ): ByteArray {
        check(nativeHandle != 0L) { "RIFE session has already been closed." }
        return Rife46Engine.interpolate(
            nativeHandle,
            frame0Rgb24,
            frame1Rgb24,
            width,
            height,
            timestep
        )
    }

    @Synchronized
    override fun close() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            Rife46Engine.releaseSession(handle)
        }
    }
}
