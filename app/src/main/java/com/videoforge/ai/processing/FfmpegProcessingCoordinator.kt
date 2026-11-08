package com.videoforge.ai.processing

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.videoforge.ai.viewmodel.ProcessingStage
import com.videoforge.ai.viewmodel.VideoCodec
import com.videoforge.ai.viewmodel.VideoContainer
import com.videoforge.ai.viewmodel.VideoOutputResolution
import com.videoforge.ai.viewmodel.VideoProcessRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * FFmpeg route uses motion-compensated minterpolate. It is not a neural network;
 * choose RIFE 4.6 AI when neural optical-flow interpolation is required.
 */
class FfmpegProcessingCoordinator(context: Context) {
    private val app = context.applicationContext

    private data class ResolvedInput(val path: String, val isPlaylist: Boolean)

    suspend fun process(
        request: VideoProcessRequest,
        onProgress: (Float, String, ProcessingStage) -> Unit,
        onStatus: (String?) -> Unit
    ): VideoProcessResult = withContext(Dispatchers.IO) {
        val extension = if (request.container == VideoContainer.MKV) "mkv" else "mp4"
        val tag = System.currentTimeMillis().toString()
        val inputCopy = File(app.cacheDir, "smoothai_ffmpeg_input_$tag.bin")
        val outputTemp = File(app.cacheDir, "smoothai_ffmpeg_output_$tag.$extension")
        var copiedInputBytes = 0L
        val cleanedStaleBytes = if (request.autoCleanFragments) cleanStaleCacheFiles() else 0L
        try {
            onProgress(1f, "正在准备 FFmpeg", ProcessingStage.Extraction)
            require(request.targetFrameRate in 30..120) { "目标帧率必须在 30–120 FPS 之间。" }
            require(request.bitrateMbps in 2..100) { "目标码率必须在 2–100 Mbps 之间。" }
            val input = resolveInputPath(request.source.uri, inputCopy, onStatus)
            val inputPath = input.path
            if (inputPath == inputCopy.absolutePath) copiedInputBytes = inputCopy.length()
            onStatus("正在检测 FFmpeg 编码器")
            val encoder = findEncoder(request.videoCodec)
            val durationMs = request.source.durationMs.takeIf { it > 0L }
                ?: probeDurationMs(inputPath, input.isPlaylist)
            if (input.isPlaylist && durationMs <= 0L) {
                throw IOException("FFmpeg 只处理有明确时长的 HLS/DASH 点播清单；检测到直播或无法确定时长的清单，已停止以防无限处理。")
            }
            val filter = buildVideoFilter(request.targetFrameRate, request.outputResolution)

            val args = mutableListOf("-hide_banner", "-y")
            if (input.isPlaylist) {
                args += listOf("-protocol_whitelist", "https,tls,tcp,crypto,data")
            }
            args += listOf(
                "-i", inputPath,
                "-map", "0:v:0", "-map_metadata", "0", "-map_chapters", "0",
                "-vf", filter, "-c:v", encoder, "-b:v", "${request.bitrateMbps}M",
                "-pix_fmt", "yuv420p", "-fps_mode", "cfr", "-max_muxing_queue_size", "4096"
            )
            if (request.preserveAudio) {
                args += listOf("-map", "0:a?", "-c:a", if (request.container == VideoContainer.MP4) "aac" else "copy")
            } else {
                args += listOf("-an")
            }
            if (request.container == VideoContainer.MKV) {
                args += listOf("-map", "0:s?", "-c:s", "copy", "-f", "matroska")
            } else {
                val canConvertTextSubtitles = hasMovTextCompatibleSubtitles(inputPath, input.isPlaylist)
                if (canConvertTextSubtitles) {
                    onStatus("检测到文本字幕，正在转换为 MP4 mov_text 字幕")
                    args += listOf("-map", "0:s?", "-c:s", "mov_text", "-movflags", "+faststart", "-f", "mp4")
                } else {
                    onStatus("MP4 输出：未检测到可转换的文本字幕，跳过字幕轨道")
                    args += listOf("-sn", "-movflags", "+faststart", "-f", "mp4")
                }
            }
            args += outputTemp.absolutePath

            onStatus("FFmpeg · $encoder · ${request.targetFrameRate} FPS · ${request.container.title}")
            onProgress(4f, "正在运动估算插帧与编码", ProcessingStage.Interpolation)
            runFfmpeg(args, durationMs, request.isSafeThermal, onProgress, onStatus)
            if (!outputTemp.isFile || outputTemp.length() <= 0L) {
                throw IOException("FFmpeg 没有生成有效输出文件。")
            }

            onProgress(96f, "正在保存到 Movies/SmoothAI", ProcessingStage.Encoding)
            val tempSize = outputTemp.length()
            val uri = publishOutput(outputTemp, request.source.fileName, request.container)
            val outputSize = querySize(uri, tempSize)
            outputTemp.delete()
            inputCopy.delete()
            onProgress(100f, "已完成", ProcessingStage.Completed)
            onStatus(null)
            VideoProcessResult(uri, outputSize, tempSize + copiedInputBytes + cleanedStaleBytes)
        } catch (cancelled: CancellationException) {
            outputTemp.delete()
            inputCopy.delete()
            throw cancelled
        } catch (failure: Throwable) {
            outputTemp.delete()
            inputCopy.delete()
            throw failure
        }
    }

