package com.videoforge.ai.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class VideoPageRoute { Home, Settings, Processing, Result, Benchmark }

enum class VideoOutputResolution(val title: String) {
    SOURCE("原始分辨率"),
    HD_720("720p"),
    FULL_HD_1080("1080p"),
    QHD_1440("1440p"),
    UHD_4K("4K")
}
enum class ProcessingStage { Extraction, Interpolation, Encoding, Completed }

data class SelectedVideoInfo(
    val uri: Uri,
    val fileName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val originalFrameRate: Float? = null
) {
    val resolution: String
        get() = if (width > 0 && height > 0) "${width}×${height}" else "未知"
}

data class VideoProcessRequest(
    val source: SelectedVideoInfo,
    val targetFrameRate: Int,
    val outputResolution: VideoOutputResolution,
    val isExtremeHeatMode: Boolean,
    val autoCleanFragments: Boolean
)

data class VideoProcessUiState(
    val currentPage: VideoPageRoute = VideoPageRoute.Home,
    val selectedVideo: SelectedVideoInfo? = null,
    val targetFrameRate: Int = 60,
    val outputResolution: VideoOutputResolution = VideoOutputResolution.SOURCE,
    val isExtremeHeatMode: Boolean = false,
    val autoCleanFragments: Boolean = true,
    val progressPercent: Float = 0f,
    val estimatedRemainingTime: String = "等待开始",
    val currentStage: ProcessingStage = ProcessingStage.Extraction,
    val isOverheated: Boolean = false,
    val isPaused: Boolean = false
)

class VideoProcessViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(VideoProcessUiState())
    val uiState: StateFlow<VideoProcessUiState> = _uiState.asStateFlow()

    fun onVideoSelected(video: SelectedVideoInfo) {
        _uiState.update { it.copy(selectedVideo = video) }
    }

    fun openSettings() {
        _uiState.update { it.copy(currentPage = VideoPageRoute.Settings) }
    }

    fun backToHome() {
        _uiState.update { it.copy(currentPage = VideoPageRoute.Home) }
    }

    fun openBenchmark() {
        _uiState.update { it.copy(currentPage = VideoPageRoute.Benchmark) }
    }

    fun setTargetFrameRate(value: Int) {
        if (value in setOf(30, 60, 90, 120)) {
            _uiState.update { it.copy(targetFrameRate = value) }
        }
    }

    fun setOutputResolution(value: VideoOutputResolution) {
        _uiState.update { it.copy(outputResolution = value) }
    }

    fun setExtremeHeatMode(enabled: Boolean) {
        _uiState.update { it.copy(isExtremeHeatMode = enabled) }
    }

    fun setAutoCleanFragments(enabled: Boolean) {
        _uiState.update { it.copy(autoCleanFragments = enabled) }
    }

    /** Builds the request and moves the UI to Processing. Actual video work is supplied by the host callback. */
    fun createProcessRequest(): VideoProcessRequest? {
        val current = _uiState.value
        val source = current.selectedVideo ?: return null
        val request = VideoProcessRequest(
            source = source,
            targetFrameRate = current.targetFrameRate,
            outputResolution = current.outputResolution,
            isExtremeHeatMode = current.isExtremeHeatMode,
            autoCleanFragments = current.autoCleanFragments
        )
        _uiState.update {
            it.copy(
                currentPage = VideoPageRoute.Processing,
                progressPercent = 0f,
                estimatedRemainingTime = "正在估算",
                currentStage = ProcessingStage.Extraction,
                isOverheated = false,
                isPaused = false
            )
        }
        return request
    }

    fun updateProgress(
        percent: Float,
        remainingTime: String,
        stage: ProcessingStage
    ) {
        _uiState.update {
            it.copy(
                progressPercent = percent.coerceIn(0f, 100f),
                estimatedRemainingTime = remainingTime,
                currentStage = stage,
                currentPage = if (stage == ProcessingStage.Completed) VideoPageRoute.Result else it.currentPage
            )
        }
    }

    fun setOverheated(overheated: Boolean) {
        _uiState.update {
            it.copy(
                isOverheated = overheated,
                // Thermal protection pauses the UI immediately; cooling down does not auto-resume work.
                isPaused = if (overheated) true else it.isPaused
            )
        }
    }

    fun setPaused(paused: Boolean) {
        _uiState.update { it.copy(isPaused = paused) }
    }

    fun cancelCurrentTask() {
        _uiState.update {
            it.copy(
                currentPage = VideoPageRoute.Settings,
                progressPercent = 0f,
                estimatedRemainingTime = "已取消",
                isPaused = false,
                isOverheated = false
            )
        }
    }
}
