package com.videoforge.ai.processing

import android.content.ContentValues
import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.os.SystemClock
import com.videoforge.ai.nativeengine.Rife46Engine
import com.videoforge.ai.nativeengine.Rife46ModelInstaller
import com.videoforge.ai.viewmodel.ProcessingStage
import com.videoforge.ai.viewmodel.VideoCodec
import com.videoforge.ai.viewmodel.VideoContainer
import com.videoforge.ai.viewmodel.VideoOutputResolution
import com.videoforge.ai.viewmodel.VideoProcessRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class VideoProcessResult(
    val outputUri: Uri,
    val fileSizeBytes: Long,
    val cleanedTempBytes: Long
)

/**
 * Full file pipeline: MediaExtractor/MediaCodec decode -> RGB24 -> RIFE 4.6 ->
 * YUV420 MediaCodec encode -> MP4 mux. Source audio packets are copied when enabled.
 */
class VideoProcessingCoordinator(context: Context) {
    private val app = context.applicationContext
    @Volatile private var paused = false

    fun setPaused(value: Boolean) { paused = value }

    suspend fun process(
        request: VideoProcessRequest,
        onProgress: (Float, String, ProcessingStage) -> Unit,
        onStatus: (String?) -> Unit
    ): VideoProcessResult = withContext(Dispatchers.IO) {
        require(request.container == VideoContainer.MP4) {
            "当前处理引擎只支持 MP4 输出；MKV 封装器尚未接入。"
        }
        require(request.targetFrameRate in 30..120) { "目标帧率必须在 30–120 FPS 之间。" }

        val startedAt = SystemClock.elapsedRealtime()
        val temp = File(app.cacheDir, "smoothai_${System.currentTimeMillis()}.mp4")
        var published: Uri? = null

        try {
            currentCoroutineContext().ensureActive()
            onProgress(1f, "正在检查 RIFE 4.6 模型", ProcessingStage.Extraction)
            val modelDir = Rife46ModelInstaller.ensureInstalled(app) { name, done, total ->
                val percent = if (total > 0L) (done * 100L / total).toInt() else 0
                onStatus("正在下载模型：$name $percent%")
            }
            onStatus("正在初始化 RIFE 4.6")
            val rife = Rife46Engine.createSession(modelDir, true, true, 2)

            rife.use {
                val videoExtractor = MediaExtractor()
                val audioExtractor = MediaExtractor()
                var decoder: MediaCodec? = null
                var encoder: MediaCodec? = null
                var muxer: MediaMuxer? = null
                var decoderStarted = false
                var encoderStarted = false
                var muxerStarted = false
                var videoExtractorOpened = false
                var audioExtractorOpened = false
                var videoTrack = -1
                var audioTrack = -1
                var encoderEos = false

                try {
                    setExtractorSource(videoExtractor, request.source.uri)
                    videoExtractorOpened = true
                    val sourceVideoTrack = findTrack(videoExtractor, "video/")
                        ?: throw IOException("源文件没有视频轨道。")
                    videoExtractor.selectTrack(sourceVideoTrack)
                    val inputFormat = videoExtractor.getTrackFormat(sourceVideoTrack)
                    val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)
                        ?: throw IOException("无法识别源视频格式。")
                    val sourceWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
                    val sourceHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                        inputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
                    } else (request.source.durationMs * 1000L).coerceAtLeast(1L)

                    val size = outputSize(sourceWidth, sourceHeight, request.outputResolution)
                    val outWidth = size.first
                    val outHeight = size.second
                    val encoderInfo = findEncoder(
                        request.videoCodec, outWidth, outHeight, request.targetFrameRate
                    )
                    val colorFormat = chooseColorFormat(encoderInfo)
                    val encoderFormat = MediaFormat.createVideoFormat(
                        request.videoCodec.mimeType, outWidth, outHeight
                    ).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                        setInteger(MediaFormat.KEY_BIT_RATE, request.bitrateMbps * 1_000_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, request.targetFrameRate)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                        if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    }

                    inputFormat.setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )
                    decoder = MediaCodec.createDecoderByType(inputMime)
                    decoder.configure(inputFormat, null, null, 0)
                    decoder.start()
                    decoderStarted = true

                    encoder = MediaCodec.createByCodecName(encoderInfo.name)
                    encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoder.start()
                    encoderStarted = true

                    muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    val rotation = if (inputFormat.containsKey("rotation-degrees")) {
                        inputFormat.getInteger("rotation-degrees")
                    } else 0
                    if (rotation in listOf(90, 180, 270)) muxer.setOrientationHint(rotation)