    private suspend fun resolveInputPath(
        uri: Uri,
        cacheFile: File,
        onStatus: (String?) -> Unit
    ): ResolvedInput {
        val scheme = uri.scheme.orEmpty()
        if (scheme.equals("http", true) || scheme.equals("https", true)) {
            if (scheme.equals("http", true)) {
                throw IOException("FFmpeg 模式的网络视频请使用 HTTPS 直链；明文 HTTP 下载已禁用。")
            }
            val path = uri.path.orEmpty().lowercase()
            if (path.endsWith(".m3u8") || path.endsWith(".mpd")) {
                onStatus(if (path.endsWith(".m3u8")) "FFmpeg 正在读取 HTTPS HLS 点播清单…" else "FFmpeg 正在读取 HTTPS DASH 点播清单…")
                return ResolvedInput(uri.toString(), isPlaylist = true)
            }

            onStatus("正在通过 HTTPS 下载视频到本地缓存…")
            val connection = (URL(uri.toString()).openConnection() as? HttpURLConnection)
                ?: throw IOException("无法创建 HTTPS 下载连接。")
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "SmoothAI-Android")
            try {
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IOException("HTTPS 视频下载失败：HTTP ${connection.responseCode}")
                }
                if (!connection.url.protocol.equals("https", true)) {
                    throw IOException("服务器将 HTTPS 请求重定向到了非加密地址，已阻止下载。")
                }
                val contentType = connection.contentType.orEmpty().lowercase()
                if (contentType.contains("mpegurl") || contentType.contains("dash+xml")) {
                    onStatus("检测到 HTTPS 流媒体点播清单，交由 FFmpeg 读取…")
                    cacheFile.delete()
                    return ResolvedInput(uri.toString(), isPlaylist = true)
                }
                if (contentType.startsWith("text/html")) {
                    throw IOException("该地址返回的是网页，不是可直接处理的视频文件。")
                }
                val totalBytes = connection.contentLengthLong
                connection.inputStream.use { input ->
                    cacheFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastReported = -10
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            if (totalBytes > 0L) {
                                val percent = (copied * 100L / totalBytes).toInt()
                                if (percent >= lastReported + 10) {
                                    lastReported = percent
                                    onStatus("HTTPS 视频下载：${percent.coerceAtMost(100)}%")
                                }
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (!cacheFile.isFile || cacheFile.length() == 0L) {
                throw IOException("HTTPS 视频下载结果为空。")
            }
            return ResolvedInput(cacheFile.absolutePath, isPlaylist = false)
        }

        if (scheme.equals("file", true)) {
            return ResolvedInput(uri.path ?: throw IOException("本地视频路径无效。"), isPlaylist = false)
        }
        val input = app.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取视频，请重新选择文件。")
        input.use { source ->
            cacheFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
        if (!cacheFile.isFile || cacheFile.length() == 0L) {
            throw IOException("视频复制到临时目录失败。")
        }
        return ResolvedInput(cacheFile.absolutePath, isPlaylist = false)
    }

    private fun probeDurationMs(path: String, isPlaylist: Boolean): Long {
        val args = mutableListOf("-v", "error")
        if (isPlaylist) {
            args += listOf("-protocol_whitelist", "https,tls,tcp,crypto,data")
        }
        args += listOf(
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1", path
        )
        val session = FFprobeKit.executeWithArguments(args.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode)) return 0L
        return session.getOutput()?.trim()?.toDoubleOrNull()?.times(1000.0)?.toLong()?.coerceAtLeast(0L) ?: 0L
    }

    private fun hasMovTextCompatibleSubtitles(path: String, isPlaylist: Boolean): Boolean {
        val args = mutableListOf("-v", "error")
        if (isPlaylist) {
            args += listOf("-protocol_whitelist", "https,tls,tcp,crypto,data")
        }
        args += listOf(
            "-select_streams", "s",
            "-show_entries", "stream=codec_name",
            "-of", "csv=p=0",
            path
        )
        val session = FFprobeKit.executeWithArguments(args.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode)) return false
        val codecs = session.getOutput().orEmpty()
            .lineSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toList()
        if (codecs.isEmpty()) return false

        val supportedTextCodecs = setOf(
            "subrip", "ass", "ssa", "webvtt", "mov_text", "text"
        )
        return codecs.all { it in supportedTextCodecs }
    }

