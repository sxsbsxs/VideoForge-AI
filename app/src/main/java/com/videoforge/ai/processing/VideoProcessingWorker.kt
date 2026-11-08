package com.videoforge.ai.processing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.videoforge.ai.R
import com.videoforge.ai.viewmodel.ProcessingEngine
import com.videoforge.ai.viewmodel.ProcessingStage
import com.videoforge.ai.viewmodel.SelectedVideoInfo
import com.videoforge.ai.viewmodel.VideoCodec
import com.videoforge.ai.viewmodel.VideoContainer
import com.videoforge.ai.viewmodel.VideoOutputResolution
import com.videoforge.ai.viewmodel.VideoProcessRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Long-running foreground work. WorkManager persists the request and can restart the worker
 * after process death; processing restarts from the beginning because no frame checkpoint exists yet.
 */
class VideoProcessingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val request = decodeRequest(inputData) ?: return Result.failure(
            workDataOfError("任务参数缺失或损坏，请重新选择视频。")
        )

        try {
            ProcessingRuntime.paused = false
            setForeground(createForegroundInfo())
            val latestProgress = AtomicReference(0f)
            val latestEta = AtomicReference("正在准备")
            val latestStage = AtomicReference(ProcessingStage.Extraction.name)
            val latestStatus = AtomicReference<String?>(null)
            fun writeProgress() {
                runBlocking(Dispatchers.IO) {
                    val data = Data.Builder()
                        .putFloat(KEY_PROGRESS, latestProgress.get())
                        .putString(KEY_ETA, latestEta.get())
                        .putString(KEY_STAGE, latestStage.get())
                        .putString(KEY_STATUS, latestStatus.get())
                        .build()
                    setProgress(data)
                }
            }
            val progressCallback: (Float, String, ProcessingStage) -> Unit =
                { percent, eta, stage ->
                    latestProgress.set(percent)
                    latestEta.set(eta)
                    latestStage.set(stage.name)
                    writeProgress()
                }
            val statusCallback: (String?) -> Unit = { status ->
                latestStatus.set(status)
                writeProgress()
            }

            val result = when (request.processingEngine) {
                ProcessingEngine.RIFE_AI -> VideoProcessingCoordinator(applicationContext)
                    .process(request, progressCallback, statusCallback)
                ProcessingEngine.FFMPEG -> FfmpegProcessingCoordinator(applicationContext)
                    .process(request, progressCallback, statusCallback)
            }

            return Result.success(
                Data.Builder()
                    .putString(KEY_OUTPUT_URI, result.outputUri.toString())
                    .putLong(KEY_OUTPUT_SIZE, result.fileSizeBytes)
                    .putLong(KEY_CLEANED_BYTES, result.cleanedTempBytes)
                    .build()
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return Result.failure(
                workDataOfError(failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName)
            )
        } finally {
            ProcessingRuntime.paused = false
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "SmoothAI 视频处理",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "显示当前视频处理任务状态"
                    setShowBadge(false)
                }
            )
        }

        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("SmoothAI 正在处理视频")
            .setContentText("任务会在后台继续运行")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()

        return when {
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
            Build.VERSION.SDK_INT >= 34 -> ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            else -> ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val TAG = "smoothai_video_processing"
        const val PREF_WORK_ID = "active_processing_work_id"

        const val KEY_URI = "source_uri"
        const val KEY_NAME = "source_name"
        const val KEY_DURATION = "source_duration_ms"
        const val KEY_WIDTH = "source_width"
        const val KEY_HEIGHT = "source_height"
        const val KEY_FPS = "source_fps"
        const val KEY_NETWORK = "source_network"
        const val KEY_TARGET_FPS = "target_fps"
        const val KEY_RESOLUTION = "target_resolution"
        const val KEY_CODEC = "target_codec"
        const val KEY_CONTAINER = "target_container"
        const val KEY_BITRATE = "target_bitrate"
        const val KEY_THERMAL = "thermal_protection"
        const val KEY_AUTO_CLEAN = "auto_clean"
        const val KEY_AUDIO = "preserve_audio"
        const val KEY_ENGINE = "processing_engine"

        const val KEY_PROGRESS = "progress_percent"
        const val KEY_ETA = "progress_eta"
        const val KEY_STAGE = "progress_stage"
        const val KEY_STATUS = "progress_status"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_OUTPUT_SIZE = "output_size"
        const val KEY_CLEANED_BYTES = "cleaned_bytes"
        const val KEY_ERROR = "worker_error"

        private const val CHANNEL_ID = "smoothai_processing"
        private const val NOTIFICATION_ID = 7406

        fun encodeRequest(request: VideoProcessRequest): Data =
            Data.Builder()
                .putString(KEY_URI, request.source.uri.toString())
                .putString(KEY_NAME, request.source.fileName)
                .putLong(KEY_DURATION, request.source.durationMs)
                .putInt(KEY_WIDTH, request.source.width)
                .putInt(KEY_HEIGHT, request.source.height)
                .putFloat(KEY_FPS, request.source.originalFrameRate ?: -1f)
                .putBoolean(KEY_NETWORK, request.source.isNetworkSource)
                .putInt(KEY_TARGET_FPS, request.targetFrameRate)
                .putString(KEY_RESOLUTION, request.outputResolution.name)
                .putString(KEY_CODEC, request.videoCodec.name)
                .putString(KEY_CONTAINER, request.container.name)
                .putInt(KEY_BITRATE, request.bitrateMbps)
                .putBoolean(KEY_THERMAL, request.isSafeThermal)
                .putBoolean(KEY_AUTO_CLEAN, request.autoCleanFragments)
                .putBoolean(KEY_AUDIO, request.preserveAudio)
                .putString(KEY_ENGINE, request.processingEngine.name)
                .build()

        private fun decodeRequest(data: Data): VideoProcessRequest? = runCatching {
            val sourceUri = Uri.parse(data.getString(KEY_URI) ?: error("missing source uri"))
            val source = SelectedVideoInfo(
                uri = sourceUri,
                fileName = data.getString(KEY_NAME) ?: "video",
                durationMs = data.getLong(KEY_DURATION, 0L),
                width = data.getInt(KEY_WIDTH, 0),
                height = data.getInt(KEY_HEIGHT, 0),
                originalFrameRate = data.getFloat(KEY_FPS, -1f).takeIf { it > 0f },
                isNetworkSource = data.getBoolean(KEY_NETWORK, false)
            )
            VideoProcessRequest(
                source = source,
                targetFrameRate = data.getInt(KEY_TARGET_FPS, 60),
                outputResolution = enumValueOf(data.getString(KEY_RESOLUTION) ?: "FULL_HD_1080"),
                videoCodec = enumValueOf(data.getString(KEY_CODEC) ?: "H264"),
                container = enumValueOf(data.getString(KEY_CONTAINER) ?: "MP4"),
                bitrateMbps = data.getInt(KEY_BITRATE, 16),
                isSafeThermal = data.getBoolean(KEY_THERMAL, true),
                autoCleanFragments = data.getBoolean(KEY_AUTO_CLEAN, true),
                preserveAudio = data.getBoolean(KEY_AUDIO, true),
                processingEngine = enumValueOf(data.getString(KEY_ENGINE) ?: "RIFE_AI")
            )
        }.getOrNull()

        private fun workDataOfError(message: String): Data =
            Data.Builder().putString(KEY_ERROR, message.take(1800)).build()
    }
}