                    if (request.preserveAudio) {
                        setExtractorSource(audioExtractor, request.source.uri)
                        audioExtractorOpened = true
                        val sourceAudioTrack = findTrack(audioExtractor, "audio/")
                        if (sourceAudioTrack != null) {
                            audioTrack = muxer.addTrack(audioExtractor.getTrackFormat(sourceAudioTrack))
                            audioExtractor.selectTrack(sourceAudioTrack)
                        } else {
                            onStatus("源视频没有音轨，将继续生成无声视频")
                        }
                    }

                    var videoMuxerTrack = -1
                    var nextOutputPts = Long.MIN_VALUE
                    var previousPts = Long.MIN_VALUE
                    var previousRgb: ByteArray? = null
                    var sourceBasePts = 0L
                    var encodedFrames = 0L
                    var decoderInputEos = false
                    var decoderOutputEos = false
                    var lastPercent = -1
                    val intervalUs = 1_000_000L / request.targetFrameRate
                    val bufferInfo = MediaCodec.BufferInfo()
                    var audioSeeked = false
                    val audioBufferSize = if (audioTrack >= 0) {
                        runCatching {
                            audioExtractor.getTrackFormat(findTrack(audioExtractor, "audio/")!!)
                                .getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        }.getOrDefault(1 shl 20).coerceIn(64 * 1024, 8 * 1024 * 1024)
                    } else 0
                    val audioBuffer = if (audioTrack >= 0) ByteBuffer.allocateDirect(audioBufferSize) else null
                    val audioInfo = MediaCodec.BufferInfo()

