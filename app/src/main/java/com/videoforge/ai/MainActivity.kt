package com.videoforge.ai

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
     * Engine integration point. Connect the native RIFE/FFmpeg pipeline here.
     * The UI and ViewModel intentionally do not implement media processing.
     */
    private fun onStartProcess(request: VideoProcessRequest) {
        // TODO: Dispatch request to the video-processing engine.
    }

    private fun onPauseTask(shouldPause: Boolean) {
        // TODO: Forward pause/resume to the video-processing engine.
    }

    private fun onCancelTask() {
        // TODO: Forward cancellation to the video-processing engine.
    }
}
