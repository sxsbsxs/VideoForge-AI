package com.videoforge.ai

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.videoforge.ai.benchmark.DeviceBenchmarkViewModel
import com.videoforge.ai.ui.VideoSmoothAIApp
import com.videoforge.ai.ui.VideoSmoothAITheme
import com.videoforge.ai.viewmodel.SelectedVideoInfo
import com.videoforge.ai.viewmodel.VideoProcessRequest
import com.videoforge.ai.viewmodel.VideoProcessViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val processViewModel: VideoProcessViewModel by viewModels()
    private val benchmarkViewModel: DeviceBenchmarkViewModel by viewModels()
    private var batteryReceiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val rawTemperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            processViewModel.updateTemperature(
                if (rawTemperature > 0) rawTemperature / 10f else null
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(15, 23, 42)
        window.navigationBarColor = android.graphics.Color.rgb(15, 23, 42)

        setContent {
            VideoSmoothAITheme {
                VideoSmoothAIApp(
                    processViewModel = processViewModel,
                    onVideoPicked = ::loadVideoMetadata,
                    onStartProcess = ::onStartProcess,
                    onPauseTask = ::onPauseTask,
                    onCancelTask = ::onCancelTask,
                    benchmarkViewModel = benchmarkViewModel
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!batteryReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            batteryReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (batteryReceiverRegistered) {
            runCatching { unregisterReceiver(batteryReceiver) }
            batteryReceiverRegistered = false
        }
        super.onStop()
    }

    private fun loadVideoMetadata(uri: Uri) {
        lifecycleScope.launch {
            val videoInfo = withContext(Dispatchers.IO) {
                readVideoInfo(uri)
            }
            if (videoInfo != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                processViewModel.onVideoSelected(videoInfo)
            } else {
                processViewModel.setStatusMessage("无法读取该视频的元数据，请尝试选择本地视频文件。")
            }
        }
    }

    private fun readVideoInfo(uri: Uri): SelectedVideoInfo? {
        val displayName = runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "未命名视频"

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            var width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val originalWidth = width
                width = height
                height = originalWidth
            }
            val frameRate = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
            )?.toFloatOrNull()

            SelectedVideoInfo(
                uri = uri,
                fileName = displayName,
                durationMs = duration,
                width = width,
                height = height,
                originalFrameRate = frameRate
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * UI request integration point. A full MediaCodec decode/RIFE/encode coordinator is not
     * wired yet, so do not report a successful export until that pipeline is implemented.
     */
    private fun onStartProcess(request: VideoProcessRequest) {
        processViewModel.setStatusMessage(
            "已提交 ${request.targetFrameRate} FPS / ${request.outputResolution.title} / ${request.videoCodec.title} 参数；完整视频解码、插帧和编码管线尚未接入，当前版本不会生成输出文件。"
        )
    }

    private fun onPauseTask(shouldPause: Boolean) {
        processViewModel.setStatusMessage(
            if (shouldPause) "暂停请求已记录；实际暂停控制需由视频处理引擎接入。"
            else "继续请求已记录；实际任务恢复需由视频处理引擎接入。"
        )
    }

    private fun onCancelTask() {
        // The processing coordinator will be connected here.
    }
}