                    fun writeAudioUntil(videoPtsUs: Long, flushRemaining: Boolean = false) {
                        if (audioTrack < 0 || audioBuffer == null || !muxerStarted) return
                        if (!audioSeeked) {
                            audioExtractor.seekTo(sourceBasePts, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            audioSeeked = true
                        }
                        while (true) {
                            audioBuffer.clear()
                            val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
                            if (sampleSize < 0) break
                            val absolutePts = audioExtractor.sampleTime
                            if (absolutePts < sourceBasePts) {
                                audioExtractor.advance()
                                continue
                            }
                            val relativePts = absolutePts - sourceBasePts
                            if (relativePts > durationUs) break
                            if (!flushRemaining && relativePts > videoPtsUs) break
                            audioInfo.set(0, sampleSize, relativePts, audioExtractor.sampleFlags)
                            audioBuffer.position(0)
                            audioBuffer.limit(sampleSize)
                            muxer!!.writeSampleData(audioTrack, audioBuffer, audioInfo)
                            audioExtractor.advance()
                        }
                    }

                    fun startMuxer(format: MediaFormat) {
                        if (muxerStarted) return
                        videoMuxerTrack = muxer!!.addTrack(format)
                        muxer!!.start()
                        muxerStarted = true
                    }

                    fun drainEncoder(waitForEos: Boolean): Boolean {
                        while (true) {
                            val index = encoder!!.dequeueOutputBuffer(
                                bufferInfo, if (waitForEos) 10_000L else 0L
                            )
                            when {
                                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                    if (!waitForEos) return false
                                }
                                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    startMuxer(encoder!!.outputFormat)
                                }
                                index >= 0 -> {
                                    val output = encoder!!.getOutputBuffer(index)
                                    if (bufferInfo.size > 0 && muxerStarted && output != null &&
                                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                                    ) {
                                        // Interleave source audio packets before the next video
                                        // packet so MediaMuxer receives monotonically ordered A/V samples.
                                        writeAudioUntil(bufferInfo.presentationTimeUs)
                                        output.position(bufferInfo.offset)
                                        output.limit(bufferInfo.offset + bufferInfo.size)
                                        muxer!!.writeSampleData(videoMuxerTrack, output, bufferInfo)
                                    }
                                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                    encoder!!.releaseOutputBuffer(index, false)
                                    if (eos) return true
                                }
                            }
                        }
                    }

                    suspend fun encodeFrame(rgb: ByteArray, ptsUs: Long) {
                        waitUntilSafe(request.isSafeThermal, onStatus)
                        currentCoroutineContext().ensureActive()
                        var index = MediaCodec.INFO_TRY_AGAIN_LATER
                        while (index < 0) {
                            index = encoder!!.dequeueInputBuffer(10_000L)
                            if (index < 0) {
                                drainEncoder(false)
                                currentCoroutineContext().ensureActive()
                            }
                        }
                        val input = encoder!!.getInputBuffer(index)
                            ?: throw IOException("编码器输入缓冲区不可用。")
                        input.clear()
                        val bytes = rgbToYuv(
                            rgb, outWidth, outHeight, colorFormat, encoder!!, index, input
                        )
                        encoder!!.queueInputBuffer(index, 0, bytes, ptsUs, 0)
                        encodedFrames++
                        drainEncoder(false)
                    }

                    val backendLabel = if (rife.backend.name == "VULKAN") "Vulkan GPU" else "CPU"
                    onStatus("RIFE 后端：$backendLabel")
                    onStatus("输出：${outWidth}×${outHeight} · ${request.targetFrameRate} FPS · ${request.videoCodec.title}")
                    onProgress(5f, "正在解码视频", ProcessingStage.Extraction)

                    while (!decoderOutputEos) {
                        waitUntilSafe(request.isSafeThermal, onStatus)
                        currentCoroutineContext().ensureActive()

                        if (!decoderInputEos) {
                            val inputIndex = decoder!!.dequeueInputBuffer(10_000L)
                            if (inputIndex >= 0) {
                                val input = decoder!!.getInputBuffer(inputIndex)
                                    ?: throw IOException("解码器输入缓冲区不可用。")
                                input.clear()
                                val sampleSize = videoExtractor.readSampleData(input, 0)
                                if (sampleSize < 0) {
                                    decoder!!.queueInputBuffer(
                                        inputIndex, 0, 0, 0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    decoderInputEos = true
                                } else {
                                    decoder!!.queueInputBuffer(
                                        inputIndex, 0, sampleSize,
                                        videoExtractor.sampleTime.coerceAtLeast(0L),
                                        videoExtractor.sampleFlags
                                    )
                                    videoExtractor.advance()
                                }
                            }
                        }

                        val outputIndex = decoder!!.dequeueOutputBuffer(bufferInfo, 10_000L)
                        when {
                            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                            outputIndex >= 0 -> {
                                if (bufferInfo.size > 0 &&
                                    bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                                ) {
                                    val image = decoder!!.getOutputImage(outputIndex)
                                        ?: throw IOException("解码器未提供 YUV 图像；当前解码器输出格式不兼容。")
                                    val decoded = image.use { frame ->
                                        val width = frame.cropRect.width()
                                        val height = frame.cropRect.height()
                                        Triple(imageToRgb(frame), width, height)
                                    }
                                    val rgb = if (decoded.second == outWidth && decoded.third == outHeight) {
                                        decoded.first
                                    } else {
                                        scaleRgb(decoded.first, decoded.second, decoded.third, outWidth, outHeight)
                                    }
                                    val pts = bufferInfo.presentationTimeUs.coerceAtLeast(0L)
                                    val previous = previousRgb
                                    if (previous == null) {
                                        sourceBasePts = pts
                                        nextOutputPts = pts
                                        previousPts = pts
                                        previousRgb = rgb
                                    } else if (pts > previousPts) {
                                        while (nextOutputPts < pts) {
                                            waitUntilSafe(request.isSafeThermal, onStatus)
                                            currentCoroutineContext().ensureActive()
                                            val timestep = (
                                                (nextOutputPts - previousPts).toFloat() /
                                                    (pts - previousPts).toFloat()
                                                ).coerceIn(0f, 1f)
                                            val frame = when {
                                                timestep < 0.001f -> previous
                                                timestep > 0.999f -> rgb
                                                else -> rife.interpolate(
                                                    previous, rgb, outWidth, outHeight, timestep
                                                )
                                            }
                                            encodeFrame(frame, nextOutputPts - sourceBasePts)
                                            val fraction = (
                                                (nextOutputPts - sourceBasePts).toDouble() / durationUs.toDouble()
                                            ).coerceIn(0.0, 0.98)
                                            val percent = (5.0 + fraction * 88.0).toFloat()
                                            if (percent.toInt() != lastPercent) {
                                                lastPercent = percent.toInt()
                                                val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
                                                val remaining = if (fraction > 0.01) {
                                                    (elapsed * (1.0 - fraction) / fraction / 1000.0).toLong()
                                                } else -1L
                                                val eta = if (remaining < 0L) "正在估算剩余时间"
                                                    else formatRemaining(remaining)
                                                onProgress(percent, eta, ProcessingStage.Interpolation)
                                            }
                                            nextOutputPts += intervalUs
                                        }
                                        previousPts = pts
                                        previousRgb = rgb
                                    }
                                }
                                decoderOutputEos =
                                    bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                decoder!!.releaseOutputBuffer(outputIndex, false)
                            }
                        }
                    }

                    val finalFrame = previousRgb ?: throw IOException("源视频没有解码出画面。")
                    if (encodedFrames == 0L) {
                        encodeFrame(finalFrame, 0L)
                        nextOutputPts = sourceBasePts + intervalUs
                    }
                    // Extend the final decoded frame to the source duration so the output
                    // has a complete constant-frame-rate timeline instead of ending early.
                    val endPtsUs = sourceBasePts + durationUs
                    while (nextOutputPts < endPtsUs) {
                        encodeFrame(finalFrame, nextOutputPts - sourceBasePts)
                        nextOutputPts += intervalUs
                    }
                    onProgress(94f, "正在完成编码", ProcessingStage.Encoding)

                    var eosQueued = false
                    while (!eosQueued) {
                        val index = encoder!!.dequeueInputBuffer(10_000L)
                        if (index >= 0) {
                            encoder!!.queueInputBuffer(
                                index, 0, 0,
                                (nextOutputPts - sourceBasePts).coerceAtLeast(0L),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            eosQueued = true
                        } else {
                            drainEncoder(false)
                            currentCoroutineContext().ensureActive()
                        }
                    }
                    while (!encoderEos) encoderEos = drainEncoder(true)
                    if (!muxerStarted) throw IOException("编码器未能生成有效的视频轨道。")

                    if (request.preserveAudio && audioTrack >= 0) {
                        onStatus("正在完成最后的音频数据")
                        writeAudioUntil(Long.MAX_VALUE, flushRemaining = true)
                    }

                    muxer!!.stop()
                    muxerStarted = false
                    onProgress(98f, "正在保存视频文件", ProcessingStage.Encoding)
                } finally {
                    if (decoderStarted) runCatching { decoder?.stop() }
                    if (encoderStarted) runCatching { encoder?.stop() }
                    runCatching { decoder?.release() }
                    runCatching { encoder?.release() }
                    if (muxerStarted) runCatching { muxer?.stop() }
                    runCatching { muxer?.release() }
                    if (videoExtractorOpened) runCatching { videoExtractor.release() }
                    if (audioExtractorOpened) runCatching { audioExtractor.release() }
                }
            }

            currentCoroutineContext().ensureActive()
            if (!temp.isFile || temp.length() <= 0L) throw IOException("编码器没有生成有效 MP4 文件。")
            val tempSize = temp.length()
            published = publishOutput(temp, request.source.fileName)
            val outputSize = querySize(published!!, tempSize)
            val cleanedBytes = if (temp.delete()) tempSize else 0L
            onProgress(100f, "已完成", ProcessingStage.Completed)
            onStatus(null)
            VideoProcessResult(published!!, outputSize, cleanedBytes)
        } catch (cancelled: CancellationException) {
            published?.let { runCatching { app.contentResolver.delete(it, null, null) } }
            throw cancelled
        } catch (failure: Throwable) {
            published?.let { runCatching { app.contentResolver.delete(it, null, null) } }
            throw failure
        } finally {
            temp.delete()
        }
    }

    private suspend fun waitUntilSafe(thermalProtection: Boolean, onStatus: (String?) -> Unit) {
        while (paused) {
            currentCoroutineContext().ensureActive()
            onStatus("任务已暂停")
            delay(200L)
        }
        if (thermalProtection && Build.VERSION.SDK_INT >= 29) {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            while (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                currentCoroutineContext().ensureActive()
                onStatus("设备温度过高，正在自动等待降温。系统热保护不会被绕过。")
                delay(1_000L)
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private fun setExtractorSource(extractor: MediaExtractor, uri: Uri) {
        if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            extractor.setDataSource(uri.toString())
        } else {
            extractor.setDataSource(app, uri, null)
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix, true)) return i
        }
        return null
    }

    private fun findEncoder(codec: VideoCodec, width: Int, height: Int, fps: Int): MediaCodecInfo {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(codec.mimeType, true) } }
            .sortedBy { info ->
                if (Build.VERSION.SDK_INT >= 29 && info.isSoftwareOnly) 1 else 0
            }
        return infos.firstOrNull { info ->
            runCatching {
                info.getCapabilitiesForType(codec.mimeType).videoCapabilities
                    .areSizeAndRateSupported(width, height, fps.toDouble())
            }.getOrDefault(false)
        } ?: throw IOException(
            "设备没有支持 ${codec.title}、${width}×${height}、${fps} FPS 的编码器。请降低分辨率/帧率或更换编码格式。"
        )
    }

    private fun chooseColorFormat(info: MediaCodecInfo): Int {
        val caps = info.getCapabilitiesForType(
            info.supportedTypes.first { it.startsWith("video/", true) }
        )
        val preferred = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        return preferred.firstOrNull { it in caps.colorFormats }
            ?: throw IOException("编码器不支持 SmoothAI 需要的 YUV420 输入格式。")
    }

    private fun outputSize(width: Int, height: Int, resolution: VideoOutputResolution): Pair<Int, Int> {
        val longEdge = when (resolution) {
            VideoOutputResolution.SOURCE -> 0
            VideoOutputResolution.HD_720 -> 1280
            VideoOutputResolution.FULL_HD_1080 -> 1920
            VideoOutputResolution.QHD_1440 -> 2560
            VideoOutputResolution.UHD_4K -> 3840
        }
        if (longEdge == 0) return (width and -2).coerceAtLeast(2) to (height and -2).coerceAtLeast(2)
        val scale = longEdge.toFloat() / max(width, height).toFloat()
        return (width * scale).roundToInt().and(-2).coerceAtLeast(2) to
            (height * scale).roundToInt().and(-2).coerceAtLeast(2)
    }

    private fun imageToRgb(image: Image): ByteArray {
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        val result = ByteArray(width * height * 3)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yBase = yBuf.position()
        val uBase = uBuf.position()
        val vBase = vBuf.position()
        var p = 0
        for (y in 0 until height) {
            val sy = crop.top + y
            for (x in 0 until width) {
                val sx = crop.left + x
                val yy = yBuf.get(yBase + sy * yPlane.rowStride + sx * yPlane.pixelStride).toInt() and 0xff
                val uu = (uBuf.get(uBase + (sy / 2) * uPlane.rowStride + (sx / 2) * uPlane.pixelStride).toInt() and 0xff) - 128
                val vv = (vBuf.get(vBase + (sy / 2) * vPlane.rowStride + (sx / 2) * vPlane.pixelStride).toInt() and 0xff) - 128
                val c = max(0, yy - 16)
                result[p++] = ((298 * c + 409 * vv + 128) shr 8).coerceIn(0, 255).toByte()
                result[p++] = ((298 * c - 100 * uu - 208 * vv + 128) shr 8).coerceIn(0, 255).toByte()
                result[p++] = ((298 * c + 516 * uu + 128) shr 8).coerceIn(0, 255).toByte()
            }
        }
        return result
    }

    private fun scaleRgb(
        input: ByteArray, sourceW: Int, sourceH: Int, targetW: Int, targetH: Int
    ): ByteArray {
        if (sourceW == targetW && sourceH == targetH) return input
        val output = ByteArray(targetW * targetH * 3)
        for (y in 0 until targetH) {
            val fy = (((y + 0.5f) * sourceH / targetH) - 0.5f).coerceIn(0f, (sourceH - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, sourceH - 1)
            val wy = fy - y0
            for (x in 0 until targetW) {
                val fx = (((x + 0.5f) * sourceW / targetW) - 0.5f).coerceIn(0f, (sourceW - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, sourceW - 1)
                val wx = fx - x0
                val p00 = (y0 * sourceW + x0) * 3
                val p10 = (y0 * sourceW + x1) * 3
                val p01 = (y1 * sourceW + x0) * 3
                val p11 = (y1 * sourceW + x1) * 3
                val dst = (y * targetW + x) * 3
                for (channel in 0..2) {
                    val top = (input[p00 + channel].toInt() and 255) * (1f - wx) +
                        (input[p10 + channel].toInt() and 255) * wx
                    val bottom = (input[p01 + channel].toInt() and 255) * (1f - wx) +
                        (input[p11 + channel].toInt() and 255) * wx
                    output[dst + channel] = (top * (1f - wy) + bottom * wy).roundToInt()
                        .coerceIn(0, 255).toByte()
                }
            }
        }
        return output
    }

    private fun rgbToYuv(
        rgb: ByteArray, width: Int, height: Int, colorFormat: Int,
        codec: MediaCodec, inputIndex: Int, buffer: ByteBuffer
    ): Int {
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
            val image = codec.getInputImage(inputIndex)
                ?: throw IOException("编码器没有提供 Flexible YUV420 输入 Image。")
            image.use { rgbToImage(rgb, width, height, it) }
            return width * height * 3 / 2
        }
        val frameSize = width * height
        val chromaSize = frameSize / 4
        val yPlane = ByteArray(frameSize)
        val uPlane = ByteArray(chromaSize)
        val vPlane = ByteArray(chromaSize)
        var src = 0
        for (y in 0 until height) for (x in 0 until width) {
            val r = rgb[src++].toInt() and 255
            val g = rgb[src++].toInt() and 255
            val b = rgb[src++].toInt() and 255
            yPlane[y * width + x] = (((66*r + 129*g + 25*b + 128) shr 8) + 16).coerceIn(0,255).toByte()
        }
        var c = 0
        for (y in 0 until height step 2) for (x in 0 until width step 2) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val index = ((y + dy) * width + x + dx) * 3
                r += rgb[index].toInt() and 255
                g += rgb[index + 1].toInt() and 255
                b += rgb[index + 2].toInt() and 255
            }
            r /= 4; g /= 4; b /= 4
            uPlane[c] = (((-38*r - 74*g + 112*b + 128) shr 8) + 128).coerceIn(0,255).toByte()
            vPlane[c] = (((112*r - 94*g - 18*b + 128) shr 8) + 128).coerceIn(0,255).toByte()
            c++
        }
        val needed = frameSize + frameSize / 2
        if (buffer.remaining() < needed) throw IOException("编码器输入缓冲区容量不足。")
        buffer.put(yPlane)
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
            buffer.put(uPlane); buffer.put(vPlane)
        } else if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
            for (i in 0 until chromaSize) { buffer.put(uPlane[i]); buffer.put(vPlane[i]) }
        } else throw IOException("不支持的编码器 YUV420 格式。")
        return needed
    }

    private fun rgbToImage(rgb: ByteArray, width: Int, height: Int, image: Image) {
        val p = image.planes
        if (p.size < 3) throw IOException("编码器输入 Image 不是三平面 YUV420。")
        val yBuf = p[0].buffer; val uBuf = p[1].buffer; val vBuf = p[2].buffer
        var src = 0
        for (y in 0 until height) for (x in 0 until width) {
            val r = rgb[src++].toInt() and 255
            val g = rgb[src++].toInt() and 255
            val b = rgb[src++].toInt() and 255
            val value = (((66*r + 129*g + 25*b + 128) shr 8) + 16).coerceIn(0,255)
            yBuf.put(y * p[0].rowStride + x * p[0].pixelStride, value.toByte())
        }
        for (y in 0 until height step 2) for (x in 0 until width step 2) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val i = ((y + dy) * width + x + dx) * 3
                r += rgb[i].toInt() and 255; g += rgb[i + 1].toInt() and 255; b += rgb[i + 2].toInt() and 255
            }
            r /= 4; g /= 4; b /= 4
            val u = (((-38*r - 74*g + 112*b + 128) shr 8) + 128).coerceIn(0,255)
            val v = (((112*r - 94*g - 18*b + 128) shr 8) + 128).coerceIn(0,255)
            val cx = x / 2; val cy = y / 2
            uBuf.put(cy * p[1].rowStride + cx * p[1].pixelStride, u.toByte())
            vBuf.put(cy * p[2].rowStride + cx * p[2].pixelStride, v.toByte())
        }
    }

    private fun publishOutput(temp: File, sourceName: String): Uri {
        val base = sourceName.substringBeforeLast('.', sourceName)
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifBlank { "video" }
        val name = "${base}_SmoothAI_${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SmoothAI")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = app.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在系统媒体库创建输出文件。")
            try {
                app.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                    FileInputStream(temp).use { input -> input.copyTo(output) }
                }
                app.contentResolver.update(
                    uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null, null
                )
                return uri
            } catch (error: Throwable) {
                app.contentResolver.delete(uri, null, null)
                throw error
            }
        }
        val movies = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw IOException("无法访问应用视频目录。")
        if (!movies.exists() && !movies.mkdirs()) throw IOException("无法创建输出目录。")
        val target = File(movies, name)
        temp.copyTo(target, true)
        return Uri.fromFile(target)
    }

    private fun querySize(uri: Uri, fallback: Long): Long =
        runCatching {
            app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                ?.takeIf { it > 0L } ?: fallback
        }.getOrDefault(fallback)

    private fun formatRemaining(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        return if (s >= 60L) "预计剩余 ${s / 60}分${s % 60}秒" else "预计剩余 ${s}秒"
    }
}