    private fun findEncoder(codec: VideoCodec): String {
        val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-encoders"))
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw IOException("无法读取 FFmpeg 编码器列表：${session.getFailStackTrace() ?: session.output.orEmpty()}")
        }
        val lines = session.output.orEmpty().lineSequence().map { it.trim() }.toList()
        val candidates = when (codec) {
            VideoCodec.H264 -> listOf("h264_mediacodec", "libopenh264")
            VideoCodec.H265 -> listOf("hevc_mediacodec", "libkvazaar")
            VideoCodec.AV1 -> listOf("av1_mediacodec", "libaom-av1", "librav1e")
        }
        return candidates.firstOrNull { name -> lines.any { it.split(Regex("\\s+")).contains(name) } }
            ?: throw IOException(
                "当前 FFmpeg 构建没有找到 ${codec.title} 编码器。尝试格式：${candidates.joinToString()}。"
            )
    }

    private fun buildVideoFilter(fps: Int, resolution: VideoOutputResolution): String {
        val edge = when (resolution) {
            VideoOutputResolution.SOURCE -> 0
            VideoOutputResolution.HD_720 -> 1280
            VideoOutputResolution.FULL_HD_1080 -> 1920
            VideoOutputResolution.QHD_1440 -> 2560
            VideoOutputResolution.UHD_4K -> 3840
        }
        val motion = "minterpolate=fps=$fps:mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1"
        return if (edge == 0) "$motion,format=yuv420p"
        else "$motion,scale=$edge:$edge:force_original_aspect_ratio=decrease:force_divisible_by=2,format=yuv420p"
    }

    private suspend fun runFfmpeg(
        args: List<String>,
        durationMs: Long,
        thermalProtection: Boolean,
        onProgress: (Float, String, ProcessingStage) -> Unit,
        onStatus: (String?) -> Unit
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val sessionRef = AtomicReference<FFmpegSession?>(null)
        val thermalAbort = AtomicBoolean(false)
        val thermalCriticalSince = AtomicLong(0L)
        val thermalWarningShown = AtomicBoolean(false)
        val startedAt = SystemClock.elapsedRealtime()
        val logs = ArrayDeque<String>()

        val session = FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            { completed ->
                if (!continuation.isActive) return@executeWithArgumentsAsync
                when {
                    thermalAbort.get() -> continuation.resumeWithException(
                        IllegalStateException("温控保护已终止 FFmpeg 任务；设备冷却后可以重新处理。")
                    )
                    ReturnCode.isSuccess(completed.getReturnCode()) -> continuation.resume(Unit)
                    ReturnCode.isCancel(completed.returnCode) -> continuation.resumeWithException(
                        CancellationException("FFmpeg 任务已取消。")
                    )
                    else -> {
                        val detail = completed.getFailStackTrace()?.takeIf { it.isNotBlank() }
                            ?: logs.toList().takeLast(8).joinToString("\n")
                        continuation.resumeWithException(IOException("FFmpeg 处理失败：${detail.takeLast(1600)}"))
                    }
                }
            },
            { log ->
                val line = log.getMessage()?.trim().orEmpty()
                if (line.isNotBlank()) {
                    synchronized(logs) {
                        if (logs.size >= 40) logs.removeFirst()
                        logs.addLast(line)
                    }
                    if (line.contains("error", true)) onStatus(line.take(180))
                }
            },
            { stats ->
                if (!continuation.isActive) return@executeWithArgumentsAsync
                if (thermalProtection && Build.VERSION.SDK_INT >= 29) {
                    val thermalStatus = runCatching {
                        app.getSystemService(PowerManager::class.java)?.currentThermalStatus
                    }.getOrNull() ?: PowerManager.THERMAL_STATUS_NONE

                    // SEVERE is an advisory state and must not kill a job by itself.
                    if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE &&
                        thermalWarningShown.compareAndSet(false, true)
                    ) {
                        onStatus("系统报告严重温控等级；任务会继续运行，Android 系统仍保留自身温控。")
                    }

                    if (thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL) {
                        val now = SystemClock.elapsedRealtime()
                        val firstCriticalAt = thermalCriticalSince.updateAndGet { previous ->
                            if (previous == 0L) now else previous
                        }
                        if (now - firstCriticalAt >= 5_000L) {
                            thermalAbort.set(true)
                            sessionRef.get()?.let { FFmpegKit.cancel(it.getSessionId()) }
                            onStatus("系统持续报告临界温控等级超过 5 秒，已停止任务保护设备。")
                            return@executeWithArgumentsAsync
                        }
                    } else {
                        thermalCriticalSince.set(0L)
                    }
                }
                val timeMs = stats.getTime().toLong().coerceAtLeast(0L)
                val fraction = if (durationMs > 0L) (timeMs.toDouble() / durationMs).coerceIn(0.0, 0.99) else 0.0
                val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
                val remain = if (fraction > 0.01) (elapsed * (1.0 - fraction) / fraction / 1000.0).toLong() else -1L
                val eta = if (remain < 0L) "FFmpeg 正在处理"
                    else if (remain >= 60L) "预计剩余 ${remain / 60}分${remain % 60}秒"
                    else "预计剩余 ${remain}秒"
                val percent = if (durationMs > 0L) (4.0 + fraction * 90.0).toFloat() else 4f
                onProgress(percent, eta, ProcessingStage.Interpolation)
            }
        )
        sessionRef.set(session)
        continuation.invokeOnCancellation { FFmpegKit.cancel(session.getSessionId()) }
    }

    private fun cleanStaleCacheFiles(): Long {
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        var removedBytes = 0L
        app.cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("smoothai_ffmpeg_") && it.lastModified() < cutoff }
            ?.forEach { file ->
                val size = file.length()
                if (file.delete()) removedBytes += size
            }
        return removedBytes
    }

    private fun publishOutput(temp: File, sourceName: String, container: VideoContainer): Uri {
        val mkv = container == VideoContainer.MKV
        val ext = if (mkv) "mkv" else "mp4"
        val mime = if (mkv) "video/x-matroska" else "video/mp4"
        val base = sourceName.substringBeforeLast('.', sourceName)
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifBlank { "video" }
        val name = "${base}_SmoothAI_${System.currentTimeMillis()}.$ext"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SmoothAI")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = app.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在系统媒体库创建输出文件。")
            try {
                app.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                    FileInputStream(temp).use { input -> input.copyTo(output) }
                }
                app.contentResolver.update(uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                return uri
            } catch (error: Throwable) {
                app.contentResolver.delete(uri, null, null)
                throw error
            }
        }
        val directory = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw IOException("无法访问应用视频目录。")
        if (!directory.exists() && !directory.mkdirs()) throw IOException("无法创建 SmoothAI 输出目录。")
        val target = File(directory, name)
        temp.copyTo(target, overwrite = true)
        return Uri.fromFile(target)
    }

    private fun querySize(uri: Uri, fallback: Long): Long =
        runCatching { app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0L } ?: fallback }
            .getOrDefault(fallback)
}
